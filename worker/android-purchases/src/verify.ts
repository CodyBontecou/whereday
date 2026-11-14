import {base64url,bytes,decode64,hash,message,object,publicKey,purchaseStatus,signJwt,text,validateIntegrity} from './protocol';
import {PurchaseRevoked,type Ledger} from './ledger';

export type Configuration={packageName:string,productId:string,certificates:string[],minimumVersion:number};
export interface PlayGateway {
  integrity(token:string):Promise<unknown>;
  purchase(productId:string,token:string):Promise<unknown>;
  acknowledge(productId:string,token:string):Promise<void>;
}
export type Dependencies={ledger:Ledger,google:()=>Promise<PlayGateway>,signingKey:()=>Promise<CryptoKey>,now:()=>number};
export type VerificationResult={status:'active',lease:string}|{status:'pending'|'revoked'};

export async function verifyPurchase(input:unknown,configuration:Configuration,deps:Dependencies):Promise<VerificationResult> {
  const body=object(input),now=deps.now();
  const challengeId=text(body.challengeId,64),packageName=text(body.packageName,200),productId=text(body.productId,200);
  if(packageName!==configuration.packageName || productId!==configuration.productId) throw new Error('wrong_product');
  const token=text(body.purchaseToken),attestation=text(body.integrityToken,100_000);
  const device=await publicKey(text(body.publicKey,2048));
  const challenge=await deps.ledger.challenge(challengeId);
  if(!challenge || challenge.device_hash!==device.hash || challenge.consumed_at!==null || challenge.expires_at<=now) throw new Error('challenge_rejected');
  const tokenHash=await hash(bytes(token));
  const canonical=message(challengeId,challenge.nonce,packageName,productId,tokenHash,device.hash);
  if(!await crypto.subtle.verify('RSASSA-PKCS1-v1_5',device.key,decode64(text(body.signature,2048)),canonical)) throw new Error('signature_rejected');
  if(!await deps.ledger.consume(challengeId,device.hash,now)) throw new Error('challenge_rejected');

  const google=await deps.google();
  const verdict=await google.integrity(attestation);
  validateIntegrity(verdict,{...configuration,requestHash:base64url(await crypto.subtle.digest('SHA-256',canonical)),now});
  const purchase=purchaseStatus(await google.purchase(productId,token),productId);
  if(purchase.status==='revoked') {await deps.ledger.revoke(tokenHash,now);return {status:'revoked'};}
  if(purchase.status==='pending') return {status:'pending'};

  // Durable grant before acknowledgement. No consumption API exists in this service.
  let grantId:string;
  try {grantId=await deps.ledger.grant(tokenHash,productId,now);}
  catch(error) {if(error instanceof PurchaseRevoked) return {status:'revoked'};throw error;}
  if(!purchase.acknowledged) {
    try {await google.acknowledge(productId,token);}
    catch(error) {if(await deps.ledger.isRevoked(tokenHash)) return {status:'revoked'};throw error;}
  }
  const issued=deps.now();
  const lease=await signJwt({aud:packageName,sub:device.hash,product_id:productId,jti:grantId,purchase_token_hash:tokenHash,
    iat:issued,refresh_after:issued+86400,exp:issued+7*86400},await deps.signingKey());
  // A notification/voided scan may have revoked the grant during acknowledgement/signing.
  if(await deps.ledger.isRevoked(tokenHash)) return {status:'revoked'};
  return {status:'active',lease};
}
