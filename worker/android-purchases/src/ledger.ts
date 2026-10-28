export type Challenge={nonce:string,device_hash:string,expires_at:number,consumed_at:number|null};
export class PurchaseRevoked extends Error {constructor() {super('purchase_revoked');}}
export interface Ledger {
  challenge(id:string):Promise<Challenge|null>;
  consume(id:string,deviceHash:string,now:number):Promise<boolean>;
  grant(tokenHash:string,productId:string,now:number):Promise<string>;
  revoke(tokenHash:string,now:number):Promise<void>;
  isRevoked(tokenHash:string):Promise<boolean>;
}

export class PurchaseLedger implements Ledger {
  constructor(private readonly database:D1Database) {}
  async challenge(id:string):Promise<Challenge|null> {
    return this.database.prepare('SELECT nonce,device_hash,expires_at,consumed_at FROM challenges WHERE id=?').bind(id).first<Challenge>();
  }
  async consume(id:string,deviceHash:string,now:number):Promise<boolean> {
    const row=await this.database.prepare('UPDATE challenges SET consumed_at=? WHERE id=? AND device_hash=? AND consumed_at IS NULL AND expires_at>? RETURNING id').bind(now,id,deviceHash,now).first();
    return row!==null;
  }
  async grant(tokenHash:string,productId:string,now:number):Promise<string> {
    const row=await this.database.prepare(`INSERT INTO grants(token_hash,grant_id,product_id,verified_at,state)
      SELECT ?,?,?,?,'active' WHERE NOT EXISTS(SELECT 1 FROM revocations WHERE token_hash=? )
      ON CONFLICT(token_hash) DO UPDATE SET verified_at=excluded.verified_at,state='active'
      WHERE grants.state='active' AND grants.product_id=excluded.product_id AND NOT EXISTS(SELECT 1 FROM revocations WHERE token_hash=excluded.token_hash)
      RETURNING grant_id`).bind(tokenHash,crypto.randomUUID(),productId,now,tokenHash).first<{grant_id:string}>();
    if(!row) {if(await this.isRevoked(tokenHash)) throw new PurchaseRevoked();throw new Error('grant_conflict');}
    return row.grant_id;
  }
  async revoke(tokenHash:string,now:number,source:'verification'|'rtdn'|'voided'='verification'):Promise<void> {
    await this.database.batch([
      this.database.prepare('INSERT OR IGNORE INTO revocations(token_hash,revoked_at,source) VALUES(?,?,?)').bind(tokenHash,now,source),
      this.database.prepare("UPDATE grants SET state='revoked',verified_at=? WHERE token_hash=?").bind(now,tokenHash)
    ]);
  }
  async isRevoked(tokenHash:string):Promise<boolean> {
    return await this.database.prepare("SELECT 1 FROM revocations WHERE token_hash=? UNION ALL SELECT 1 FROM grants WHERE token_hash=? AND state='revoked' LIMIT 1").bind(tokenHash,tokenHash).first()!==null;
  }
  /** A page may replay after interruption. Each small chunk atomically records terminal
   * fences and updates existing grants; a failed later chunk never advances the scan. */
  async revokeMany(tokenHashes:string[],now:number):Promise<void> {
    const unique=[...new Set(tokenHashes)];
    for(let index=0;index<unique.length;index+=25) {
      const chunk=unique.slice(index,index+25);
      await this.database.batch([
        this.database.prepare(`INSERT OR IGNORE INTO revocations(token_hash,revoked_at,source) VALUES ${chunk.map(()=>"(?,?,'voided')").join(',')}`)
          .bind(...chunk.flatMap(value=>[value,now])),
        this.database.prepare(`UPDATE grants SET state='revoked',verified_at=? WHERE token_hash IN (${chunk.map(()=>'?').join(',')})`)
          .bind(now,...chunk)
      ]);
    }
  }
  async create(deviceHash:string,now:number):Promise<{challengeId:string,nonce:string}> {
    const challengeId=crypto.randomUUID(),nonce=crypto.randomUUID();
    await this.database.prepare('INSERT INTO challenges(id,nonce,device_hash,expires_at) VALUES(?,?,?,?)').bind(challengeId,nonce,deviceHash,now+120).run();
    return {challengeId,nonce};
  }
  async rate(bucket:string,now:number,limit:number):Promise<boolean> {
    const row=await this.database.prepare(`INSERT INTO rate_windows(bucket,count,expires_at) VALUES(?,1,?)
      ON CONFLICT(bucket) DO UPDATE SET count=count+1 RETURNING count`).bind(bucket,now+120).first<{count:number}>();
    return row!==null && row.count<=limit;
  }
  async cleanup(now:number):Promise<void> {
    await this.database.batch([
      this.database.prepare('DELETE FROM challenges WHERE expires_at<?').bind(now),
      this.database.prepare('DELETE FROM rate_windows WHERE expires_at<?').bind(now),
      this.database.prepare('DELETE FROM purchase_notifications WHERE expires_at<?').bind(now)
    ]);
  }
}
