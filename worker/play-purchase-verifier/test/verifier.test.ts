import { beforeAll, describe, expect, it } from "vitest";
import { handle, MAX_DEVICES_PER_PURCHASE, type Env } from "../src/index";
import { subscriptionStatus } from "../src/play";
import { base64Decode, base64UrlEncode, leaseClaims, requestHash, sha256Hex, verificationMessage } from "../src/protocol";

const PACKAGE = "com.bontecou.whereday";
const NOW = Date.parse("2026-11-03T12:00:00Z");

/** Enough of D1 for the verifier's statements, keyed on their SQL. */
class FakeD1 {
  challenges = new Map<string, { nonce: string; key_hash: string; package_name: string; expires_at: number }>();
  devices: { token_hash: string; key_hash: string; first_seen: number }[] = [];
  prepare(sql: string) {
    const db = this;
    let args: unknown[] = [];
    const statement = {
      bind(...values: unknown[]) { args = values; return statement; },
      async run() {
        if (sql.startsWith("DELETE FROM challenges WHERE expires_at")) for (const [id, row] of db.challenges) { if (row.expires_at < (args[0] as number)) db.challenges.delete(id); }
        else if (sql.startsWith("INSERT INTO challenges")) db.challenges.set(args[0] as string, { nonce: args[1] as string, key_hash: args[2] as string, package_name: args[3] as string, expires_at: args[4] as number });
        else if (sql.startsWith("INSERT OR IGNORE INTO purchase_devices")) {
          if (!db.devices.some((row) => row.token_hash === args[0] && row.key_hash === args[1])) db.devices.push({ token_hash: args[0] as string, key_hash: args[1] as string, first_seen: args[2] as number });
        } else throw new Error(`Unexpected run: ${sql}`);
        return { success: true };
      },
      async first<T>() {
        if (!sql.startsWith("DELETE FROM challenges WHERE id")) throw new Error(`Unexpected first: ${sql}`);
        const row = db.challenges.get(args[0] as string) ?? null;
        db.challenges.delete(args[0] as string);
        return row as T;
      },
      async all<T>() {
        const rows = db.devices.filter((row) => row.token_hash === args[0]).sort((a, b) => a.first_seen - b.first_seen).slice(0, args[1] as number);
        return { results: rows as T[] };
      },
    };
    return statement;
  }
  async batch(statements: { run(): Promise<unknown> }[]) { for (const statement of statements) await statement.run(); return []; }
}

async function rsa() {
  return crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]) as Promise<CryptoKeyPair>;
}
const standardBase64 = (bytes: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
async function pem(key: CryptoKey) {
  return `-----BEGIN PRIVATE KEY-----\n${standardBase64(await crypto.subtle.exportKey("pkcs8", key))}\n-----END PRIVATE KEY-----\n`;
}

let leaseKeys: CryptoKeyPair;
let env: Env;
let db: FakeD1;
beforeAll(async () => {
  leaseKeys = await rsa();
  const account = await rsa();
  db = new FakeD1();
  env = {
    DB: db as unknown as D1Database,
    PACKAGE_NAME: PACKAGE,
    LIFETIME_PRODUCT_ID: "whereday_export_lifetime",
    SUBSCRIPTION_PRODUCT_ID: "whereday_individual",
    GOOGLE_SERVICE_ACCOUNT: JSON.stringify({ client_email: "verifier@example.iam.gserviceaccount.com", private_key: await pem(account.privateKey) }),
    LEASE_PRIVATE_KEY: await pem(leaseKeys.privateKey),
  };
});

interface Play {
  product?: { purchaseState: number; acknowledgementState: number } | null;
  subscription?: Record<string, unknown> | null;
  device?: string[];
  app?: string;
}

/** Google's token, Integrity and Publisher endpoints, recording acknowledgements. */
function google(play: Play, acknowledged: string[]) {
  let attestedHash = "";
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "https://oauth2.googleapis.com/token") return Response.json({ access_token: "access", expires_in: 3600 });
    if (url.includes(":decodeIntegrityToken")) {
      attestedHash = JSON.parse(String(init?.body)).integrity_token;
      return Response.json({ tokenPayloadExternal: {
        requestDetails: { requestPackageName: PACKAGE, requestHash: attestedHash, timestampMillis: String(NOW - 1000) },
        appIntegrity: { appRecognitionVerdict: play.app ?? "PLAY_RECOGNIZED", packageName: PACKAGE },
        deviceIntegrity: { deviceRecognitionVerdict: play.device ?? ["MEETS_DEVICE_INTEGRITY"] },
      } });
    }
    if (url.endsWith(":acknowledge")) { acknowledged.push(url); return new Response("", { status: 200 }); }
    if (url.includes("/purchases/products/")) return play.product ? Response.json(play.product) : new Response("", { status: 404 });
    if (url.includes("/purchases/subscriptionsv2/")) return play.subscription ? Response.json(play.subscription) : new Response("", { status: 404 });
    throw new Error(`Unexpected fetch ${url}`);
  }) as typeof fetch;
  return { fetcher, now: () => NOW };
}

const post = (path: string, body: unknown) => new Request(`https://verifier.example${path}`, { method: "POST", body: JSON.stringify(body) });

/** What RemotePurchaseVerifier does: ask for a challenge, sign the message, attest its hash (here the fake echoes it), then verify. */
async function verify(play: Play, options: { productId?: string; token?: string; device?: CryptoKeyPair; tamper?: boolean; replay?: boolean } = {}) {
  const device = options.device ?? (await rsa());
  const publicKey = standardBase64(await crypto.subtle.exportKey("spki", device.publicKey));
  const acknowledged: string[] = [];
  const deps = google(play, acknowledged);
  const challenge = (await (await handle(post("/v1/challenges", { publicKey, packageName: PACKAGE }), env, deps)).json()) as { challengeId: string; nonce: string };
  const productId = options.productId ?? env.LIFETIME_PRODUCT_ID;
  const token = options.token ?? "purchase-token";
  const message = verificationMessage({ challengeId: challenge.challengeId, nonce: challenge.nonce, packageName: PACKAGE, productId,
    purchaseTokenHash: await sha256Hex(new TextEncoder().encode(token)), publicKeyHash: await sha256Hex(base64Decode(publicKey)) });
  const signature = base64UrlEncode(new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", device.privateKey, options.tamper ? new TextEncoder().encode("other") : message)));
  const body = { challengeId: challenge.challengeId, publicKey, packageName: PACKAGE, productId, purchaseToken: token, signature, integrityToken: await requestHash(message) };
  const response = await handle(post("/v1/verify", body), env, deps);
  const replay = options.replay ? await handle(post("/v1/verify", body), env, deps) : null;
  return { response, json: (await response.json()) as { status?: string; lease?: string; error?: string }, acknowledged, replay, publicKey };
}

async function claims(lease: string) {
  const [header, body, signature] = lease.split(".");
  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", leaseKeys.publicKey, base64Decode(signature), new TextEncoder().encode(`${header}.${body}`));
  expect(valid).toBe(true);
  return JSON.parse(new TextDecoder().decode(base64Decode(body)));
}

describe("verification message", () => {
  it("matches the Android client's seven-line format", () => {
    const text = new TextDecoder().decode(verificationMessage({ challengeId: "challenge", nonce: "nonce", packageName: "example.app", productId: "lifetime", purchaseTokenHash: "hash", publicKeyHash: "key-hash" }));
    expect(text).toBe("whereday-purchase-v1\nchallenge\nnonce\nexample.app\nlifetime\nhash\nkey-hash");
    expect(() => verificationMessage({ challengeId: "bad\nchallenge", nonce: "n", packageName: "a", productId: "p", purchaseTokenHash: "h", publicKeyHash: "k" })).toThrow();
  });
  it("never issues a lease past seven days or past paid-through access", () => {
    const now = 1_000_000;
    expect(leaseClaims({ packageName: "a", deviceKeyHash: "d", productId: "p", purchaseTokenHash: "h", now, id: "x" })!.exp).toBe(now + 7 * 86400);
    const short = leaseClaims({ packageName: "a", deviceKeyHash: "d", productId: "p", purchaseTokenHash: "h", now, accessEnds: now + 3600, id: "x" })!;
    expect([short.exp, short.refresh_after]).toEqual([now + 3600, now + 3600]);
    expect(leaseClaims({ packageName: "a", deviceKeyHash: "d", productId: "p", purchaseTokenHash: "h", now, accessEnds: now, id: "x" })).toBeNull();
  });
});

describe("subscription states", () => {
  const item = (expiry: number) => ({ lineItems: [{ productId: "whereday_individual", expiryTime: new Date(expiry).toISOString() }] });
  it("keeps access through grace and paid-through cancellation, and not after", () => {
    for (const state of ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"])
      expect(subscriptionStatus({ subscriptionState: state, ...item(NOW + 86400_000) }, "whereday_individual", NOW)).toEqual({ status: "active", accessEnds: (NOW + 86400_000) / 1000 });
    expect(subscriptionStatus({ subscriptionState: "SUBSCRIPTION_STATE_CANCELED", ...item(NOW - 1000) }, "whereday_individual", NOW).status).toBe("revoked");
    for (const state of ["SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PAUSED", "SUBSCRIPTION_STATE_EXPIRED"])
      expect(subscriptionStatus({ subscriptionState: state, ...item(NOW + 86400_000) }, "whereday_individual", NOW).status).toBe("revoked");
    expect(subscriptionStatus({ subscriptionState: "SUBSCRIPTION_STATE_PENDING", ...item(NOW) }, "whereday_individual", NOW).status).toBe("pending");
    expect(subscriptionStatus({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", ...item(NOW + 1000) }, "other_product", NOW).status).toBe("revoked");
  });
});

describe("verify", () => {
  it("signs a device-bound Lifetime lease and acknowledges the purchase", async () => {
    const result = await verify({ product: { purchaseState: 0, acknowledgementState: 0 } }, { replay: true });
    expect(result.json.status).toBe("active");
    const lease = await claims(result.json.lease!);
    expect(lease.aud).toBe(PACKAGE);
    expect(lease.product_id).toBe("whereday_export_lifetime");
    expect(lease.sub).toBe(await sha256Hex(base64Decode(result.publicKey)));
    expect(lease.purchase_token_hash).toBe(await sha256Hex(new TextEncoder().encode("purchase-token")));
    expect(lease.exp - lease.iat).toBe(7 * 86400);
    expect(result.acknowledged).toHaveLength(1);
    // A challenge cannot be used twice.
    expect(result.replay!.status).toBe(403);
  });
  it("limits a subscription lease to its paid-through time and acknowledges it once", async () => {
    const ends = NOW + 2 * 86400_000;
    const result = await verify({ subscription: { subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
      lineItems: [{ productId: "whereday_individual", expiryTime: new Date(ends).toISOString() }] } }, { productId: "whereday_individual", token: "subscription-token" });
    const lease = await claims(result.json.lease!);
    expect([lease.product_id, lease.exp]).toEqual(["whereday_individual", ends / 1000]);
    expect(result.acknowledged[0]).toContain("/purchases/subscriptions/whereday_individual/tokens/subscription-token:acknowledge");
  });
  it("reports pending and revoked purchases without a lease", async () => {
    expect((await verify({ product: { purchaseState: 2, acknowledgementState: 0 } })).json).toEqual({ status: "pending" });
    expect((await verify({ product: null })).json).toEqual({ status: "revoked" });
    expect((await verify({ subscription: { subscriptionState: "SUBSCRIPTION_STATE_EXPIRED", lineItems: [{ productId: "whereday_individual", expiryTime: new Date(NOW - 1).toISOString() }] } },
      { productId: "whereday_individual" })).json).toEqual({ status: "revoked" });
  });
  it("refuses forged signatures, unrecognized apps and failing devices", async () => {
    const owned = { product: { purchaseState: 0, acknowledgementState: 1 } };
    expect((await verify(owned, { tamper: true })).response.status).toBe(403);
    expect((await verify({ ...owned, app: "UNRECOGNIZED_VERSION" })).response.status).toBe(403);
    expect((await verify({ ...owned, device: [] })).response.status).toBe(403);
    expect((await verify(owned, { productId: "something_else" })).response.status).toBe(400);
  });
  it("stops one purchase from unlocking unlimited phones", async () => {
    const owned = { product: { purchaseState: 0, acknowledgementState: 1 } };
    const first = await rsa();
    for (let index = 0; index < MAX_DEVICES_PER_PURCHASE; index++)
      expect((await verify(owned, { token: "shared-token", device: index === 0 ? first : undefined })).json.status).toBe("active");
    expect((await verify(owned, { token: "shared-token" })).response.status).toBe(403);
    // A phone that already has access keeps it.
    expect((await verify(owned, { token: "shared-token", device: first })).json.status).toBe("active");
  });
});
