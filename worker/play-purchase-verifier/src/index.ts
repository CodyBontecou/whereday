// Whereday Play purchase verifier: turns a Google Play purchase into a short, device-bound signed lease.

import { accessToken, decodeIntegrityToken, integrityProblem, lifetimeStatus, subscriptionPurchase, type PurchaseStatus, type ServiceAccount } from "./play";
import { base64UrlEncode, importPrivateKey, leaseClaims, ProtocolError, requestHash, sha256Hex, signLease, verificationMessage, verifyDeviceSignature } from "./protocol";

export interface Env {
  DB: D1Database;
  PACKAGE_NAME: string;
  LIFETIME_PRODUCT_ID: string;
  SUBSCRIPTION_PRODUCT_ID: string;
  /** Play Console service-account JSON with Android Publisher and Play Integrity access. */
  GOOGLE_SERVICE_ACCOUNT: string;
  /** PKCS#8 PEM; its public half is the app's whereday.purchaseVerifierPublicKey. */
  LEASE_PRIVATE_KEY: string;
}

const CHALLENGE_SECONDS = 300;
/** One Google account's purchase may unlock this many phones before new ones are refused. */
export const MAX_DEVICES_PER_PURCHASE = 5;

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

export class RequestError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

function field(body: Record<string, unknown>, name: string, max = 4096): string {
  const value = body[name];
  if (typeof value !== "string" || value.length === 0 || value.length > max) throw new RequestError(400, `Invalid ${name}`);
  return value;
}

async function readBody(request: Request): Promise<Record<string, unknown>> {
  const text = await request.text();
  if (text.length > 64 * 1024) throw new RequestError(413, "Request too large");
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed as Record<string, unknown>;
  } catch {}
  throw new RequestError(400, "Invalid JSON");
}

async function keyHash(publicKey: string): Promise<string> {
  try { return await sha256Hex(Uint8Array.from(atob(publicKey), (char) => char.charCodeAt(0))); }
  catch { throw new RequestError(400, "Invalid publicKey"); }
}

async function challenge(body: Record<string, unknown>, env: Env, now: number): Promise<Response> {
  if (field(body, "packageName", 200) !== env.PACKAGE_NAME) throw new RequestError(400, "Unknown package");
  const hash = await keyHash(field(body, "publicKey"));
  const id = crypto.randomUUID();
  const nonce = base64UrlEncode(crypto.getRandomValues(new Uint8Array(32)));
  await env.DB.batch([
    env.DB.prepare("DELETE FROM challenges WHERE expires_at < ?").bind(now),
    env.DB.prepare("INSERT INTO challenges (id, nonce, key_hash, package_name, expires_at) VALUES (?, ?, ?, ?, ?)").bind(id, nonce, hash, env.PACKAGE_NAME, now + CHALLENGE_SECONDS),
  ]);
  return json(200, { challengeId: id, nonce });
}

export interface Dependencies {
  fetcher: typeof fetch;
  now: () => number;
}

async function verify(body: Record<string, unknown>, env: Env, deps: Dependencies): Promise<Response> {
  const now = Math.floor(deps.now() / 1000);
  const packageName = field(body, "packageName", 200);
  const productId = field(body, "productId", 200);
  const publicKey = field(body, "publicKey");
  const purchaseToken = field(body, "purchaseToken");
  const challengeId = field(body, "challengeId", 64);
  if (packageName !== env.PACKAGE_NAME) throw new RequestError(400, "Unknown package");
  if (productId !== env.LIFETIME_PRODUCT_ID && productId !== env.SUBSCRIPTION_PRODUCT_ID) throw new RequestError(400, "Unknown product");
  const publicKeyHash = await keyHash(publicKey);
  // Each challenge is consumed by its first verification attempt, successful or not.
  const stored = await env.DB.prepare("DELETE FROM challenges WHERE id = ? RETURNING nonce, key_hash, package_name, expires_at").bind(challengeId)
    .first<{ nonce: string; key_hash: string; package_name: string; expires_at: number }>();
  if (!stored || stored.expires_at < now || stored.key_hash !== publicKeyHash || stored.package_name !== packageName) throw new RequestError(403, "Challenge expired or unknown");
  const purchaseTokenHash = await sha256Hex(new TextEncoder().encode(purchaseToken));
  const message = verificationMessage({ challengeId, nonce: stored.nonce, packageName, productId, purchaseTokenHash, publicKeyHash });
  if (!(await verifyDeviceSignature(publicKey, message, field(body, "signature")))) throw new RequestError(403, "Invalid device signature");

  const google = await accessToken(JSON.parse(env.GOOGLE_SERVICE_ACCOUNT) as ServiceAccount, deps.fetcher, deps.now());
  const verdict = await decodeIntegrityToken(google, packageName, field(body, "integrityToken", 16_384), deps.fetcher);
  const problem = integrityProblem(verdict, packageName, await requestHash(message), deps.now());
  if (problem) throw new RequestError(403, `Integrity check failed: ${problem}`);

  const status: PurchaseStatus = productId === env.SUBSCRIPTION_PRODUCT_ID
    ? await subscriptionPurchase(google, packageName, productId, purchaseToken, deps.fetcher, deps.now())
    : await lifetimeStatus(google, packageName, productId, purchaseToken, deps.fetcher);
  if (status.status !== "active") return json(200, { status: status.status });

  await env.DB.prepare("INSERT OR IGNORE INTO purchase_devices (token_hash, key_hash, first_seen) VALUES (?, ?, ?)").bind(purchaseTokenHash, publicKeyHash, now).run();
  const allowed = await env.DB.prepare("SELECT key_hash FROM purchase_devices WHERE token_hash = ? ORDER BY first_seen, rowid LIMIT ?").bind(purchaseTokenHash, MAX_DEVICES_PER_PURCHASE)
    .all<{ key_hash: string }>();
  if (!allowed.results.some((row) => row.key_hash === publicKeyHash)) throw new RequestError(403, "Device limit reached for this purchase");

  const claims = leaseClaims({ packageName, deviceKeyHash: publicKeyHash, productId, purchaseTokenHash, now, accessEnds: status.accessEnds, id: crypto.randomUUID() });
  if (!claims) return json(200, { status: "revoked" });
  return json(200, { status: "active", lease: await signLease(claims, await importPrivateKey(env.LEASE_PRIVATE_KEY, "sign")) });
}

export async function handle(request: Request, env: Env, deps: Dependencies = { fetcher: fetch, now: Date.now }): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/health") return json(200, { ok: true });
  if (request.method !== "POST") return json(405, { error: "Method not allowed" });
  try {
    const body = await readBody(request);
    if (url.pathname === "/v1/challenges") return await challenge(body, env, Math.floor(deps.now() / 1000));
    if (url.pathname === "/v1/verify") return await verify(body, env, deps);
    return json(404, { error: "Not found" });
  } catch (error) {
    if (error instanceof RequestError) return json(error.status, { error: error.message });
    if (error instanceof ProtocolError) return json(400, { error: error.message });
    // Never echo upstream detail; the app shows "verification unavailable" for any non-200 answer.
    console.error("verification failed", error instanceof Error ? error.message : "unknown");
    return json(502, { error: "Verification unavailable" });
  }
}

export default { fetch: (request: Request, env: Env) => handle(request, env) } satisfies ExportedHandler<Env>;
