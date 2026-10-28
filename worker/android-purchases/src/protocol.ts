const encoder=new TextEncoder();
export const bytes=(value:string)=>encoder.encode(value);
export const base64url=(value:Uint8Array|ArrayBuffer)=>btoa(String.fromCharCode(...(value instanceof Uint8Array?value:new Uint8Array(value)))).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'');
export function decode64(value:string):Uint8Array {
  const normalized=value.replaceAll('-','+').replaceAll('_','/');
  return Uint8Array.from(atob(normalized+'='.repeat((4-normalized.length%4)%4)),x=>x.charCodeAt(0));
}
export async function hash(value:Uint8Array):Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256',value))].map(x=>x.toString(16).padStart(2,'0')).join('');
}
export function message(challengeId:string,nonce:string,packageName:string,productId:string,tokenHash:string,deviceHash:string):Uint8Array {
  const parts=['whereday-purchase-v1',challengeId,nonce,packageName,productId,tokenHash,deviceHash];
  if(parts.some(x=>!x || /[\r\n]/.test(x))) throw new Error('invalid_message');
  return bytes(parts.join('\n'));
}
export function object(value:unknown):Record<string,unknown> {
  if(!value || typeof value!=='object' || Array.isArray(value)) throw new Error('invalid_object');
  return value as Record<string,unknown>;
}
export function text(value:unknown,max=8192):string {
  if(typeof value!=='string' || value.length<1 || value.length>max || /[\r\n]/.test(value)) throw new Error('invalid_text');
  return value;
}
export async function publicKey(spki:string):Promise<{key:CryptoKey,hash:string}> {
  const raw=decode64(text(spki,2048));
  const key=await crypto.subtle.importKey('spki',raw,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);
  if(!('modulusLength' in key.algorithm) || typeof key.algorithm.modulusLength!=='number' || key.algorithm.modulusLength<2048) throw new Error('weak_key');
  return {key,hash:await hash(raw)};
}
export async function privateKey(pem:string):Promise<CryptoKey> {
  const raw=decode64(pem.replace(/-----[A-Z ]+-----/g,'').replace(/\s/g,''));
  const key=await crypto.subtle.importKey('pkcs8',raw,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
  if(!('modulusLength' in key.algorithm) || typeof key.algorithm.modulusLength!=='number' || key.algorithm.modulusLength<2048) throw new Error('weak_key');
  return key;
}
export async function signJwt(claims:Record<string,unknown>,key:CryptoKey):Promise<string> {
  const signingInput=base64url(bytes(JSON.stringify({alg:'RS256',typ:'JWT'})))+'.'+base64url(bytes(JSON.stringify(claims)));
  return signingInput+'.'+base64url(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,bytes(signingInput)));
}

/** Bound unknown HTTP bodies before JSON parsing, including chunked responses. */
export async function boundedJson(source:Request|Response,max=128*1024):Promise<unknown> {
  if(!source.body) throw new Error('empty_body');
  const reader=source.body.getReader();let size=0;const joined=new Uint8Array(max);
  try {
    while(true) {
      const {done,value}=await reader.read();if(done) break;
      if(size+value.length>max) throw new Error('body_too_large');joined.set(value,size);size+=value.length;
    }
    return JSON.parse(new TextDecoder().decode(joined.subarray(0,size)));
  } finally { await reader.cancel();reader.releaseLock(); }
}

export function validateIntegrity(payload:unknown,expected:{packageName:string,requestHash:string,certificates:string[],minimumVersion:number,now:number}):void {
  const root=object(payload),request=object(root.requestDetails),app=object(root.appIntegrity);
  const timestamp=Number(request.timestampMillis),version=Number(app.versionCode);
  const certificates=app.certificateSha256Digest;
  if(request.requestPackageName!==expected.packageName || request.requestHash!==expected.requestHash ||
    !Number.isFinite(timestamp) || Math.abs(expected.now*1000-timestamp)>300_000 ||
    app.appRecognitionVerdict!=='PLAY_RECOGNIZED' || app.packageName!==expected.packageName ||
    !Number.isSafeInteger(version) || version<expected.minimumVersion ||
    !Array.isArray(certificates) || certificates.length===0 ||
    !certificates.every(cert=>typeof cert==='string' && expected.certificates.includes(cert)) ||
    object(root.accountDetails).appLicensingVerdict!=='LICENSED' ||
    !Array.isArray(object(root.deviceIntegrity).deviceRecognitionVerdict) ||
    !(object(root.deviceIntegrity).deviceRecognitionVerdict as unknown[]).includes('MEETS_DEVICE_INTEGRITY')) throw new Error('integrity_rejected');
}

export type PurchaseStatus={status:'active'|'pending'|'revoked',acknowledged:boolean};
export function purchaseStatus(value:unknown,productId:string):PurchaseStatus {
  const purchase=object(value);
  if(purchase.productId!==undefined && purchase.productId!==productId) throw new Error('wrong_product');
  if(purchase.purchaseState===1) return {status:'revoked',acknowledged:false};
  if(purchase.purchaseState===2) return {status:'pending',acknowledged:false};
  if(purchase.purchaseState!==0 || purchase.consumptionState!==0 ||
    (purchase.quantity!==undefined && purchase.quantity!==1) ||
    (purchase.acknowledgementState!==0 && purchase.acknowledgementState!==1)) throw new Error('ineligible_purchase');
  return {status:'active',acknowledged:purchase.acknowledgementState===1};
}
