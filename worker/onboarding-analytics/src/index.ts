export interface Env {
  DB: D1Database;
  INGEST_TOKEN?: string;
  MAX_BATCH_SIZE?: string;
}

type OnboardingValue = string | number;
type OnboardingProperties = Record<string, OnboardingValue>;

type OnboardingEventRow = {
  id: string;
  installId: string;
  eventName: string;
  properties: OnboardingProperties;
};

const MAX_BODY_BYTES = 64 * 1024;
const DEFAULT_MAX_BATCH_SIZE = 50;

const EVENT_NAMES = new Set([
  "onboarding_started",
  "onboarding_step_viewed",
  "onboarding_location_authorization_requested",
  "onboarding_location_authorization_completed",
  "onboarding_tracking_intent_changed",
  "onboarding_completed",
  "onboarding_paywall_shown",
  "onboarding_purchase_started",
  "onboarding_purchase_finished",
  "onboarding_restore_started",
  "onboarding_restore_finished",
]);

const STRING_PROPERTY_KEYS = new Set([
  "appVersion",
  "buildNumber",
  "platform",
  "onboardingStep",
  "authorizationStatus",
  "authorizationRequestKind",
  "trackingIntent",
  "paywallContext",
  "productId",
  "purchaseOutcome",
  "errorCategory",
]);

const ALLOWED_PROPERTY_KEYS = new Set([...STRING_PROPERTY_KEYS]);

const PLATFORMS = new Set(["ios", "android"]);
const ONBOARDING_STEPS = new Set(["welcome", "features", "permissions", "notifications", "ready"]);
const AUTHORIZATION_STATUSES = new Set([
  "authorized",
  "not_determined",
  "when_in_use",
  "always",
  "denied",
  "restricted",
  "unknown",
]);
const AUTHORIZATION_REQUEST_KINDS = new Set(["when_in_use", "always", "settings", "notifications"]);
const TRACKING_INTENTS = new Set(["start_immediately", "later", "unavailable"]);
const PAYWALL_CONTEXTS = new Set(["export", "settings", "webhook", "onboarding"]);
const PRODUCT_IDS = new Set([
  "whereday_export_lifetime",
  "com.bontecou.isome.lifetime.individual",
  "com.bontecou.isome.lifetime",
  "com.bontecou.isome.lifetime.family.upgrade",
]);
const PURCHASE_OUTCOMES = new Set([
  "started",
  "succeeded",
  "failed",
  "cancelled",
  "pending",
  "restored",
  "not_found",
]);
const ERROR_CATEGORIES = new Set([
  "product_unavailable",
  "store_unavailable",
  "network_unavailable",
  "user_cancelled",
  "verification_failed",
  "payment_pending",
  "not_unlocked",
  "unknown",
]);

const APP_VERSION_RE = /^\d+(?:\.\d+){0,3}$/;
const BUILD_NUMBER_RE = /^\d{1,12}$/;
const INSTALL_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const pathname = normalizedPathname(url.pathname);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (request.method === "GET" && pathname === "/health") {
      return json({ ok: true, service: "iso-me-onboarding-analytics" });
    }

    if (request.method === "POST" && pathname === "/v1/events") {
      return ingestEvents(request, env);
    }

    return json({ ok: false, error: "not_found" }, 404);
  },
};

// Bound unknown/chunked bodies before materializing or decoding request text.
async function boundedBody(request: Request): Promise<string | null> {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) { await reader.cancel(); return null; }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

async function ingestEvents(request: Request, env: Env): Promise<Response> {
  const authError = authorize(request, env);
  if (authError) return authError;

  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    return json({ ok: false, error: "body_too_large" }, 413);
  }

  let rawBody: string | null;
  try { rawBody = await boundedBody(request); }
  catch { return json({ ok: false, error: "invalid_body" }, 400); }
  if (rawBody === null) return json({ ok: false, error: "body_too_large" }, 413);

  let body: unknown;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return json({ ok: false, error: "invalid_json" }, 400);
  }

  let rows: OnboardingEventRow[];
  try {
    rows = normalizeIngestBody(body, maxBatchSize(env));
  } catch (error) {
    return json({ ok: false, error: error instanceof Error ? error.message : "invalid_payload" }, 400);
  }

  if (rows.length === 0) {
    return json({ ok: false, error: "empty_batch" }, 400);
  }

  const insert = env.DB.prepare(`
    INSERT OR IGNORE INTO onboarding_events (
      id,
      install_id,
      event_name,
      app_version,
      build_number,
      platform,
      onboarding_step,
      authorization_status,
      authorization_request_kind,
      tracking_intent,
      paywall_context,
      product_id,
      purchase_outcome,
      error_category,
      payload_json
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  await env.DB.batch(rows.map((row) => insert.bind(
    row.id,
    row.installId,
    row.eventName,
    stringProperty(row.properties, "appVersion"),
    stringProperty(row.properties, "buildNumber"),
    stringProperty(row.properties, "platform"),
    stringProperty(row.properties, "onboardingStep"),
    stringProperty(row.properties, "authorizationStatus"),
    stringProperty(row.properties, "authorizationRequestKind"),
    stringProperty(row.properties, "trackingIntent"),
    stringProperty(row.properties, "paywallContext"),
    stringProperty(row.properties, "productId"),
    stringProperty(row.properties, "purchaseOutcome"),
    stringProperty(row.properties, "errorCategory"),
    JSON.stringify({ eventName: row.eventName, properties: row.properties }),
  )));

  return json({ ok: true, accepted: rows.length });
}

function normalizeIngestBody(body: unknown, maxBatch: number): OnboardingEventRow[] {
  if (!isObject(body)) throw new Error("payload_must_be_object");

  const batchInstallId = optionalString(body.installId);
  const incomingEvents = Array.isArray(body.events) ? body.events : [body];

  if (incomingEvents.length > maxBatch) throw new Error("batch_too_large");

  return incomingEvents.map((event) => normalizeEvent(event, batchInstallId));
}

function normalizeEvent(event: unknown, batchInstallId: string | undefined): OnboardingEventRow {
  if (!isObject(event)) throw new Error("event_must_be_object");

  const eventName = requiredString(event.eventName, "eventName");
  if (!EVENT_NAMES.has(eventName)) throw new Error("unknown_event_name");

  const eventId = validateEventId(optionalString(event.eventId) ?? optionalString(event.id));
  const installId = validateInstallId(optionalString(event.installId) ?? batchInstallId);
  const properties = normalizeProperties(isObject(event.properties) ? event.properties : {});

  return {
    id: eventId,
    installId,
    eventName,
    properties,
  };
}

function normalizeProperties(properties: Record<string, unknown>): OnboardingProperties {
  const normalized: OnboardingProperties = {};

  for (const [key, value] of Object.entries(properties)) {
    if (!ALLOWED_PROPERTY_KEYS.has(key)) throw new Error(`unknown_property:${key}`);

    if (STRING_PROPERTY_KEYS.has(key)) {
      normalized[key] = validateStringProperty(key, value);
      continue;
    }
  }

  if (normalized.authorizationStatus === "authorized" && (normalized.platform !== "android" || normalized.authorizationRequestKind !== "notifications")) {
    throw new Error("invalid_notification_authorization");
  }
  const androidProduct = normalized.productId === "whereday_export_lifetime";
  if (normalized.platform === "android" && normalized.productId && !androidProduct) {
    throw new Error("invalid_platform_product");
  }
  if ((androidProduct || normalized.onboardingStep === "notifications" || normalized.authorizationRequestKind === "notifications") && normalized.platform !== "android") {
    throw new Error("invalid_platform_specific_property");
  }
  return normalized;
}

function validateStringProperty(key: string, value: unknown): string {
  if (typeof value !== "string") throw new Error(`invalid_property_type:${key}`);

  switch (key) {
    case "appVersion":
      if (!APP_VERSION_RE.test(value)) throw new Error(`invalid_property:${key}`);
      return value;
    case "buildNumber":
      if (!BUILD_NUMBER_RE.test(value)) throw new Error(`invalid_property:${key}`);
      return value;
    case "platform":
      return validateSetValue(key, value, PLATFORMS);
    case "onboardingStep":
      return validateSetValue(key, value, ONBOARDING_STEPS);
    case "authorizationStatus":
      return validateSetValue(key, value, AUTHORIZATION_STATUSES);
    case "authorizationRequestKind":
      return validateSetValue(key, value, AUTHORIZATION_REQUEST_KINDS);
    case "trackingIntent":
      return validateSetValue(key, value, TRACKING_INTENTS);
    case "paywallContext":
      return validateSetValue(key, value, PAYWALL_CONTEXTS);
    case "productId":
      return validateSetValue(key, value, PRODUCT_IDS);
    case "purchaseOutcome":
      return validateSetValue(key, value, PURCHASE_OUTCOMES);
    case "errorCategory":
      return validateSetValue(key, value, ERROR_CATEGORIES);
    default:
      throw new Error(`unknown_property:${key}`);
  }
}

function validateSetValue(key: string, value: string, allowedValues: Set<string>): string {
  if (!allowedValues.has(value)) throw new Error(`unknown_property_value:${key}`);
  return value;
}

function validateEventId(value: string | undefined): string {
  if (!value || !INSTALL_ID_RE.test(value)) throw new Error("invalid_event_id");
  return value.toLowerCase();
}

function validateInstallId(value: string | undefined): string {
  if (!value || !INSTALL_ID_RE.test(value)) throw new Error("invalid_install_id");
  return value.toLowerCase();
}

function authorize(request: Request, env: Env): Response | undefined {
  if (!env.INGEST_TOKEN) return undefined;

  const expected = `Bearer ${env.INGEST_TOKEN}`;
  if (request.headers.get("authorization") === expected) return undefined;

  return json({ ok: false, error: "unauthorized" }, 401);
}

function maxBatchSize(env: Env): number {
  const parsed = Number(env.MAX_BATCH_SIZE ?? DEFAULT_MAX_BATCH_SIZE);
  return Number.isInteger(parsed) && parsed > 0 ? Math.min(parsed, DEFAULT_MAX_BATCH_SIZE) : DEFAULT_MAX_BATCH_SIZE;
}

function stringProperty(properties: OnboardingProperties, key: string): string | null {
  const value = properties[key];
  return typeof value === "string" ? value : null;
}

function requiredString(value: unknown, key: string): string {
  if (typeof value !== "string" || value.length === 0) throw new Error(`missing_${key}`);
  return value;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: corsHeaders() });
}

function normalizedPathname(pathname: string): string {
  const normalized = pathname.replace(/\/+$/, "");
  return normalized.length > 0 ? normalized : "/";
}

function corsHeaders(): HeadersInit {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "authorization,content-type",
    "cache-control": "no-store",
  };
}
