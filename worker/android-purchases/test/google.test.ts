import {test} from 'node:test';
import assert from 'node:assert/strict';
import {GooglePlay} from '../src/google';

test('Google transport uses fixed OAuth audience and API hosts; ack follows purchase and never consumes',async()=>{
  const original=globalThis.fetch;
  const key=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',hash:'SHA-256',modulusLength:2048,publicExponent:new Uint8Array([1,0,1])},true,['sign','verify']);
  const pem='-----BEGIN PRIVATE KEY-----\n'+Buffer.from(await crypto.subtle.exportKey('pkcs8',key.privateKey)).toString('base64')+'\n-----END PRIVATE KEY-----\n';
  const calls:string[]=[];
  globalThis.fetch=async(input,init)=>{
    const url=String(input);calls.push(url);
    assert.equal(init?.redirect,'error');assert.ok(init?.signal);
    if(url==='https://oauth2.googleapis.com/token') {
      const assertion=new URLSearchParams(String(init?.body)).get('assertion')!;
      const [header,claims,signature]=assertion.split('.');
      assert.ok(await crypto.subtle.verify('RSASSA-PKCS1-v1_5',key.publicKey,Buffer.from(signature,'base64url'),new TextEncoder().encode(header+'.'+claims)));
      const payload=JSON.parse(Buffer.from(claims,'base64url').toString());
      assert.equal(payload.aud,'https://oauth2.googleapis.com/token');assert.equal(payload.iss,'synthetic@example.invalid');
      assert.ok(payload.scope.includes('androidpublisher'));assert.ok(payload.scope.includes('playintegrity'));
      return Response.json({access_token:'test-oauth-token'});
    }
    assert.equal((init?.headers as Record<string,string>).Authorization,'Bearer test-oauth-token');
    if(url.includes('decodeIntegrityToken')) {
      assert.deepEqual(JSON.parse(String(init?.body)),{integrity_token:'synthetic-integrity'});
      return Response.json({tokenPayloadExternal:{requestDetails:{}}});
    }
    if(url.endsWith(':acknowledge')) {assert.equal(init?.method,'POST');return new Response(null,{status:204});}
    assert.equal(init?.method,'GET');return Response.json({purchaseState:0,consumptionState:0,acknowledgementState:0});
  };
  try {
    const google=await GooglePlay.connect(JSON.stringify({client_email:'synthetic@example.invalid',private_key:pem}),'com.bontecou.whereday',1_793_098_800);
    await google.integrity('synthetic-integrity');await google.purchase('lifetime','synthetic/token');await google.acknowledge('lifetime','synthetic/token');
    assert.ok(calls[2].endsWith('/products/lifetime/tokens/synthetic%2Ftoken'));assert.ok(calls[3].endsWith(':acknowledge'));
    assert.ok(calls.every(x=>!x.includes(':consume')));
    globalThis.fetch=async()=>new Response(null,{status:404});
    assert.deepEqual(await google.purchase('lifetime','missing'),{purchaseState:1});
    globalThis.fetch=async()=>new Response(null,{status:503});
    await assert.rejects(()=>google.purchase('lifetime','synthetic'),/publisher_unavailable/);
    await assert.rejects(()=>google.acknowledge('lifetime','synthetic'),/acknowledgement_unavailable/);
  } finally {globalThis.fetch=original;}
});
