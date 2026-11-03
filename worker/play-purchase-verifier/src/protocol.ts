// Shared with android/billing Entitlement.kt and RemotePurchaseVerifier.kt. Change both sides together.

const encoder = new TextEncoder();
/** Bytes backed by a plain ArrayBuffer, as WebCrypto requires. */
export type Bytes = Uint8Array<ArrayBuffer>;

export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64Decode(text: string): Bytes {
  const normal = text.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normal + "=".repeat((4 - (normal.length % 4)) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

export async function sha256(bytes: Bytes): Promise<Bytes> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

export async function sha256Hex(bytes: Bytes): Promise<string> {
  return [...(await sha256(bytes))].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** The exact bytes the device signs and Play Integrity attests. No raw purchase token appears in it. */
export function verificationMessage(parts: {
  challengeId: string;
  nonce: string;
  packageName: string;
  productId: string;
  purchaseTokenHash: string;
  publicKeyHash: string;
}): Bytes {
  const values = ["whereday-purchase-v1", parts.challengeId, parts.nonce, parts.packageName, parts.productId, parts.purchaseTokenHash, parts.publicKeyHash];
  if (!values.every((value) => value.length > 0 && !value.includes("\n") && !value.includes("\r"))) throw new ProtocolError("Invalid message field");
  return encoder.encode(values.join("\n"));
}

/** The requestHash the app passes to Play Integrity: base64url SHA-256 of the message, without padding. */
export async function requestHash(message: Bytes): Promise<string> {
  return base64UrlEncode(await sha256(message));
}

/** Verifies the device's RSASSA-PKCS1-v1_5 SHA-256 signature with its Android Keystore public key (X.509 SPKI, base64). */
export async function verifyDeviceSignature(publicKeySpki: string, message: Bytes, signature: string): Promise<boolean> {
  const key = await crypto.subtle.importKey("spki", base64Decode(publicKeySpki), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  if (((key.algorithm as { modulusLength?: number }).modulusLength ?? 0) < 2048) return false;
  return crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, base64Decode(signature), message);
}

export class ProtocolError extends Error {}

export const MAX_LEASE_SECONDS = 7 * 86400;
export const REFRESH_SECONDS = 86400;

export interface LeaseClaims {
  aud: string;
  sub: string;
  product_id: string;
  jti: string;
  purchase_token_hash: string;
  iat: number;
  refresh_after: number;
  exp: number;
}

/**
 * A lease never outlives seven days, the client's own limit, nor a subscription's paid-through time.
 * Lifetime leases simply renew weekly. Returns null when an expiry has already passed.
 */
export function leaseClaims(input: {
  packageName: string;
  deviceKeyHash: string;
  productId: string;
  purchaseTokenHash: string;
  now: number;
  accessEnds?: number;
  id: string;
}): LeaseClaims | null {
  const exp = Math.min(input.now + MAX_LEASE_SECONDS, input.accessEnds ?? Number.MAX_SAFE_INTEGER);
  if (exp <= input.now) return null;
  return {
    aud: input.packageName,
    sub: input.deviceKeyHash,
    product_id: input.productId,
    jti: input.id,
    purchase_token_hash: input.purchaseTokenHash,
    iat: input.now,
    refresh_after: Math.min(input.now + REFRESH_SECONDS, exp),
    exp,
  };
}

/** A compact RS256 JWT, verified on the device by LeaseVerifier with the published public key. */
export async function signLease(claims: LeaseClaims, privateKey: CryptoKey): Promise<string> {
  const header = base64UrlEncode(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const body = base64UrlEncode(encoder.encode(JSON.stringify(claims)));
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privateKey, encoder.encode(`${header}.${body}`));
  return `${header}.${body}.${base64UrlEncode(new Uint8Array(signature))}`;
}

export async function importPrivateKey(pem: string, usage: KeyUsage): Promise<CryptoKey> {
  const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  return crypto.subtle.importKey("pkcs8", base64Decode(body), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, [usage]);
}
