import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {getPlatformProxy} from 'wrangler';
import {base64url,boundedJson,bytes,decode64,hash,message,purchaseStatus,validateIntegrity} from '../src/protocol';
import {PurchaseLedger} from '../src/ledger';
import {verifyPurchase,type Configuration} from '../src/verify';

const config:Configuration={packageName:'com.bontecou.whereday',productId:'whereday_export_lifetime',certificates:['certificate'],minimumVersion:1};
const now=1_793_098_800;
const rsa=()=>crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',hash:'SHA-256',modulusLength:2048,publicExponent:new Uint8Array([1,0,1])},true,['sign','verify']);

function verdict(requestHash:string) {
  return {requestDetails:{requestPackageName:config.packageName,requestHash,timestampMillis:String(now*1000)},
    appIntegrity:{appRecognitionVerdict:'PLAY_RECOGNIZED',packageName:config.packageName,certificateSha256Digest:['certificate'],versionCode:'1'},
    accountDetails:{appLicensingVerdict:'LICENSED'},deviceIntegrity:{deviceRecognitionVerdict:['MEETS_DEVICE_INTEGRITY']}};
}

test('Play states never turn unknown, consumed, pending or canceled purchases into active access',()=>{
  assert.deepEqual(purchaseStatus({purchaseState:2},config.productId),{status:'pending',acknowledged:false});
  assert.deepEqual(purchaseStatus({purchaseState:1},config.productId),{status:'revoked',acknowledged:false});
  for(const purchase of [{},{purchaseState:0,consumptionState:1,acknowledgementState:0},
    {purchaseState:0,consumptionState:0,acknowledgementState:0,quantity:2},
    {purchaseState:0,consumptionState:0,acknowledgementState:0,productId:'another_product'}]) assert.throws(()=>purchaseStatus(purchase,config.productId));
});

test('Integrity binds request, package, certificate, version, account license, device and freshness',()=>{
  const expected={...config,requestHash:'request',now};
  validateIntegrity(verdict('request'),expected);
  for(const mutate of [
    (v:ReturnType<typeof verdict>)=>{v.requestDetails.requestHash='tampered';},
    (v:ReturnType<typeof verdict>)=>{v.requestDetails.timestampMillis=String((now-301)*1000);},
    (v:ReturnType<typeof verdict>)=>{v.appIntegrity.packageName='other.app';},
    (v:ReturnType<typeof verdict>)=>{v.appIntegrity.certificateSha256Digest=['other'];},
    (v:ReturnType<typeof verdict>)=>{v.appIntegrity.versionCode='0';},
    (v:ReturnType<typeof verdict>)=>{v.appIntegrity.appRecognitionVerdict='UNRECOGNIZED_VERSION';},
    (v:ReturnType<typeof verdict>)=>{v.accountDetails.appLicensingVerdict='UNLICENSED';},
    (v:ReturnType<typeof verdict>)=>{v.deviceIntegrity.deviceRecognitionVerdict=[];}
  ]) {const value=verdict('request');mutate(value);assert.throws(()=>validateIntegrity(value,expected));}
});

test('Chunked request and upstream JSON have a hard memory bound',async()=>{
  await assert.rejects(()=>boundedJson(new Response('x'.repeat(1025)),1024));
  assert.deepEqual(await boundedJson(new Response('{"status":"pending"}'),1024),{status:'pending'});
  assert.throws(()=>message('id','nonce\ninjected','package','product','hash','key'));
  assert.equal(new TextDecoder().decode(message('id','nonce','package','product','hash','key')),'whereday-purchase-v1\nid\nnonce\npackage\nproduct\nhash\nkey');
});

test('Real D1 ledger: one-use challenges, concurrent replay, durable grant before acknowledgement and signed lease',async()=>{
  const proxy=await getPlatformProxy<Env>({configPath:'wrangler.jsonc',persist:false,remoteBindings:false});
  try {
    for(const name of ['0001_purchases.sql','0002_revocations.sql']) {
      const sql=(await readFile(new URL('../migrations/'+name,import.meta.url),'utf8')).replace(/^--.*$/gm,'');
      for(const statement of sql.split(';').map(x=>x.trim()).filter(Boolean)) await proxy.env.PURCHASES.prepare(statement).run();
    }
    const ledger=new PurchaseLedger(proxy.env.PURCHASES),device=await rsa(),signer=await rsa();
    const deviceRaw=new Uint8Array(await crypto.subtle.exportKey('spki',device.publicKey));
    const deviceHash=await hash(deviceRaw),token='synthetic-test-token',tokenHash=await hash(bytes(token));
    let acknowledgements=0,purchaseReads=0,googleConnections=0;
    let state:unknown={purchaseState:0,consumptionState:0,acknowledgementState:0,quantity:1,productId:config.productId};
    let expectedHash='';let ackFails=false;
    const dependencies={ledger,now:()=>now,signingKey:async()=>signer.privateKey,google:async()=>{
      googleConnections++;
      return {integrity:async()=>verdict(expectedHash),purchase:async()=>{purchaseReads++;return state;},
        acknowledge:async()=>{
          const saved=await proxy.env.PURCHASES.prepare('SELECT grant_id FROM grants WHERE token_hash=?').bind(tokenHash).first();
          assert.ok(saved,'Durable grant must exist before acknowledgement');
          if(ackFails) throw new Error('google_acknowledgement_unavailable');
          acknowledgements++;
        }};
    }};
    const request=async()=>{
      const challenge=await ledger.create(deviceHash,now);
      const canonical=message(challenge.challengeId,challenge.nonce,config.packageName,config.productId,tokenHash,deviceHash);
      expectedHash=base64url(await crypto.subtle.digest('SHA-256',canonical));
      return {...challenge,packageName:config.packageName,productId:config.productId,purchaseToken:token,
        publicKey:Buffer.from(deviceRaw).toString('base64'),integrityToken:'synthetic-attestation',
        signature:base64url(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',device.privateKey,canonical))};
    };
    const first=await request();
    const results=await Promise.allSettled([verifyPurchase(first,config,dependencies),verifyPurchase(first,config,dependencies)]);
    assert.equal(results.filter(x=>x.status==='fulfilled').length,1);
    assert.equal(googleConnections,1);assert.equal(acknowledgements,1);
    const active=results.find(x=>x.status==='fulfilled');assert.ok(active && active.status==='fulfilled' && active.value.status==='active');
    if(active.value.status!=='active') throw new Error('Expected active');
    const parts=active.value.lease.split('.');
    assert.ok(await crypto.subtle.verify('RSASSA-PKCS1-v1_5',signer.publicKey,decode64(parts[2]),bytes(parts[0]+'.'+parts[1])));
    const claims=JSON.parse(Buffer.from(parts[1],'base64url').toString());
    assert.equal(claims.sub,deviceHash);assert.equal(claims.aud,config.packageName);
    assert.equal(claims.purchase_token_hash,tokenHash);assert.equal(claims.exp-claims.iat,7*86400);
    const second=await verifyPurchase(await request(),config,dependencies);assert.equal(second.status,'active');
    if(second.status==='active') assert.equal(JSON.parse(Buffer.from(second.lease.split('.')[1],'base64url').toString()).jti,claims.jti);
    ackFails=true;await assert.rejects(()=>request().then(x=>verifyPurchase(x,config,dependencies)),/acknowledgement/);ackFails=false;
    state={purchaseState:2};assert.deepEqual(await verifyPurchase(await request(),config,dependencies),{status:'pending'});
    state={purchaseState:1};assert.deepEqual(await verifyPurchase(await request(),config,dependencies),{status:'revoked'});
    assert.equal((await proxy.env.PURCHASES.prepare('SELECT state FROM grants WHERE token_hash=?').bind(tokenHash).first())?.state,'revoked');
    const before=purchaseReads,bad=await request();bad.purchaseToken='changed';
    await assert.rejects(()=>verifyPurchase(bad,config,dependencies),/signature/);assert.equal(purchaseReads,before);
    const expired=await request();await proxy.env.PURCHASES.prepare('UPDATE challenges SET expires_at=? WHERE id=?').bind(now-1,expired.challengeId).run();
    await assert.rejects(()=>verifyPurchase(expired,config,dependencies),/challenge/);
    assert.equal(await ledger.rate('bucket',now,1),true);assert.equal(await ledger.rate('bucket',now,1),false);
    await ledger.cleanup(now+121);
    assert.equal(await ledger.challenge(expired.challengeId),null);
    const stored=await proxy.env.PURCHASES.prepare('SELECT * FROM grants').all();
    assert.ok(!JSON.stringify(stored).includes(token),'Raw token must never be stored');
  } finally { await proxy.dispose(); }
});
