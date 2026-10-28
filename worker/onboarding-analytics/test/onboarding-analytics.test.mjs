import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";

import worker from "../src/index.ts";

const installId = "00000000-0000-4000-8000-000000000001";

class FakeD1Database {
  preparedSql = "";
  statements = [];

  prepare(sql) {
    this.preparedSql = sql;
    return {
      bind: (...values) => ({ values }),
    };
  }

  async batch(statements) {
    this.statements = statements;
    return statements.map(() => ({ success: true }));
  }
}

async function postEvents(body, env = {}, headers = {}) {
  const db = new FakeD1Database();
  const request = new Request("https://iso-me-onboarding-analytics.example/v1/events", {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });

  const response = await worker.fetch(request, { DB: db, ...env });
  const json = await response.json();
  return { db, response, json };
}

function baseProperties(extra = {}) {
  return {
    appVersion: "1.8.2",
    buildNumber: "204",
    platform: "ios",
    ...extra,
  };
}

test("health check returns service identity", async () => {
  const response = await worker.fetch(new Request("https://example.com/health"), { DB: new FakeD1Database() });
  const json = await response.json();

  assert.equal(response.status, 200);
  assert.deepEqual(json, { ok: true, service: "iso-me-onboarding-analytics" });
});

test("accepts onboarding events and stores coarse columns", async () => {
  const events = [
    ["00000000-0000-4000-8000-000000000101", "onboarding_started", "welcome"],
    ["00000000-0000-4000-8000-000000000102", "onboarding_step_viewed", "features"],
    ["00000000-0000-4000-8000-000000000103", "onboarding_location_authorization_requested", "permissions"],
    ["00000000-0000-4000-8000-000000000104", "onboarding_location_authorization_completed", "permissions"],
    ["00000000-0000-4000-8000-000000000105", "onboarding_tracking_intent_changed", "ready"],
    ["00000000-0000-4000-8000-000000000106", "onboarding_completed", "ready"],
  ].map(([eventId, eventName, onboardingStep]) => ({
    eventId,
    eventName,
    properties: baseProperties({
      onboardingStep,
      authorizationStatus: onboardingStep === "permissions" ? "when_in_use" : undefined,
      authorizationRequestKind: eventName.endsWith("requested") ? "always" : undefined,
      trackingIntent: onboardingStep === "ready" ? "start_immediately" : undefined,
    }),
  }));

  const { db, response, json } = await postEvents({ installId, events });

  assert.equal(response.status, 200);
  assert.deepEqual(json, { ok: true, accepted: events.length });
  assert.match(db.preparedSql, /onboarding_step/);
  assert.match(db.preparedSql, /authorization_status/);
  assert.equal(db.statements.length, events.length);

  const payloadJson = db.statements[2].values.at(-1);
  assert.equal(JSON.parse(payloadJson).properties.onboardingStep, "permissions");
});

test("accepts paywall and purchase events for every lifetime plan", async () => {
  const productIds = [
    "com.bontecou.isome.lifetime.individual",
    "com.bontecou.isome.lifetime",
    "com.bontecou.isome.lifetime.family.upgrade",
  ];

  for (const [index, productId] of productIds.entries()) {
    const eventSuffix = String(201 + index).padStart(12, "0");
    const { db, response, json } = await postEvents({
      installId,
      eventId: `00000000-0000-4000-8000-${eventSuffix}`,
      eventName: "onboarding_purchase_finished",
      properties: baseProperties({
        paywallContext: "settings",
        productId,
        purchaseOutcome: "succeeded",
      }),
    });

    assert.equal(response.status, 200);
    assert.deepEqual(json, { ok: true, accepted: 1 });

    const payload = JSON.parse(db.statements[0].values.at(-1));
    assert.equal(payload.eventName, "onboarding_purchase_finished");
    assert.equal(payload.properties.paywallContext, "settings");
    assert.equal(payload.properties.productId, productId);
  }
});

test("rejects onboardingStep values outside the coarse allowlist", async () => {
  const { response, json } = await postEvents({
    installId,
    eventId: "00000000-0000-4000-8000-000000000301",
    eventName: "onboarding_step_viewed",
    properties: baseProperties({ onboardingStep: "home:/Users/cody/Documents" }),
  });

  assert.equal(response.status, 400);
  assert.equal(json.error, "unknown_property_value:onboardingStep");
});

test("rejects unknown raw location and webhook properties", async () => {
  for (const properties of [
    baseProperties({ latitude: 37.7749 }),
    baseProperties({ longitude: -122.4194 }),
    baseProperties({ address: "1 Market St" }),
    baseProperties({ webhookURL: "https://example.com/hook" }),
  ]) {
    const { response, json } = await postEvents({
      installId,
      eventId: "00000000-0000-4000-8000-000000000401",
      eventName: "onboarding_step_viewed",
      properties,
    });

    assert.equal(response.status, 400);
    assert.match(json.error, /^unknown_property:/);
  }
});

test("rejects events without valid install and event UUIDs", async () => {
  const invalidEventId = await postEvents({
    installId,
    eventId: "not-a-uuid",
    eventName: "onboarding_started",
    properties: baseProperties({ onboardingStep: "welcome" }),
  });
  assert.equal(invalidEventId.response.status, 400);
  assert.equal(invalidEventId.json.error, "invalid_event_id");

  const invalidInstallId = await postEvents({
    installId: "not-a-uuid",
    eventId: "00000000-0000-4000-8000-000000000501",
    eventName: "onboarding_started",
    properties: baseProperties({ onboardingStep: "welcome" }),
  });
  assert.equal(invalidInstallId.response.status, 400);
  assert.equal(invalidInstallId.json.error, "invalid_install_id");
});

test("enforces optional bearer token", async () => {
  const unauthorized = await postEvents({
    installId,
    eventId: "00000000-0000-4000-8000-000000000601",
    eventName: "onboarding_started",
    properties: baseProperties({ onboardingStep: "welcome" }),
  }, { INGEST_TOKEN: "secret" });
  assert.equal(unauthorized.response.status, 401);
  assert.equal(unauthorized.json.error, "unauthorized");

  const authorized = await postEvents({
    installId,
    eventId: "00000000-0000-4000-8000-000000000602",
    eventName: "onboarding_started",
    properties: baseProperties({ onboardingStep: "welcome" }),
  }, { INGEST_TOKEN: "secret" }, { authorization: "Bearer secret" });
  assert.equal(authorized.response.status, 200);
  assert.equal(authorized.json.accepted, 1);
});

test("enforces max batch size", async () => {
  const events = Array.from({ length: 3 }, (_, index) => ({
    eventId: `00000000-0000-4000-8000-00000000070${index}`,
    eventName: "onboarding_step_viewed",
    properties: baseProperties({ onboardingStep: "welcome" }),
  }));

  const { response, json } = await postEvents({ installId, events }, { MAX_BATCH_SIZE: "2" });

  assert.equal(response.status, 400);
  assert.equal(json.error, "batch_too_large");
});

test("accepts Android consent-scoped onboarding and individual purchase properties", async () => {
  const { db, response } = await postEvents({ installId, events: [{
    eventId: "00000000-0000-4000-8000-000000000901",
    eventName: "onboarding_purchase_finished",
    properties: baseProperties({platform: "android", onboardingStep: "notifications", authorizationRequestKind: "notifications", productId: "whereday_export_lifetime", purchaseOutcome: "pending"}),
  }] });
  assert.equal(response.status, 200);
  const payload = JSON.parse(db.statements[0].values.at(-1));
  assert.equal(payload.properties.platform, "android");
  assert.equal(payload.properties.productId, "whereday_export_lifetime");
});

test("rejects cross-platform products and Android-only properties without Android identity", async () => {
  for (const properties of [
    baseProperties({platform: "android", productId: "com.bontecou.isome.lifetime.individual"}),
    baseProperties({productId: "whereday_export_lifetime"}),
    baseProperties({onboardingStep: "notifications"}),
    baseProperties({authorizationRequestKind: "notifications"}),
  ]) {
    const { db, response } = await postEvents({installId,eventId:"00000000-0000-4000-8000-000000000902",eventName:"onboarding_step_viewed",properties});
    assert.equal(response.status,400);assert.equal(db.statements.length,0);
  }
});

test("Android properties still reject raw identifiers, coordinates and user text", async () => {
  for (const extra of [{deviceModel:"QA phone"},{latitude:1},{visitName:"Private place"},{webhookURL:"https://example.invalid"},{advertisingId:installId}]) {
    const { db, response } = await postEvents({installId,eventId:"00000000-0000-4000-8000-000000000903",eventName:"onboarding_step_viewed",properties:baseProperties({platform:"android",...extra})});
    assert.equal(response.status,400);assert.equal(db.statements.length,0);
  }
});

test("oversized chunked bodies are cancelled before full materialization", async () => {
  let cancelled=false;
  const stream=new ReadableStream({pull(controller) {controller.enqueue(new Uint8Array(8192));},cancel() {cancelled=true;}});
  const db=new FakeD1Database();
  const response=await worker.fetch(new Request("https://example.com/v1/events",{method:"POST",body:stream,duplex:"half"}),{DB:db});
  assert.equal(response.status,413);assert.equal(cancelled,true);assert.equal(db.statements.length,0);
});


test("accepts the exact fixture checked by the production Android encoder", async () => {
  for (const filename of ["usage-analytics-android.json","usage-analytics-android-notification.json"]) {
    const body=JSON.parse(readFileSync(new URL(`../../../android/platform/src/test/resources/${filename}`,import.meta.url),"utf8"));
    const { db,response,json }=await postEvents(body);
    assert.equal(response.status,200);assert.equal(json.accepted,1);
    assert.deepEqual(JSON.parse(db.statements[0].values.at(-1)).properties,body.events[0].properties);
  }
});


test("notification authorization is accepted only for explicit Android notification requests", async () => {
  for (const [properties, status] of [
    [baseProperties({platform:"android",authorizationRequestKind:"notifications",authorizationStatus:"authorized"}),200],
    [baseProperties({platform:"android",authorizationStatus:"authorized"}),400],
    [baseProperties({authorizationStatus:"authorized"}),400],
    [baseProperties({platform:"android",authorizationRequestKind:"when_in_use",authorizationStatus:"authorized"}),400],
  ]) {
    const {response}=await postEvents({installId,eventId:"00000000-0000-4000-8000-000000000905",eventName:"onboarding_location_authorization_completed",properties});
    assert.equal(response.status,status);
  }
});
