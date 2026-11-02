package com.bontecou.whereday.data

import androidx.room.*
import com.bontecou.whereday.domain.TrackPoint
import java.time.Instant

@Entity(tableName="webhook_policy")
data class WebhookPolicyRow(@PrimaryKey val id:Int=1,val revision:String,val enabled:Boolean,val capture:Boolean,val includeOutliers:Boolean,val createdAt:Instant,val lastSuccess:Instant?=null,val lastStatus:Int?=null)
@Entity(tableName="webhook_batches",indices=[Index(value=["revision","createdAt","id"]),Index(value=["bodyId"])])
data class WebhookBatchRow(@PrimaryKey val id:String,val revision:String,val createdAt:Instant,val pointCount:Long,val kind:String="points",val visitCount:Long=0,
    val bodyId:String?=null,val bodyFormat:String?=null,val bodyBytes:Long?=null,val bodySha256:String?=null,
    val attempts:Int=0,val nextAttempt:Instant?=null,val stopped:Boolean=false,val attemptToken:String?=null,val leaseUntil:Instant?=null,val lastStatus:Int?=null)
@Entity(tableName="webhook_points",primaryKeys=["revision","value_id"],indices=[Index(value=["revision","batchId","value_time","value_id"]),Index(value=["batchId","value_time","value_id"])],
    foreignKeys=[ForeignKey(entity=WebhookBatchRow::class,parentColumns=["id"],childColumns=["batchId"],onDelete=ForeignKey.CASCADE)])
data class WebhookPointRow(val revision:String,@Embedded(prefix="value_") val value:TrackPoint,val queuedAt:Instant,val batchId:String?=null)

@Dao
interface WebhookQueueDao {
    @Query("SELECT * FROM webhook_policy WHERE id=1") suspend fun policy():WebhookPolicyRow?
    @Upsert suspend fun put(row:WebhookPolicyRow)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun capture(row:WebhookPointRow):Long
    @Query("UPDATE webhook_points SET value_outlier=1 WHERE revision=:revision AND value_id=:id AND batchId IS NULL") suspend fun flagPending(revision:String,id:String)
    @Query("DELETE FROM webhook_points WHERE revision=:revision AND value_id=:id AND batchId IS NULL") suspend fun removePending(revision:String,id:String)
    @Query("DELETE FROM webhook_points WHERE revision=:revision AND batchId IS NULL AND value_outlier=1") suspend fun removePendingOutliers(revision:String)
    @Query("SELECT COUNT(*) FROM webhook_points WHERE revision=:revision") suspend fun pointCount(revision:String):Long
    @Query("SELECT COUNT(*) FROM webhook_points WHERE revision=:revision AND batchId IS NULL AND (:until IS NULL OR queuedAt<=:until)") suspend fun unclaimedCount(revision:String,until:Instant?):Long
    @Query("SELECT MIN(queuedAt) FROM webhook_points WHERE revision=:revision AND batchId IS NULL") suspend fun oldestQueuedAt(revision:String):Instant?
    @Query("SELECT * FROM webhook_points WHERE revision=:revision AND batchId IS NULL AND (:until IS NULL OR queuedAt<=:until) ORDER BY value_time,value_id LIMIT :limit") suspend fun unclaimed(revision:String,until:Instant?,limit:Int):List<WebhookPointRow>
    @Query("UPDATE webhook_points SET batchId=:batch WHERE revision=:revision AND value_id IN (:ids) AND batchId IS NULL") suspend fun assign(revision:String,ids:List<String>,batch:String):Int
    @Query("SELECT * FROM webhook_points WHERE batchId=:batch ORDER BY value_time,value_id LIMIT 500") suspend fun firstBatchPage(batch:String):List<WebhookPointRow>
    @Query("SELECT * FROM webhook_points WHERE batchId=:batch AND (value_time,value_id)>(:after,:id) ORDER BY value_time,value_id LIMIT 500") suspend fun nextBatchPage(batch:String,after:Instant,id:String):List<WebhookPointRow>
    @Query("SELECT * FROM webhook_batches WHERE id=:id") suspend fun batch(id:String):WebhookBatchRow?
    @Query("SELECT * FROM webhook_batches WHERE revision=:revision ORDER BY createdAt,id LIMIT 1") suspend fun head(revision:String):WebhookBatchRow?
    @Query("SELECT * FROM webhook_batches WHERE revision=:revision AND kind IN ('manual','flush') ORDER BY createdAt,id LIMIT 1") suspend fun explicitHead(revision:String):WebhookBatchRow?
    @Query("SELECT COUNT(*) FROM webhook_batches WHERE revision=:revision") suspend fun batchCount(revision:String):Long
    @Query("SELECT MAX(createdAt) FROM webhook_batches WHERE revision=:revision") suspend fun latestBatchAt(revision:String):Instant?
    @Upsert suspend fun put(row:WebhookBatchRow)
    @Query("SELECT COUNT(*) FROM webhook_points WHERE batchId=:batch") suspend fun batchPointCount(batch:String):Long
    @Query("UPDATE webhook_points SET batchId=:batch WHERE revision=:revision AND batchId IS NULL AND queuedAt<=:until") suspend fun assignWindow(revision:String,until:Instant,batch:String)
    @Query("UPDATE webhook_batches SET kind='flush' WHERE revision=:revision AND kind='points'") suspend fun requestFlush(revision:String)
    @Query("SELECT EXISTS(SELECT 1 FROM webhook_batches WHERE bodyId=:body)") suspend fun referencesBody(body:String):Boolean
    @Query("UPDATE webhook_batches SET attemptToken=NULL,leaseUntil=NULL,lastStatus=0,nextAttempt=CASE WHEN attempts<:maximum THEN :next ELSE NULL END,stopped=CASE WHEN attempts>=:maximum THEN 1 ELSE 0 END WHERE revision=:revision AND attemptToken IS NOT NULL") suspend fun recoverLeases(revision:String,next:Instant,maximum:Int):Int
    @Query("DELETE FROM webhook_batches WHERE id=:id") suspend fun deleteBatch(id:String)
    @Query("SELECT COUNT(*) FROM webhook_points") suspend fun totalPoints():Long
    @Query("SELECT COUNT(*) FROM webhook_batches") suspend fun totalBatches():Long
    @Query("SELECT COUNT(*) FROM webhook_outbox") suspend fun legacyCount():Long
    @Query("UPDATE webhook_points SET revision=:next WHERE revision=:old") suspend fun transferPoints(old:String,next:String)
    @Query("UPDATE webhook_batches SET revision=:next,attemptToken=NULL,leaseUntil=NULL WHERE revision=:old") suspend fun transferBatches(old:String,next:String)
    @Query("DELETE FROM webhook_batches") suspend fun clearBatches()
    @Query("DELETE FROM webhook_points") suspend fun clearPoints()
    @Query("DELETE FROM webhook_outbox") suspend fun clearLegacy()
}
