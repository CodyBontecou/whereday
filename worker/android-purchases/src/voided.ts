import {base64url,bytes,decode64,hash,object,text} from './protocol';
import {PurchaseLedger} from './ledger';

const DAY=86400000,LOOKBACK=30*DAY;
export interface VoidedGateway {voided(startMillis:number|null,endMillis:number,cursor:string|null):Promise<unknown>}
export type ScanState={scope:string,last_completed_end:number|null,run_start:number|null,run_end:number|null,run_started_at:number|null,cursor:string|null,
  lease_id:string|null,lease_until:number,last_success_at:number|null,last_error_at:number|null,restarts:number,coverage_gap:number};
export type ScanResult={status:'disabled'|'busy'|'idle'|'more'|'complete',processed:number,coverageGap:boolean|null};

/** The provider cursor is not a purchase proof, but it is opaque and may contain identifiers.
 * Encrypt it with a separate server key and bind it to the exact app/window. */
export class VoidedCursorCipher {
  private constructor(private readonly key:CryptoKey) {}
  static async create(secret:string):Promise<VoidedCursorCipher> {
    if(!/^[A-Za-z0-9_-]{43}=?$/.test(secret)) throw new Error('unconfigured');
    const raw=decode64(secret);if(raw.length!==32) throw new Error('unconfigured');
    return new VoidedCursorCipher(await crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']));
  }
  private context(state:ScanState):Uint8Array {return bytes(`whereday-voided-v1\n${state.scope}\n${state.run_start??'default'}\n${state.run_end}`);}
  async seal(value:string,state:ScanState):Promise<string> {
    const raw=bytes(text(value));if(raw.length>8192) throw new Error('google_voided_cursor_too_large');
    const iv=crypto.getRandomValues(new Uint8Array(12));
    const encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:this.context(state)},this.key,raw);
    return base64url(iv)+'.'+base64url(encrypted);
  }
  async open(value:string,state:ScanState):Promise<string> {
    try {
      const parts=text(value,16384).split('.');if(parts.length!==2) throw new Error();
      const iv=decode64(parts[0]);if(iv.length!==12) throw new Error();
      const decrypted=await crypto.subtle.decrypt({name:'AES-GCM',iv,additionalData:this.context(state)},this.key,decode64(parts[1]));
      return text(new TextDecoder('utf-8',{fatal:true,ignoreBOM:false}).decode(decrypted));
    } catch {throw new Error('voided_cursor_unreadable');}
  }
}

export class VoidedScanStore {
  constructor(private readonly database:D1Database) {}
  async claim(scope:string,now:number):Promise<ScanState|null> {
    return this.database.prepare(`INSERT INTO voided_reconciliation(scope,lease_id,lease_until) VALUES(?,?,?)
      ON CONFLICT(scope) DO UPDATE SET lease_id=excluded.lease_id,lease_until=excluded.lease_until
      WHERE voided_reconciliation.lease_until<=? RETURNING *`).bind(scope,crypto.randomUUID(),now+180,now).first<ScanState>();
  }
  private async changed(query:D1PreparedStatement):Promise<ScanState> {
    const row=await query.first<ScanState>();if(!row) throw new Error('voided_claim_lost');return row;
  }
  async begin(state:ScanState,start:number|null,end:number,now:number,gap:boolean,restart=false):Promise<ScanState> {
    return this.changed(this.database.prepare(`UPDATE voided_reconciliation SET run_start=?,run_end=?,run_started_at=?,cursor=NULL,
      coverage_gap=MAX(coverage_gap,?),restarts=restarts+?,lease_until=? WHERE scope=? AND lease_id=? AND lease_until>? RETURNING *`)
      .bind(start,end,now,gap?1:0,restart?1:0,now+180,state.scope,state.lease_id,now));
  }
  async checkpoint(state:ScanState,ciphertext:string,now:number):Promise<ScanState> {
    return this.changed(this.database.prepare(`UPDATE voided_reconciliation SET cursor=?,lease_until=?
      WHERE scope=? AND lease_id=? AND lease_until>? AND run_end IS NOT NULL RETURNING *`)
      .bind(ciphertext,now+180,state.scope,state.lease_id,now));
  }
  async flagGap(state:ScanState,now:number):Promise<ScanState> {
    return this.changed(this.database.prepare(`UPDATE voided_reconciliation SET coverage_gap=1,lease_until=?
      WHERE scope=? AND lease_id=? AND lease_until>? RETURNING *`).bind(now+180,state.scope,state.lease_id,now));
  }
  async finish(state:ScanState,now:number):Promise<ScanState> {
    return this.changed(this.database.prepare(`UPDATE voided_reconciliation SET last_completed_end=run_end,last_success_at=?,
      run_start=NULL,run_end=NULL,run_started_at=NULL,cursor=NULL,lease_id=NULL,lease_until=0
      WHERE scope=? AND lease_id=? AND lease_until>? AND run_end IS NOT NULL RETURNING *`).bind(now,state.scope,state.lease_id,now));
  }
  async release(state:ScanState,now:number,failed=false):Promise<void> {
    await this.database.prepare(`UPDATE voided_reconciliation SET lease_id=NULL,lease_until=0,last_error_at=CASE WHEN ? THEN ? ELSE last_error_at END
      WHERE scope=? AND lease_id=?`).bind(failed?1:0,now,state.scope,state.lease_id).run();
  }
}

export async function parseVoidedPage(value:unknown,now:number):Promise<{hashes:string[],next:string|null}> {
  const result=object(value),rows=result.voidedPurchases===undefined?[]:result.voidedPurchases;
  if(!Array.isArray(rows) || rows.length>50) throw new Error('google_voided_invalid_page');
  const hashes:string[]=[];
  for(const item of rows) {
    const row=object(item),token=text(row.purchaseToken),time=text(row.voidedTimeMillis,20);
    if((row.kind!==undefined && row.kind!=='androidpublisher#voidedPurchase') || !/^\d+$/.test(time) ||
      !Number.isSafeInteger(Number(time)) || Number(time)>now*1000+300000 ||
      (row.voidedQuantity!==undefined && (!Number.isSafeInteger(row.voidedQuantity) || Number(row.voidedQuantity)<1))) throw new Error('google_voided_invalid_page');
    // API filters use the time Google first sees a record as voided, not this event time.
    // Do not drop a late-discovered refund just because its voidedTimeMillis is old.
    hashes.push(await hash(bytes(token)));
  }
  const pagination=result.tokenPagination===undefined?{}:object(result.tokenPagination);
  const next=pagination.nextPageToken;
  const cursor=next===undefined || next===null || next===''?null:text(next);
  if(cursor!==null && bytes(cursor).length>8192) throw new Error('google_voided_cursor_too_large');
  return {hashes,next:cursor};
}

type ScanDependencies={store:VoidedScanStore,ledger:PurchaseLedger,cipher:VoidedCursorCipher,google:()=>Promise<VoidedGateway>,now:()=>number};
/** Four pages per invocation; progress resumes from the persisted encrypted next cursor. */
export async function reconcileVoided(scope:string,deps:ScanDependencies):Promise<ScanResult> {
  let state=await deps.store.claim(scope,deps.now());
  if(state===null) return {status:'busy',processed:0,coverageGap:null};
  let processed=0;
  try {
    const now=deps.now(),end=now*1000-60000;
    if(state.run_end===null) {
      if(state.last_success_at!==null && now-state.last_success_at<1800) {
        await deps.store.release(state,now);return {status:'idle',processed,coverageGap:state.coverage_gap===1};
      }
      const desired=state.last_completed_end===null?null:state.last_completed_end-DAY;
      // Let Google select its full default window rather than sending an out-of-range start.
      const start=desired!==null && desired>=now*1000-LOOKBACK+60000?desired:null;
      const gap=state.last_completed_end!==null && state.last_completed_end<now*1000-LOOKBACK;
      state=await deps.store.begin(state,start,end,now,gap);
    } else if(state.run_end<now*1000-LOOKBACK && state.cursor!==null) {
      // Pagination ignores the original time bounds. Preserve a potentially valid provider
      // snapshot even when newer uncovered history may have aged out of its pull window.
      state=await deps.store.flagGap(state,now);
    } else if(state.run_end<now*1000-LOOKBACK) {
      state=await deps.store.begin(state,null,end,now,true,true);
    } else if(state.cursor===null && ((state.run_start===null && state.run_started_at!==null && state.run_started_at<now) ||
      (state.run_start!==null && state.run_start<now*1000-LOOKBACK+60000))) {
      // Before the first durable page there is no provider snapshot to resume. Its default
      // window moves with server time, and an explicit start may age out while interrupted.
      // Re-query all currently available records without claiming a lost bootstrap interval.
      const gap=state.last_completed_end===null || state.last_completed_end<now*1000-LOOKBACK;
      state=await deps.store.begin(state,null,end,now,gap,true);
    }
    let cursor=state.cursor===null?null:await deps.cipher.open(state.cursor,state);
    const seen=new Set<string>();if(cursor!==null) seen.add(cursor);
    const google=await deps.google();let restarted=false;
    for(let attempt=0;attempt<4;attempt++) {
      let raw:unknown;
      try {raw=await google.voided(state.run_start,state.run_end!,cursor);}
      catch(error) {
        if(cursor!==null && !restarted && error instanceof Error && error.message==='google_voided_cursor_rejected') {
          // Re-query the available default window after a provider cursor expires. Surface the
          // uncertainty; never advance the old watermark or silently claim complete coverage.
          state=await deps.store.begin(state,null,deps.now()*1000-60000,deps.now(),true,true);
          cursor=null;seen.clear();restarted=true;continue;
        }
        throw error;
      }
      const page=await parseVoidedPage(raw,deps.now());
      if(page.next!==null && seen.has(page.next)) throw new Error('google_voided_cursor_loop');
      await deps.ledger.revokeMany(page.hashes,deps.now());processed+=page.hashes.length;
      if(page.next===null) {
        state=await deps.store.finish(state,deps.now());
        return {status:'complete',processed,coverageGap:state.coverage_gap===1};
      }
      const encrypted=await deps.cipher.seal(page.next,state);
      state=await deps.store.checkpoint(state,encrypted,deps.now());
      cursor=page.next;seen.add(cursor);
    }
    await deps.store.release(state,deps.now());return {status:'more',processed,coverageGap:state.coverage_gap===1};
  } catch(error) {await deps.store.release(state,deps.now(),true);throw error;}
}

export async function runVoidedReconciliation(env:Env,now:()=>number,google:()=>Promise<VoidedGateway>):Promise<ScanResult> {
  if(String(env.VOIDED_RECONCILIATION_ENABLED)!=='true') return {status:'disabled',processed:0,coverageGap:null};
  const cipher=await VoidedCursorCipher.create(env.VOIDED_CURSOR_KEY);
  return reconcileVoided(env.PACKAGE_NAME,{store:new VoidedScanStore(env.PURCHASES),ledger:new PurchaseLedger(env.PURCHASES),cipher,google,now});
}
