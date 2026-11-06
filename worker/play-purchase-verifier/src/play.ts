// Google Play Developer API and Play Integrity, called with the Play Console service account.

import { base64UrlEncode, importPrivateKey } from "./protocol";

const SCOPES = "https://www.googleapis.com/auth/androidpublisher https://www.googleapis.com/auth/playintegrity";
const encoder = new TextEncoder();

export interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

let cached: { token: string; expires: number; email: string } | null = null;

/** An OAuth access token from a signed service-account assertion, reused until a minute before it expires. */
export async function accessToken(account: ServiceAccount, fetcher: typeof fetch = fetch, now = Date.now()): Promise<string> {
  if (cached && cached.email === account.client_email && cached.expires - 60_000 > now) return cached.token;
  const audience = account.token_uri ?? "https://oauth2.googleapis.com/token";
  const issued = Math.floor(now / 1000);
  const header = base64UrlEncode(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = base64UrlEncode(encoder.encode(JSON.stringify({ iss: account.client_email, scope: SCOPES, aud: audience, iat: issued, exp: issued + 3600 })));
  const key = await importPrivateKey(account.private_key, "sign");
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(`${header}.${claims}`)));
  const response = await fetcher(audience, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${header}.${claims}.${base64UrlEncode(signature)}` }),
  });
  if (!response.ok) throw new UpstreamError(`Google token request failed: ${response.status}`);
  const body = (await response.json()) as { access_token: string; expires_in: number };
  cached = { token: body.access_token, expires: now + body.expires_in * 1000, email: account.client_email };
  return body.access_token;
}

export class UpstreamError extends Error {}

async function google<T>(token: string, url: string, fetcher: typeof fetch, init: RequestInit = {}): Promise<{ status: number; body: T | null }> {
  const response = await fetcher(url, { ...init, headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" } });
  if (response.status === 404 || response.status === 410) return { status: response.status, body: null };
  if (!response.ok) throw new UpstreamError(`Google API ${response.status}`);
  const text = await response.text();
  return { status: response.status, body: text ? (JSON.parse(text) as T) : null };
}

export interface IntegrityVerdict {
  requestDetails?: { requestPackageName?: string; requestHash?: string; timestampMillis?: string };
  appIntegrity?: { appRecognitionVerdict?: string; packageName?: string };
  deviceIntegrity?: { deviceRecognitionVerdict?: string[] };
  accountDetails?: { appLicensingVerdict?: string };
}

export async function decodeIntegrityToken(token: string, packageName: string, integrityToken: string, fetcher: typeof fetch = fetch): Promise<IntegrityVerdict> {
  const { body } = await google<{ tokenPayloadExternal: IntegrityVerdict }>(token,
    `https://playintegrity.googleapis.com/v1/${encodeURIComponent(packageName)}:decodeIntegrityToken`, fetcher,
    { method: "POST", body: JSON.stringify({ integrity_token: integrityToken }) });
  if (!body) throw new UpstreamError("Integrity token could not be decoded");
  return body.tokenPayloadExternal;
}

/** The app is the Play-signed build, on a device that passes integrity, asking about this exact message within five minutes. */
export function integrityProblem(verdict: IntegrityVerdict, packageName: string, expectedHash: string, now = Date.now()): string | null {
  const request = verdict.requestDetails;
  if (request?.requestPackageName !== packageName || request.requestHash !== expectedHash) return "request";
  const at = Number(request.timestampMillis);
  if (!Number.isFinite(at) || Math.abs(now - at) > 5 * 60_000) return "stale";
  if (verdict.appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED" || verdict.appIntegrity.packageName !== packageName) return "app";
  if (!verdict.deviceIntegrity?.deviceRecognitionVerdict?.includes("MEETS_DEVICE_INTEGRITY")) return "device";
  return null;
}

export type PurchaseStatus = { status: "active"; accessEnds?: number } | { status: "pending" } | { status: "revoked" };

interface ProductPurchase {
  purchaseState?: number; // 0 purchased, 1 canceled, 2 pending
  acknowledgementState?: number;
  productId?: string;
}

/** A one-time product. Purchased products are acknowledged here, because Play refunds unacknowledged purchases after three days. */
export async function lifetimeStatus(token: string, packageName: string, productId: string, purchaseToken: string, fetcher: typeof fetch = fetch): Promise<PurchaseStatus> {
  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}`;
  const { body } = await google<ProductPurchase>(token, base, fetcher);
  const status = productStatus(body);
  if (status.status === "active" && body?.acknowledgementState === 0) await google(token, `${base}:acknowledge`, fetcher, { method: "POST", body: "{}" });
  return status;
}

export function productStatus(purchase: ProductPurchase | null): PurchaseStatus {
  if (!purchase) return { status: "revoked" };
  if (purchase.purchaseState === 0) return { status: "active" };
  if (purchase.purchaseState === 2) return { status: "pending" };
  return { status: "revoked" };
}

export interface SubscriptionPurchaseV2 {
  subscriptionState?: string;
  acknowledgementState?: string;
  lineItems?: { productId?: string; expiryTime?: string }[];
}

/** Paid-through access for a subscription. Grace period keeps access; on hold, paused and expired do not. */
export function subscriptionStatus(purchase: SubscriptionPurchaseV2 | null, productId: string, now = Date.now()): PurchaseStatus {
  if (!purchase) return { status: "revoked" };
  const item = purchase.lineItems?.find((line) => line.productId === productId);
  if (!item) return { status: "revoked" };
  switch (purchase.subscriptionState) {
    case "SUBSCRIPTION_STATE_PENDING":
      return { status: "pending" };
    case "SUBSCRIPTION_STATE_ACTIVE":
    case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD":
    case "SUBSCRIPTION_STATE_CANCELED": {
      // A canceled subscription keeps access until the end of the period already paid for.
      const ends = Date.parse(item.expiryTime ?? "");
      if (!Number.isFinite(ends) || ends <= now) return { status: "revoked" };
      return { status: "active", accessEnds: Math.floor(ends / 1000) };
    }
    default:
      return { status: "revoked" };
  }
}

export async function subscriptionPurchase(token: string, packageName: string, productId: string, purchaseToken: string, fetcher: typeof fetch = fetch, now = Date.now()): Promise<PurchaseStatus> {
  const app = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases`;
  const { body } = await google<SubscriptionPurchaseV2>(token, `${app}/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`, fetcher);
  const status = subscriptionStatus(body, productId, now);
  if (status.status === "active" && body?.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING")
    await google(token, `${app}/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`, fetcher, { method: "POST", body: "{}" });
  return status;
}
