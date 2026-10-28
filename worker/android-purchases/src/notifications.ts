import {createRemoteJWKSet,customFetch,jwtVerify,type JWTVerifyGetKey} from 'jose';
import {boundedJson,bytes,decode64,hash,object,purchaseStatus,text} from './protocol';
import {PurchaseLedger} from './ledger';
import type {PlayGateway} from './verify';

export class PushRejected extends Error {constructor() {super('push_rejected');}}
export class NotificationInvalid extends Error {constructor() {super('notification_invalid');}}
export type NotificationConfiguration={packageName:string,productId:string,audience:string,email:string,subject:string,subscription:string};

// Public Google verification keys may be cached across requests. No message, JWT or credential
// is retained here; URL, redirect behavior, response size and fetch timeout are fixed.
const googleKeys=createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'),{
  timeoutDuration:5000,cooldownDuration:30000,cacheMaxAge:600000,
  [customFetch]:async(url,options)=>{
    try {
      const response=await fetch(url,{...options,redirect:'error'});
      if(!response.ok) {await response.body?.cancel();throw new Error('google_push_keys_unavailable');}
      return Response.json(await boundedJson(response,64*1024));
    } catch {throw new Error('google_push_keys_unavailable');}
  }
});
export function notificationConfiguration(env:Env):NotificationConfiguration {
  if(String(env.RTDN_ENABLED)!=='true') throw new Error('unconfigured');
  const audience=text(env.RTDN_AUDIENCE,2048),url=new URL(audience);
  const email=text(env.RTDN_PUSH_EMAIL,320),subject=text(env.RTDN_PUSH_SUBJECT,40),subscription=text(env.RTDN_SUBSCRIPTION,512);
  if(url.protocol!=='https:' || url.username || url.password || url.search || url.hash ||
    !/^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\.iam\.gserviceaccount\.com$/.test(email) || !/^\d{10,30}$/.test(subject) ||
    !/^projects\/[A-Za-z0-9:._-]+\/subscriptions\/[A-Za-z0-9._~-]+$/.test(subscription)) throw new Error('unconfigured');
  return {packageName:env.PACKAGE_NAME,productId:env.PRODUCT_ID,audience,email,subject,subscription};
}
export async function verifyPushIdentity(request:Request,config:NotificationConfiguration,keys:JWTVerifyGetKey=googleKeys,now=new Date()):Promise<void> {
  const authorization=request.headers.get('Authorization')??'';
  if(authorization.length>8192 || !/^Bearer [A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(authorization)) throw new PushRejected();
  try {
    const {payload}=await jwtVerify(authorization.slice(7),keys,{algorithms:['RS256'],
      issuer:['https://accounts.google.com','accounts.google.com'],audience:config.audience,subject:config.subject,
      requiredClaims:['iss','aud','sub','iat','exp','email','email_verified'],currentDate:now,clockTolerance:30,maxTokenAge:'65m'});
    if(payload.aud!==config.audience || payload.email!==config.email || payload.email_verified!==true) throw new PushRejected();
  } catch(error) {
    if(error instanceof Error && (error.message.startsWith('google_') || ('code' in error && error.code==='ERR_JWKS_TIMEOUT'))) throw new Error('google_push_keys_unavailable');
    throw new PushRejected();
  }
}

type Notification={messageHash:string,kind:'ignored'|'test'}|{messageHash:string,kind:'product',token:string,canceled:boolean}|{messageHash:string,kind:'voided',token:string};
export async function parseNotification(input:unknown,config:NotificationConfiguration,now:number):Promise<Notification> {
  try {
    const envelope=object(input),message=object(envelope.message);
    if(envelope.subscription!==config.subscription) throw new NotificationInvalid();
    const messageId=text(message.messageId,256),encoded=text(message.data,90_000);
    if(!/^[A-Za-z0-9+/_-]+={0,2}$/.test(encoded)) throw new NotificationInvalid();
    const raw=decode64(encoded);if(raw.length>64*1024) throw new NotificationInvalid();
    const event=object(JSON.parse(new TextDecoder('utf-8',{fatal:true,ignoreBOM:false}).decode(raw)));
    const time=text(event.eventTimeMillis,20);
    if(event.version!=='1.0' || event.packageName!==config.packageName || !/^\d+$/.test(time) ||
      !Number.isSafeInteger(Number(time)) || Number(time)>now*1000+300000) throw new NotificationInvalid();
    const kinds=['oneTimeProductNotification','voidedPurchaseNotification','subscriptionNotification','pendingRefundReviewNotification','testNotification'].filter(key=>event[key]!==undefined);
    if(kinds.length!==1) throw new NotificationInvalid();
    const kind=kinds[0],payload=object(event[kind]);
    const messageHash=await hash(bytes(config.subscription+'\n'+messageId));
    if(kind==='testNotification') {
      if(payload.version!=='1.0') throw new NotificationInvalid();
      return {messageHash,kind:'test'};
    }
    if(kind==='oneTimeProductNotification') {
      if(payload.version!=='1.0' || ![1,2].includes(Number(payload.notificationType)) || typeof payload.notificationType!=='number') throw new NotificationInvalid();
      const token=text(payload.purchaseToken),sku=text(payload.sku,200);
      return sku===config.productId?{messageHash,kind:'product',token,canceled:payload.notificationType===2}:{messageHash,kind:'ignored'};
    }
    if(kind==='voidedPurchaseNotification') {
      if(![1,2].includes(Number(payload.productType)) || typeof payload.productType!=='number' ||
        ![1,2].includes(Number(payload.refundType)) || typeof payload.refundType!=='number') throw new NotificationInvalid();
      const token=text(payload.purchaseToken);
      // This product permits one unit. Quantity-based partial refunds and subscriptions are
      // outside its grant policy and never cause an unrelated entitlement to be revoked.
      return payload.productType===2 && payload.refundType===1?{messageHash,kind:'voided',token}:{messageHash,kind:'ignored'};
    }
    return {messageHash,kind:'ignored'};
  } catch(error) {if(error instanceof NotificationInvalid) throw error;throw new NotificationInvalid();}
}

/** Acknowledgement follows durable processing. In-progress duplicates retry; only completed
 * receipts acknowledge immediately. Expired claims are reclaimable after a worker failure. */
export class NotificationReceipts {
  constructor(private readonly database:D1Database) {}
  async claim(id:string,now:number):Promise<{status:'claimed',owner:string}|{status:'done'}|{status:'busy'}> {
    const owner=crypto.randomUUID();
    const row=await this.database.prepare(`INSERT INTO purchase_notifications(message_hash,lease_id,lease_until,expires_at) VALUES(?,?,?,?)
      ON CONFLICT(message_hash) DO UPDATE SET lease_id=excluded.lease_id,lease_until=excluded.lease_until,expires_at=excluded.expires_at
      WHERE purchase_notifications.processed_at IS NULL AND purchase_notifications.lease_until<=?
      RETURNING lease_id`).bind(id,owner,now+120,now+35*86400,now).first();
    if(row) return {status:'claimed',owner};
    const saved=await this.database.prepare('SELECT processed_at FROM purchase_notifications WHERE message_hash=?').bind(id).first<{processed_at:number|null}>();
    return {status:saved?.processed_at!=null?'done':'busy'};
  }
  async complete(id:string,owner:string,now:number,outcome:'handled'|'ignored'|'test'):Promise<void> {
    const row=await this.database.prepare('UPDATE purchase_notifications SET processed_at=?,outcome=? WHERE message_hash=? AND lease_id=? AND processed_at IS NULL AND lease_until>? RETURNING message_hash')
      .bind(now,outcome,id,owner,now).first();
    if(!row) throw new Error('notification_claim_lost');
  }
  async release(id:string,owner:string):Promise<void> {
    await this.database.prepare('DELETE FROM purchase_notifications WHERE message_hash=? AND lease_id=? AND processed_at IS NULL').bind(id,owner).run();
  }
}
export async function processNotification(event:Notification,config:NotificationConfiguration,deps:{ledger:PurchaseLedger,receipts:NotificationReceipts,google:()=>Promise<PlayGateway>,now:()=>number}):Promise<void> {
  const claim=await deps.receipts.claim(event.messageHash,deps.now());
  if(claim.status==='done') return;
  if(claim.status==='busy') throw new Error('notification_busy');
  try {
    if(event.kind==='voided') await deps.ledger.revoke(await hash(bytes(event.token)),deps.now(),'rtdn');
    if(event.kind==='product') {
      const google=await deps.google();
      const status=purchaseStatus(await google.purchase(config.productId,event.token),config.productId);
      if(status.status==='revoked') await deps.ledger.revoke(await hash(bytes(event.token)),deps.now(),'rtdn');
      else if(event.canceled || status.status==='pending') throw new Error('google_notification_not_settled');
      // A purchased notification is only a signal. It never creates a grant or a lease.
    }
    await deps.receipts.complete(event.messageHash,claim.owner,deps.now(),event.kind==='test'?'test':event.kind==='ignored'?'ignored':'handled');
  } catch(error) {await deps.receipts.release(event.messageHash,claim.owner);throw error;}
}
