import {boundedJson,bytes,hash,object,privateKey,publicKey,text} from './protocol';
import {GooglePlay} from './google';
import {PurchaseLedger} from './ledger';
import {verifyPurchase,type Configuration} from './verify';
import {notificationConfiguration,verifyPushIdentity,parseNotification,processNotification,NotificationReceipts,PushRejected,NotificationInvalid} from './notifications';
import {runVoidedReconciliation} from './voided';

function configuration(env:Env):Configuration {
  const certificates:string[]=env.PLAY_CERTIFICATE_SHA256.split(',').map(x=>x.trim()).filter(Boolean);
  const minimumVersion=Number(env.MIN_VERSION_CODE);
  if(!certificates.length || certificates.some(x=>!/^[A-Za-z0-9_-]{43}=?$/.test(x)) ||
    !Number.isSafeInteger(minimumVersion) || minimumVersion<1 || !env.GOOGLE_SERVICE_ACCOUNT_JSON || !env.LEASE_PRIVATE_KEY_PKCS8) throw new Error('unconfigured');
  return {packageName:env.PACKAGE_NAME,productId:env.PRODUCT_ID,certificates,minimumVersion};
}
function json(body:unknown,status=200):Response {
  return Response.json(body,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
}

export default {
  async fetch(request,env):Promise<Response> {
    const notification=new URL(request.url).pathname==='/v1/rtdn';
    try {
      const path=new URL(request.url).pathname;
      if(request.method!=='POST') return json({error:'method_not_allowed'},405);
      if(path!=='/v1/challenges' && path!=='/v1/verify' && !notification) return json({error:'not_found'},404);
      if(notification) {
        const config=notificationConfiguration(env);
        await verifyPushIdentity(request,config);
        if(!request.headers.get('Content-Type')?.startsWith('application/json')) return json({error:'json_required'},415);
        let input:unknown;
        try {input=await boundedJson(request);} catch {throw new NotificationInvalid();}
        const event=await parseNotification(input,config,Math.floor(Date.now()/1000));
        await processNotification(event,config,{ledger:new PurchaseLedger(env.PURCHASES),receipts:new NotificationReceipts(env.PURCHASES),
          now:()=>Math.floor(Date.now()/1000),google:()=>GooglePlay.connect(env.GOOGLE_SERVICE_ACCOUNT_JSON,config.packageName,Math.floor(Date.now()/1000))});
        return new Response(null,{status:204,headers:{'Cache-Control':'no-store'}});
      }
      const config=configuration(env),now=Math.floor(Date.now()/1000),ledger=new PurchaseLedger(env.PURCHASES);
      // Daily salted hashing discards the literal address. Buckets expire and are deleted daily.
      const ipHash=await hash(bytes(`${Math.floor(now/86400)}:${request.headers.get('CF-Connecting-IP')??'local'}`));
      if(!await ledger.rate(`${path}:${Math.floor(now/60)}:${ipHash}`,now,path==='/v1/challenges'?30:60)) return json({error:'try_later'},429);
      if(!request.headers.get('Content-Type')?.startsWith('application/json')) return json({error:'json_required'},415);
      const input=object(await boundedJson(request));
      if(path==='/v1/challenges') {
        if(text(input.packageName,200)!==config.packageName) return json({error:'invalid_request'},403);
        const device=await publicKey(text(input.publicKey,2048));
        return json(await ledger.create(device.hash,now));
      }
      return json(await verifyPurchase(input,config,{ledger,now:()=>Math.floor(Date.now()/1000),
        google:()=>GooglePlay.connect(env.GOOGLE_SERVICE_ACCOUNT_JSON,config.packageName,Math.floor(Date.now()/1000)),
        signingKey:()=>privateKey(env.LEASE_PRIVATE_KEY_PKCS8)}));
    } catch(error) {
      // Never log exception messages, request URLs, bodies, tokens, or Google response bodies.
      const code=error instanceof Error?error.message:'';
      if(error instanceof PushRejected) return json({error:'unauthorized'},401);
      if(error instanceof NotificationInvalid) return json({error:'invalid_notification'},400);
      const unavailable=notification || code==='unconfigured' || code.startsWith('google_') || code==='grant_conflict';
      console.warn(JSON.stringify({event:notification?'purchase_notification_failed':'purchase_request_failed',category:unavailable?'service':'verification'}));
      return json({error:unavailable?'temporarily_unavailable':'verification_failed'},unavailable?503:403);
    }
  },
  async scheduled(_event,env):Promise<void> {
    const now=()=>Math.floor(Date.now()/1000);
    try {
      await new PurchaseLedger(env.PURCHASES).cleanup(now());
      const result=await runVoidedReconciliation(env,now,()=>GooglePlay.connect(env.GOOGLE_SERVICE_ACCOUNT_JSON,env.PACKAGE_NAME,now()));
      if(result.status!=='disabled') console.log(JSON.stringify({event:'voided_reconciliation',status:result.status,processed:result.processed,coverageGap:result.coverageGap}));
      if(result.coverageGap) console.warn(JSON.stringify({event:'voided_reconciliation_coverage_gap',category:'coverage'}));
    } catch {
      console.warn(JSON.stringify({event:'voided_reconciliation_failed',category:'service'}));
      throw new Error('voided_reconciliation_failed');
    }
  }
} satisfies ExportedHandler<Env>;
