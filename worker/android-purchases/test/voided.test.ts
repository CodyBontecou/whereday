import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {getPlatformProxy} from 'wrangler';
import {base64url,bytes,hash} from '../src/protocol';
import {PurchaseLedger,PurchaseRevoked} from '../src/ledger';
import {VoidedCursorCipher,VoidedScanStore,parseVoidedPage,reconcileVoided,runVoidedReconciliation,type ScanState} from '../src/voided';
import {GooglePlay} from '../src/google';
import worker from '../src/index';

const base=1_793_098_800,scope='com.bontecou.whereday',product='whereday_export_lifetime';
const secret=base64url(crypto.getRandomValues(new Uint8Array(32)));
const record=(index:number)=>({kind:'androidpublisher#voidedPurchase',purchaseToken:'synthetic-refund-'+index,voidedTimeMillis:String((base-10000000)*1000),orderId:'synthetic-order-'+index});
const page=(indexes:number[],next?:string)=>({voidedPurchases:indexes.map(record),tokenPagination:next===undefined?{}:{nextPageToken:next}});
async function setup() {
  const proxy=await getPlatformProxy<Env>({configPath:'wrangler.jsonc',persist:false,remoteBindings:false});
  for(const name of ['0001_purchases.sql','0002_revocations.sql','0003_voided_reconciliation.sql']) {
    const sql=(await readFile(new URL('../migrations/'+name,import.meta.url),'utf8')).replace(/^--.*$/gm,'');
    for(const statement of sql.split(';').map(x=>x.trim()).filter(Boolean)) await proxy.env.PURCHASES.prepare(statement).run();
  }
  const store=new VoidedScanStore(proxy.env.PURCHASES),ledger=new PurchaseLedger(proxy.env.PURCHASES),cipher=await VoidedCursorCipher.create(secret);
  let now=base;
  return {proxy,store,ledger,cipher,now:()=>now,setTime:(value:number)=>{now=value},state:()=>proxy.env.PURCHASES.prepare('SELECT * FROM voided_reconciliation WHERE scope=?').bind(scope).first<ScanState>()};
}

test('Four-page runs resume encrypted cursors after reconstruction, revoke every row and only then advance coverage',async()=>{
  const fixture=await setup();try {
    const {proxy,ledger}=fixture;const calls:(string|null)[]=[];
    for(const index of [0,209]) await ledger.grant(await hash(bytes(record(index).purchaseToken)),product,base);
    const google={voided:async(start:number|null,end:number,cursor:string|null)=>{
      calls.push(cursor);assert.equal(start,null);assert.equal(end,base*1000-60000);
      const index=cursor===null?0:Number(cursor.split('-').at(-1));
      return page(Array.from({length:index===4?10:50},(_,n)=>index*50+n),index===4?undefined:'synthetic-cursor-'+(index+1));
    }};
    const first=await reconcileVoided(scope,{...fixture,google:async()=>google});
    assert.deepEqual(first,{status:'more',processed:200,coverageGap:false});
    const saved=await fixture.state();assert.equal(saved?.last_completed_end,null);assert.ok(saved?.cursor);assert.equal(saved?.lease_id,null);
    const persisted=JSON.stringify(await proxy.env.PURCHASES.prepare('SELECT * FROM voided_reconciliation').all());
    assert.ok(!persisted.includes('synthetic-cursor'));assert.ok(!persisted.includes('synthetic-refund'));assert.ok(!persisted.includes(secret));
    const next=await reconcileVoided(scope,{...fixture,store:new VoidedScanStore(proxy.env.PURCHASES),cipher:await VoidedCursorCipher.create(secret),google:async()=>google});
    assert.deepEqual(next,{status:'complete',processed:10,coverageGap:false});assert.deepEqual(calls,[null,'synthetic-cursor-1','synthetic-cursor-2','synthetic-cursor-3','synthetic-cursor-4']);
    assert.equal((await fixture.state())?.last_completed_end,base*1000-60000);assert.equal((await fixture.state())?.cursor,null);
    assert.equal((await proxy.env.PURCHASES.prepare('SELECT COUNT(*) AS n FROM revocations').first())?.n,210);
    for(const index of [0,209]) {const tokenHash=await hash(bytes(record(index).purchaseToken));await assert.rejects(()=>ledger.grant(tokenHash,product,base+1),PurchaseRevoked);}
    assert.ok(!JSON.stringify(await proxy.env.PURCHASES.prepare('SELECT * FROM revocations').all()).includes('synthetic-refund'));
    assert.equal((await reconcileVoided(scope,{...fixture,google:async()=>{throw new Error('Should throttle')}})).status,'idle');
  } finally {await fixture.proxy.dispose();}
});

test('Malformed/oversized pages and cursor loops never falsely complete or advance past an unapplied page',async()=>{
  for(const malformed of [{voidedPurchases:null},{voidedPurchases:[{}]},page(Array.from({length:51},(_,n)=>n)),{...page([0]),tokenPagination:{nextPageToken:'x'.repeat(8193)}}]) {
    await assert.rejects(()=>parseVoidedPage(malformed,base));
  }
  const fixture=await setup();try {
    let calls=0;
    await assert.rejects(()=>reconcileVoided(scope,{...fixture,google:async()=>({voided:async()=>{calls++;return page([calls],'repeated-cursor')}})}),/cursor_loop/);
    assert.equal(calls,2);const state=await fixture.state();assert.equal(state?.last_completed_end,null);assert.equal(state?.lease_id,null);assert.equal(state?.last_error_at,base);
    assert.equal((await fixture.proxy.env.PURCHASES.prepare('SELECT COUNT(*) AS n FROM revocations').first())?.n,1);
  } finally {await fixture.proxy.dispose();}
});

test('A failed later revocation chunk leaves its cursor unadvanced; retry safely replays already committed hashes',async()=>{
  const fixture=await setup();try {
    const database=fixture.proxy.env.PURCHASES,target=await hash(bytes(record(30).purchaseToken));
    await database.prepare(`CREATE TRIGGER reject_refund BEFORE INSERT ON revocations WHEN NEW.token_hash='${target}' BEGIN SELECT RAISE(ABORT,'synthetic'); END`).run();
    const deps={...fixture,google:async()=>({voided:async()=>page(Array.from({length:50},(_,n)=>n))})};
    await assert.rejects(()=>reconcileVoided(scope,deps));
    assert.equal((await database.prepare('SELECT COUNT(*) AS n FROM revocations').first())?.n,25);
    assert.equal((await fixture.state())?.cursor,null);assert.equal((await fixture.state())?.last_completed_end,null);
    await database.prepare('DROP TRIGGER reject_refund').run();
    assert.equal((await reconcileVoided(scope,deps)).status,'complete');
    assert.equal((await database.prepare('SELECT COUNT(*) AS n FROM revocations').first())?.n,50);
  } finally {await fixture.proxy.dispose();}
});

test('Interruption after revocation but before final checkpoint never loses a refund or claims success',async()=>{
  const fixture=await setup();try {
    const database=fixture.proxy.env.PURCHASES;
    await database.prepare("CREATE TRIGGER reject_completion BEFORE UPDATE ON voided_reconciliation WHEN NEW.last_completed_end IS NOT NULL BEGIN SELECT RAISE(ABORT,'synthetic'); END").run();
    const deps={...fixture,google:async()=>({voided:async()=>page([1,2])})};
    await assert.rejects(()=>reconcileVoided(scope,deps));
    assert.equal((await database.prepare('SELECT COUNT(*) AS n FROM revocations').first())?.n,2);
    assert.equal((await fixture.state())?.last_completed_end,null);
    await database.prepare('DROP TRIGGER reject_completion').run();
    assert.equal((await reconcileVoided(scope,deps)).status,'complete');
  } finally {await fixture.proxy.dispose();}
});

test('An expired worker cannot checkpoint, complete or release the newer owner; stale revocation writes are harmless',async()=>{
  const fixture=await setup();try {
    let entered!:()=>void,resume!:()=>void;
    const started=new Promise<void>(resolve=>entered=resolve),blocked=new Promise<void>(resolve=>resume=resolve);
    const old=reconcileVoided(scope,{...fixture,google:async()=>({voided:async()=>{entered();await blocked;return page([1])}})});
    await started;
    const busy=await reconcileVoided(scope,{...fixture,google:async()=>{throw new Error('Busy must not call Google')}});
    assert.equal(busy.status,'busy');assert.equal(busy.coverageGap,null);
    fixture.setTime(base+181);
    assert.equal((await reconcileVoided(scope,{...fixture,google:async()=>({voided:async()=>page([2])})})).status,'complete');
    resume();await assert.rejects(()=>old,/claim_lost/);
    const state=await fixture.state();assert.equal(state?.last_success_at,base+181);assert.equal(state?.last_error_at,null);assert.equal(state?.lease_id,null);
    assert.ok(await fixture.ledger.isRevoked(await hash(bytes(record(1).purchaseToken))));
    assert.ok(await fixture.ledger.isRevoked(await hash(bytes(record(2).purchaseToken))));
  } finally {await fixture.proxy.dispose();}
});

test('A corrupt, moved or wrong-key encrypted cursor fails closed without contacting Google or discarding progress',async()=>{
  const fixture=await setup();try {
    await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(_s,_e,c)=>page([1],c===null?'cursor-1':c+'x')})});
    const saved=await fixture.state();assert.ok(saved?.cursor);
    const wrong=await VoidedCursorCipher.create(base64url(crypto.getRandomValues(new Uint8Array(32))));
    await assert.rejects(()=>reconcileVoided(scope,{...fixture,cipher:wrong,google:async()=>{throw new Error('Must not contact provider')}}),/cursor_unreadable/);
    assert.equal((await fixture.state())?.cursor,saved?.cursor);assert.equal((await fixture.state())?.last_completed_end,null);
    await assert.rejects(()=>fixture.cipher.open(saved!.cursor!,{...saved!,scope:'another.app'}),/cursor_unreadable/);
  } finally {await fixture.proxy.dispose();}
});

test('Provider cursor expiry performs one bounded recovery scan and latches a coverage warning',async()=>{
  const fixture=await setup();try {
    await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(_s,_e,c)=>page([1],c===null?'cursor-1':c+'x')})});
    const calls:(string|null)[]=[];
    const result=await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(_s,_e,c)=>{calls.push(c);if(c!==null) throw new Error('google_voided_cursor_rejected');return page([2]);}})});
    assert.equal(result.status,'complete');assert.equal(result.coverageGap,true);assert.deepEqual(calls,['cursor-1xxx',null]);
    assert.equal((await fixture.state())?.restarts,1);assert.equal((await fixture.state())?.coverage_gap,1);
  } finally {await fixture.proxy.dispose();}
});

test('Fresh windows overlap completed coverage; outages beyond provider history remain explicitly flagged',async()=>{
  const fixture=await setup();try {
    await reconcileVoided(scope,{...fixture,google:async()=>({voided:async()=>({})})});
    const previous=(await fixture.state())!.last_completed_end!;
    fixture.setTime(base+3600);let captured:number|null|undefined;
    await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(start)=>{captured=start;return {};}})});
    assert.equal(captured,previous-86400000);
    fixture.setTime(base+32*86400);
    const recovered=await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(start)=>{assert.equal(start,null);return {};}})});
    assert.equal(recovered.coverageGap,true);assert.equal((await fixture.state())?.coverage_gap,1);
  } finally {await fixture.proxy.dispose();}
});

test('Disabled configuration performs no provider work, and the scheduled handler uses the real mocked Google transport',async()=>{
  const fixture=await setup();const original=globalThis.fetch;try {
    const disabled=await runVoidedReconciliation(fixture.proxy.env,fixture.now,async()=>{throw new Error('Disabled')});
    assert.equal(disabled.status,'disabled');assert.equal(disabled.coverageGap,null);
    assert.equal(await fixture.state(),null);
    const pair=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',hash:'SHA-256',modulusLength:2048,publicExponent:new Uint8Array([1,0,1])},true,['sign','verify']);
    const pem='-----BEGIN PRIVATE KEY-----\n'+Buffer.from(await crypto.subtle.exportKey('pkcs8',pair.privateKey)).toString('base64')+'\n-----END PRIVATE KEY-----\n';
    const urls:string[]=[];
    globalThis.fetch=async(input,options)=>{
      urls.push(String(input));assert.equal(options?.redirect,'error');assert.ok(options?.signal);
      if(String(input)==='https://oauth2.googleapis.com/token') return Response.json({access_token:'synthetic-oauth'});
      const url=new URL(String(input));assert.equal(url.origin,'https://androidpublisher.googleapis.com');
      assert.equal(url.pathname,'/androidpublisher/v3/applications/'+scope+'/purchases/voidedpurchases');
      assert.equal(url.searchParams.get('maxResults'),'50');assert.equal(url.searchParams.get('type'),'0');assert.equal(url.searchParams.get('includeQuantityBasedPartialRefund'),'false');
      assert.equal(url.searchParams.get('startTime'),null);assert.equal(url.searchParams.get('token'),null);
      assert.ok(Number(url.searchParams.get('endTime'))<=Date.now());return Response.json(page([1]));
    };
    const env={...fixture.proxy.env,VOIDED_RECONCILIATION_ENABLED:'true',VOIDED_CURSOR_KEY:secret,GOOGLE_SERVICE_ACCOUNT_JSON:JSON.stringify({client_email:'synthetic@example.invalid',private_key:pem})};
    await worker.scheduled({} as ScheduledController,env);
    assert.equal(urls.length,2);assert.ok(await fixture.ledger.isRevoked(await hash(bytes(record(1).purchaseToken))));
  } finally {globalThis.fetch=original;await fixture.proxy.dispose();}
});

test('Google pagination omits ignored time bounds on continuation and fails closed for HTTP/body errors',async()=>{
  const pair=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',hash:'SHA-256',modulusLength:2048,publicExponent:new Uint8Array([1,0,1])},true,['sign','verify']);
  const pem='-----BEGIN PRIVATE KEY-----\n'+Buffer.from(await crypto.subtle.exportKey('pkcs8',pair.privateKey)).toString('base64')+'\n-----END PRIVATE KEY-----\n';
  const original=globalThis.fetch;try {
    globalThis.fetch=async()=>Response.json({access_token:'synthetic-oauth'});
    const google=await GooglePlay.connect(JSON.stringify({client_email:'synthetic@example.invalid',private_key:pem}),scope,base);
    globalThis.fetch=async(input,options)=>{
      const url=new URL(String(input));assert.equal(url.searchParams.get('token'),'opaque/+=cursor');assert.equal(url.searchParams.get('startTime'),null);assert.equal(url.searchParams.get('endTime'),null);
      assert.equal(options?.method,'GET');return Response.json({});
    };
    await google.voided(1000,2000,'opaque/+=cursor');
    globalThis.fetch=async()=>new Response(null,{status:400});await assert.rejects(()=>google.voided(null,2000,'cursor'),/cursor_rejected/);
    globalThis.fetch=async()=>new Response(null,{status:503});await assert.rejects(()=>google.voided(null,2000,null),/voided_unavailable/);
    globalThis.fetch=async()=>new Response('x'.repeat(1024*1024+1));await assert.rejects(()=>google.voided(null,2000,null),/body_too_large/);
  } finally {globalThis.fetch=original;}
});

test('An old active scan preserves its provider snapshot while exposing a possible newer coverage gap',async()=>{
  const fixture=await setup();try {
    await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(_s,_e,c)=>page([1],c===null?'cursor-1':c+'x')})});
    fixture.setTime(base+32*86400);
    const result=await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(start,end,cursor)=>{
      assert.equal(start,null);assert.equal(cursor,'cursor-1xxx');assert.equal(end,base*1000-60000);return page([99]);
    }})});
    assert.equal(result.coverageGap,true);assert.equal((await fixture.state())?.restarts,0);
    assert.ok(await fixture.ledger.isRevoked(await hash(bytes(record(99).purchaseToken))));
    fixture.setTime(fixture.now()+3600);
    await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(start,end,cursor)=>{
      assert.equal(start,null);assert.equal(cursor,null);assert.equal(end,fixture.now()*1000-60000);return {};
    }})});
  } finally {await fixture.proxy.dispose();}
});

test('Cursor encryption is byte-bounded and incomplete window metadata cannot be persisted',async()=>{
  const fixture=await setup();try {
    await assert.rejects(()=>parseVoidedPage({...page([1]),tokenPagination:{nextPageToken:'界'.repeat(3000)}},base),/cursor_too_large/);
    const claimed=await fixture.store.claim(scope,base);assert.ok(claimed);
    const state=await fixture.store.begin(claimed,null,base*1000-60000,base,false);
    const token='界'.repeat(2000),encoded=await fixture.cipher.seal(token,state);
    assert.equal(await fixture.cipher.open(encoded,state),token);assert.ok(encoded.length<16384);
    await fixture.store.checkpoint(state,encoded,base);
    await assert.rejects(()=>fixture.proxy.env.PURCHASES.prepare('UPDATE voided_reconciliation SET run_end=NULL WHERE scope=?').bind(scope).run());
    assert.equal((await fixture.state())?.run_end,state.run_end);
  } finally {await fixture.proxy.dispose();}
});

test('A failed bootstrap without a durable provider snapshot cannot silently retain its old coverage claim',async()=>{
  const fixture=await setup();try {
    await assert.rejects(()=>reconcileVoided(scope,{...fixture,google:async()=>({voided:async()=>{throw new Error('google_voided_unavailable')}})}));
    fixture.setTime(base+86400);
    const result=await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(start,end,cursor)=>{
      assert.equal(start,null);assert.equal(cursor,null);assert.equal(end,fixture.now()*1000-60000);return {};
    }})});
    assert.equal(result.coverageGap,true);assert.equal((await fixture.state())?.restarts,1);
  } finally {await fixture.proxy.dispose();}
});

test('An interrupted first page whose explicit start ages out uses the full available window and retains proven overlap',async()=>{
  const fixture=await setup();try {
    await reconcileVoided(scope,{...fixture,google:async()=>({voided:async()=>({})})});
    fixture.setTime(base+3600);
    await assert.rejects(()=>reconcileVoided(scope,{...fixture,google:async()=>({voided:async(start)=>{assert.ok(start!==null);throw new Error('google_voided_unavailable')}})}));
    fixture.setTime(base+29*86400);
    const result=await reconcileVoided(scope,{...fixture,google:async()=>({voided:async(start,end,cursor)=>{
      assert.equal(start,null);assert.equal(cursor,null);assert.equal(end,fixture.now()*1000-60000);return {};
    }})});
    assert.equal(result.coverageGap,false);assert.equal((await fixture.state())?.restarts,1);
  } finally {await fixture.proxy.dispose();}
});
