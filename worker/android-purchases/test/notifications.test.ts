import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {getPlatformProxy} from 'wrangler';
import {createLocalJWKSet,generateKeyPair,exportJWK,SignJWT} from 'jose';
import {notificationConfiguration,verifyPushIdentity,parseNotification,processNotification,NotificationReceipts,type NotificationConfiguration} from '../src/notifications';
import {PurchaseLedger,PurchaseRevoked} from '../src/ledger';
import {bytes,hash,base64url,message} from '../src/protocol';
import {verifyPurchase} from '../src/verify';
import worker from '../src/index';

const now=1_793_098_800;
const config:NotificationConfiguration={packageName:'com.bontecou.whereday',productId:'whereday_export_lifetime',audience:'https://purchases.example.invalid/v1/rtdn',email:'push@test-project.iam.gserviceaccount.com',subject:'123456789012345678901',subscription:'projects/test-project/subscriptions/whereday'};
const keysPromise=generateKeyPair('RS256',{extractable:true});
async function signed(change:Record<string,unknown>={},header:Record<string,unknown>={}) {
  const pair=await keysPromise;
  return new SignJWT({iss:'https://accounts.google.com',aud:config.audience,sub:config.subject,iat:now,exp:now+3600,email:config.email,email_verified:true,...change})
    .setProtectedHeader({alg:'RS256',kid:'synthetic-key',...header}).sign(pair.privateKey);
}
async function localKeys() {return createLocalJWKSet({keys:[{...await exportJWK((await keysPromise).publicKey),kid:'synthetic-key'}]});}
const request=(token:string)=>new Request(config.audience,{method:'POST',headers:{Authorization:'Bearer '+token}});
const event=(payload:Record<string,unknown>,messageId='synthetic-message')=>({subscription:config.subscription,message:{messageId,data:Buffer.from(JSON.stringify({version:'1.0',packageName:config.packageName,eventTimeMillis:String(now*1000),...payload})).toString('base64')}});
const product=(token='synthetic-purchase-token',type=2,sku=config.productId)=>({oneTimeProductNotification:{version:'1.0',notificationType:type,purchaseToken:token,sku}});
const voided=(token='synthetic-purchase-token')=>({voidedPurchaseNotification:{purchaseToken:token,orderId:'synthetic-order',productType:2,refundType:1}});
const active={purchaseState:0,consumptionState:0,acknowledgementState:1,quantity:1};
async function database() {
  const proxy=await getPlatformProxy<Env>({configPath:'wrangler.jsonc',persist:false,remoteBindings:false});
  for(const name of ['0001_purchases.sql','0002_revocations.sql']) {
    const sql=(await readFile(new URL('../migrations/'+name,import.meta.url),'utf8')).replace(/^--.*$/gm,'');
    for(const statement of sql.split(';').map(x=>x.trim()).filter(Boolean)) await proxy.env.PURCHASES.prepare(statement).run();
  }
  return proxy;
}

test('OIDC verifies Google issuer, audience, fixed service-account identity, verified email, algorithm and times',async()=>{
  const keys=await localKeys();const time=new Date(now*1000);
  await verifyPushIdentity(request(await signed()),config,keys,time);
  await verifyPushIdentity(request(await signed({iss:'accounts.google.com',iat:now-3590,exp:now+10})),config,keys,time);
  for(const claims of [{iss:'https://attacker.invalid'}, {aud:'https://another.invalid'}, {aud:[config.audience,'https://another.invalid']}, {sub:'999999999999999999999'},
    {email:'other@test-project.iam.gserviceaccount.com'}, {email_verified:false}, {email_verified:'true'},
    {iat:now+60}, {exp:now-60}, {iat:undefined}, {exp:undefined}, {email:undefined}, {email_verified:undefined}]) {
    await assert.rejects(()=>signed(claims).then(token=>verifyPushIdentity(request(token),config,keys,time)),/push_rejected/);
  }
  await assert.rejects(()=>signed({}, {kid:'unknown'}).then(token=>verifyPushIdentity(request(token),config,keys,time)),/push_rejected/);
  const valid=await signed();const parts=valid.split('.');
  parts[2]=(parts[2][0]==='A'?'B':'A')+parts[2].slice(1);
  await assert.rejects(()=>verifyPushIdentity(request(parts.join('.')),config,keys,time),/push_rejected/);
  parts[0]=Buffer.from(JSON.stringify({alg:'HS256',kid:'synthetic-key'})).toString('base64url');
  await assert.rejects(()=>verifyPushIdentity(request(parts.join('.')),config,keys,time),/push_rejected/);
});

test('Missing or oversized authorization never reaches a key resolver; delivery stays disabled by default',async()=>{
  let resolves=0;const keys=async()=>{resolves++;throw new Error('Unexpected network')};
  for(const token of ['', 'Bearer nope', 'Bearer '+'x'.repeat(9000)]) {
    await assert.rejects(()=>verifyPushIdentity(new Request(config.audience,{headers:{Authorization:token}}),config,keys),/push_rejected/);
  }
  assert.equal(resolves,0);
  assert.throws(()=>notificationConfiguration({RTDN_ENABLED:'false'} as Env),/unconfigured/);
});

test('Wrapped RTDN validates package, subscription, one event, version, timestamp, encoding and bounded fields',async()=>{
  const parsed=await parseNotification(event(product()),config,now);assert.equal(parsed.kind,'product');
  assert.ok(!JSON.stringify(parsed).includes(config.subscription));
  for(const value of [
    {...event(product()),subscription:'projects/other/subscriptions/other'},
    event({packageName:'other.app',...product()}),event({version:'2.0',...product()}),
    event({eventTimeMillis:String((now+301)*1000),...product()}),event({eventTimeMillis:'NaN',...product()}),
    event({...product(),testNotification:{version:'1.0'}}),event({}),
    event(product('x'.repeat(8193))),event({oneTimeProductNotification:{version:'1.0',notificationType:'2',purchaseToken:'synthetic',sku:config.productId}}),
    {subscription:config.subscription,message:{messageId:'m',data:'!invalid!'}},
    {subscription:config.subscription,message:{messageId:'m',data:Buffer.from([0xff]).toString('base64')}},
    event({unknown:'x'.repeat(70000),...product()})
  ]) await assert.rejects(()=>parseNotification(value,config,now),/notification_invalid/);
  assert.equal((await parseNotification(event(product('synthetic',1,'other-product')),config,now)).kind,'ignored');
  assert.equal((await parseNotification(event({subscriptionNotification:{}}),config,now)).kind,'ignored');
});

test('Concurrent delivery claims retry while busy; only durable completed receipts suppress re-delivery',async()=>{
  const proxy=await database();try {
    const ledger=new PurchaseLedger(proxy.env.PURCHASES),receipts=new NotificationReceipts(proxy.env.PURCHASES);
    let resume!:()=>void,entered!:()=>void,reads=0;
    const wait=new Promise<void>(resolve=>resume=resolve),started=new Promise<void>(resolve=>entered=resolve);
    const notification=await parseNotification(event(product('synthetic',1)),config,now);
    const deps={ledger,receipts,now:()=>now,google:async()=>({integrity:async()=>({}),acknowledge:async()=>{},purchase:async()=>{reads++;entered();await wait;return active}})};
    const first=processNotification(notification,config,deps);await started;
    await assert.rejects(()=>processNotification(notification,config,deps),/notification_busy/);
    resume();await first;await processNotification(notification,config,deps);
    assert.equal(reads,1);assert.equal((await proxy.env.PURCHASES.prepare('SELECT COUNT(*) AS count FROM grants').first())?.count,0);
    const saved=await proxy.env.PURCHASES.prepare('SELECT processed_at,outcome FROM purchase_notifications').first();
    assert.equal(saved?.processed_at,now);assert.equal(saved?.outcome,'handled');
  } finally {await proxy.dispose();}
});

test('Failed or not-yet-settled Publisher queries release claims and retry without granting access',async()=>{
  const proxy=await database();try {
    const ledger=new PurchaseLedger(proxy.env.PURCHASES),receipts=new NotificationReceipts(proxy.env.PURCHASES);
    const parsed=await parseNotification(event(product()),config,now);let state:unknown=active;
    const deps={ledger,receipts,now:()=>now,google:async()=>({integrity:async()=>({}),acknowledge:async()=>{},purchase:async()=>state})};
    await assert.rejects(()=>processNotification(parsed,config,deps),/not_settled/);
    assert.equal((await proxy.env.PURCHASES.prepare('SELECT COUNT(*) AS count FROM purchase_notifications').first())?.count,0);
    state={purchaseState:2};
    const pending=await parseNotification(event(product('synthetic-pending',1),'pending-message'),config,now);
    await assert.rejects(()=>processNotification(pending,config,deps),/not_settled/);
    state={purchaseState:1};await processNotification(parsed,config,deps);
    const tokenHash=await hash(bytes('synthetic-purchase-token'));assert.ok(await ledger.isRevoked(tokenHash));
    await assert.rejects(()=>ledger.grant(tokenHash,config.productId,now+1),PurchaseRevoked);
  } finally {await proxy.dispose();}
});

test('Full void notifications revoke known and unseen tokens permanently; stale purchased delivery cannot reactivate',async()=>{
  const proxy=await database();try {
    const ledger=new PurchaseLedger(proxy.env.PURCHASES),receipts=new NotificationReceipts(proxy.env.PURCHASES);
    const token='synthetic-purchase-token',tokenHash=await hash(bytes(token));const grant=await ledger.grant(tokenHash,config.productId,now);
    let reads=0;
    const deps={ledger,receipts,now:()=>now,google:async()=>({integrity:async()=>({}),acknowledge:async()=>{},purchase:async()=>{reads++;return active}})};
    await processNotification(await parseNotification(event(voided()),config,now),config,deps);assert.equal(reads,0);
    const row=await proxy.env.PURCHASES.prepare('SELECT grant_id,state FROM grants').first();assert.equal(row?.grant_id,grant);assert.equal(row?.state,'revoked');
    await processNotification(await parseNotification(event(product(token,1),'later-purchased'),config,now),config,deps);
    await assert.rejects(()=>ledger.grant(tokenHash,config.productId,now+1),PurchaseRevoked);
    const unseen='synthetic-unseen';await processNotification(await parseNotification(event(voided(unseen),'unseen-message'),config,now),config,deps);
    const unseenHash=await hash(bytes(unseen));
    await assert.rejects(()=>ledger.grant(unseenHash,config.productId,now+1),PurchaseRevoked);
    for(const table of ['grants','revocations','purchase_notifications']) {
      const stored=JSON.stringify(await proxy.env.PURCHASES.prepare('SELECT * FROM '+table).all());
      assert.ok(!stored.includes(token));assert.ok(!stored.includes('synthetic-order'));assert.ok(!stored.includes('unseen-message'));
    }
  } finally {await proxy.dispose();}
});

test('A crash after revocation before receipt completion keeps the tombstone and allows a safe retry',async()=>{
  const proxy=await database();try {
    const ledger=new PurchaseLedger(proxy.env.PURCHASES),receipts=new NotificationReceipts(proxy.env.PURCHASES);
    const parsed=await parseNotification(event(voided()),config,now);
    await proxy.env.PURCHASES.prepare("CREATE TRIGGER fail_receipt BEFORE UPDATE ON purchase_notifications BEGIN SELECT RAISE(ABORT,'synthetic'); END").run();
    const deps={ledger,receipts,now:()=>now,google:async()=>{throw new Error('Unexpected')}};
    await assert.rejects(()=>processNotification(parsed,config,deps));
    assert.ok(await ledger.isRevoked(await hash(bytes('synthetic-purchase-token'))));
    await proxy.env.PURCHASES.prepare('DROP TRIGGER fail_receipt').run();
    await processNotification(parsed,config,deps);
    assert.equal((await proxy.env.PURCHASES.prepare('SELECT outcome FROM purchase_notifications').first())?.outcome,'handled');
  } finally {await proxy.dispose();}
});

test('Expired claims are reclaimable, stale owners cannot acknowledge, cleanup removes receipts but not revocations',async()=>{
  const proxy=await database();try {
    const receipts=new NotificationReceipts(proxy.env.PURCHASES),ledger=new PurchaseLedger(proxy.env.PURCHASES);
    const a=await receipts.claim('hash',now);assert.equal(a.status,'claimed');if(a.status!=='claimed') throw new Error('Expected claim');
    const b=await receipts.claim('hash',now+121);assert.equal(b.status,'claimed');if(b.status!=='claimed') throw new Error('Expected claim');
    await assert.rejects(()=>receipts.complete('hash',a.owner,now+121,'handled'),/claim_lost/);
    await receipts.release('hash',a.owner);await receipts.complete('hash',b.owner,now+121,'handled');
    await ledger.revoke(await hash(bytes('synthetic')),now,'rtdn');await ledger.cleanup(now+36*86400);
    assert.equal((await proxy.env.PURCHASES.prepare('SELECT COUNT(*) AS count FROM purchase_notifications').first())?.count,0);
    assert.ok(await ledger.isRevoked(await hash(bytes('synthetic'))));
  } finally {await proxy.dispose();}
});

test('A revocation during acknowledgement or signing cannot return an active verification lease',async()=>{
  const proxy=await database();try {
    const ledger=new PurchaseLedger(proxy.env.PURCHASES),device=await keysPromise;
    const publicKey=Buffer.from(await crypto.subtle.exportKey('spki',device.publicKey)).toString('base64');const deviceHash=await hash(Buffer.from(publicKey,'base64'));
    const verifyConfig={packageName:config.packageName,productId:config.productId,certificates:['synthetic-certificate'],minimumVersion:1};
    for(const point of ['before-grant','acknowledgement','signing']) {
      const token='synthetic-race-'+point,tokenHash=await hash(bytes(token)),challenge=await ledger.create(deviceHash,now);
      const canonical=message(challenge.challengeId,challenge.nonce,config.packageName,config.productId,tokenHash,deviceHash);
      const requestHash=base64url(await crypto.subtle.digest('SHA-256',canonical));
      const input={...challenge,packageName:config.packageName,productId:config.productId,purchaseToken:token,integrityToken:'synthetic-proof',publicKey,signature:base64url(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',device.privateKey,canonical))};
      if(point==='before-grant') await ledger.revoke(tokenHash,now,'rtdn');
      const result=await verifyPurchase(input,verifyConfig,{ledger,now:()=>now,
        signingKey:async()=>{if(point==='signing') await ledger.revoke(tokenHash,now,'rtdn');return device.privateKey;},
        google:async()=>({integrity:async()=>({requestDetails:{requestPackageName:config.packageName,requestHash,timestampMillis:String(now*1000)},appIntegrity:{appRecognitionVerdict:'PLAY_RECOGNIZED',packageName:config.packageName,certificateSha256Digest:['synthetic-certificate'],versionCode:'1'},accountDetails:{appLicensingVerdict:'LICENSED'},deviceIntegrity:{deviceRecognitionVerdict:['MEETS_DEVICE_INTEGRITY']}}),
          purchase:async()=>({...active,acknowledgementState:0}),acknowledge:async()=>{if(point==='acknowledgement') await ledger.revoke(tokenHash,now,'rtdn');}})});
      assert.deepEqual(result,{status:'revoked'},point);
    }
  } finally {await proxy.dispose();}
});

test('HTTP push route authenticates with bounded fixed-host JWKS and acknowledges only after durable revoke',async()=>{
  const proxy=await database(),original=globalThis.fetch;const calls:string[]=[];
  try {
    globalThis.fetch=async(input,options)=>{
      calls.push(String(input));assert.equal(String(input),'https://www.googleapis.com/oauth2/v3/certs');assert.equal(options?.redirect,'error');assert.ok(options?.signal);
      return Response.json({keys:[{...await exportJWK((await keysPromise).publicKey),kid:'synthetic-key'}]});
    };
    const env={...proxy.env,RTDN_ENABLED:'true',RTDN_AUDIENCE:config.audience,RTDN_PUSH_EMAIL:config.email,RTDN_PUSH_SUBJECT:config.subject,RTDN_SUBSCRIPTION:config.subscription};
    const actualNow=Math.floor(Date.now()/1000),jwt=await signed({iat:actualNow,exp:actualNow+3600});
    const body=event({...voided(),eventTimeMillis:String(actualNow*1000)});
    const send=(value:unknown,authorization='Bearer '+jwt)=>worker.fetch(new Request(config.audience,{method:'POST',headers:{Authorization:authorization,'Content-Type':'application/json'},body:JSON.stringify(value)}),env);
    assert.equal((await send(body,'Bearer bogus')).status,401);
    assert.equal((await send(body)).status,204);
    assert.ok(await new PurchaseLedger(proxy.env.PURCHASES).isRevoked(await hash(bytes('synthetic-purchase-token'))));
    assert.equal((await send(body)).status,204);assert.equal(calls.length,1);
    assert.equal((await send({...body,subscription:'projects/other/subscriptions/other'})).status,400);
    const oversized=await worker.fetch(new Request(config.audience,{method:'POST',headers:{Authorization:'Bearer '+jwt,'Content-Type':'application/json'},body:'x'.repeat(140000)}),env);
    assert.equal(oversized.status,400);
    assert.equal((await worker.fetch(new Request(config.audience,{method:'POST'}),proxy.env)).status,503);
  } finally {globalThis.fetch=original;await proxy.dispose();}
});

test('The additive migration preserves older revoked grants and new code cannot restore a legacy revoked row',async()=>{
  const proxy=await getPlatformProxy<Env>({configPath:'wrangler.jsonc',persist:false,remoteBindings:false});
  try {
    const apply=async(name:string)=>{
      const sql=(await readFile(new URL('../migrations/'+name,import.meta.url),'utf8')).replace(/^--.*$/gm,'');
      for(const statement of sql.split(';').map(x=>x.trim()).filter(Boolean)) await proxy.env.PURCHASES.prepare(statement).run();
    };
    await apply('0001_purchases.sql');
    const tokenHash=await hash(bytes('synthetic-legacy-token'));
    await proxy.env.PURCHASES.prepare("INSERT INTO grants VALUES(?,'synthetic-grant',?,?,'revoked')").bind(tokenHash,config.productId,now).run();
    await apply('0002_revocations.sql');
    assert.equal((await proxy.env.PURCHASES.prepare('SELECT source FROM revocations WHERE token_hash=?').bind(tokenHash).first())?.source,'verification');
    const ledger=new PurchaseLedger(proxy.env.PURCHASES);
    await assert.rejects(()=>ledger.grant(tokenHash,config.productId,now+1),PurchaseRevoked);
    const other=await hash(bytes('synthetic-old-writer'));
    await ledger.grant(other,config.productId,now);
    await proxy.env.PURCHASES.prepare("UPDATE grants SET state='revoked' WHERE token_hash=?").bind(other).run();
    assert.ok(await ledger.isRevoked(other));await assert.rejects(()=>ledger.grant(other,config.productId,now+1),PurchaseRevoked);
    assert.equal((await proxy.env.PURCHASES.prepare('SELECT grant_id FROM grants WHERE token_hash=?').bind(tokenHash).first())?.grant_id,'synthetic-grant');
  } finally {await proxy.dispose();}
});
