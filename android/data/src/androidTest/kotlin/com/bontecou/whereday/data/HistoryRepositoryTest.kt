package com.bontecou.whereday.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {
    private lateinit var db: HistoryDatabase
    private lateinit var repo: HistoryRepository
    private val clock = MutableClock(Instant.parse("2026-10-26T12:00:00Z"))
    private val coordinate = Coordinate(18.0, -66.0)
    @Before fun setup() {
        db=HistoryDatabase.create(InstrumentationRegistry.getInstrumentation().targetContext,null)
        repo=HistoryRepository(db,clock)
    }
    @After fun teardown() { db.close() }
    @Test fun countryReportStreamsAllPagesAndManualEditsInvalidateTotals() = runBlocking {
        val day=clock.now.atZone(ZoneOffset.UTC).toLocalDate()
        repo.dao.putPoints((0 until 1100).map { i -> PointRow(TrackPoint(id="country-$i",coordinate=coordinate,time=clock.now.minusSeconds(i.toLong()),accuracy=5.0,countryCode="PR",timeZone="America/Puerto_Rico"),null) })
        repo.dao.put(PointRow(TrackPoint(id="unresolved",coordinate=coordinate,time=clock.now,accuracy=5.0),null))
        repo.dao.put(PointRow(TrackPoint(id="outlier",coordinate=coordinate,time=clock.now,accuracy=5.0,outlier=true),null))
        val stay=CountryStay(countryCode="PR",firstDay=day,lastDay=day.plusDays(1))
        repo.dao.put(StayRow(stay))
        val initial=repo.observeCountryDays(CountryDayBounds()).first()
        assertEquals(1L,initial.unresolvedPoints)
        assertEquals(2,initial.days.size)
        assertEquals(CountryPresence("PR",day,true,true),initial.days.first())
        repo.dao.put(StayRow(stay.copy(firstDay=day.plusDays(1))))
        val edited=repo.readCountryDays()
        assertFalse(edited.days.first().manual)
        assertEquals(1,edited.stays.size)
        repo.dao.deleteStay(stay.id)
        assertEquals(listOf(CountryPresence("PR",day,true,false)),repo.readCountryDays().days)
        assertEquals(1102L,repo.dao.totalPointCount())
    }
    @Test fun concurrentStartsProduceOneSessionAndStopClosesIt() = runBlocking {
        val starts=(1..20).map { async(Dispatchers.Default) { repo.start(1) } }.awaitAll()
        assertEquals(1,starts.map { it.sessionId }.distinct().size)
        assertEquals(1,repo.dao.openSessionCount())
        clock.now=clock.now.plusSeconds(60)
        repo.stop()
        assertEquals(0,repo.dao.openSessionCount())
        assertFalse(repo.dao.recording()!!.requested)
        assertNotNull(repo.dao.observeSessions().first().single().value.endedAt)
    }
    @Test fun cachedPoorAndDuplicateFixesAreNotStored() = runBlocking {
        repo.start(0)
        val start=clock.now
        clock.now=start.plusSeconds(30)
        val point=TrackPoint(coordinate=coordinate,time=start.plusSeconds(10),accuracy=5.0)
        repo.ingest(listOf(point.copy(id="cached",time=start.minusSeconds(30)),point.copy(id="poor",accuracy=101.0),point,point))
        repo.ingest(listOf(point.copy(id="repeated-time")))
        assertEquals(1,repo.dao.totalPointCount())
        assertEquals(point.id,repo.dao.latestPoints().single().value.id)
    }
    @Test fun dwellAndSavedPlaceStampCommitTogetherAndSurviveStop() = runBlocking {
        val place=SavedPlace(name="Library",coordinate=coordinate,address="Synthetic street")
        repo.dao.put(PlaceRow(place))
        repo.start(0)
        val start=clock.now
        clock.now=start.plusSeconds(360)
        repo.ingest(listOf(TrackPoint(coordinate=coordinate,time=start,accuracy=5.0),TrackPoint(coordinate=coordinate,time=start.plusSeconds(300),accuracy=5.0)))
        val visit=repo.dao.openVisits().single().value
        assertEquals("Library",visit.name)
        assertEquals(Confirmation.confirmed,visit.confirmation)
        assertEquals(visit.id,repo.dao.dwell()!!.state.visitId)
        repo.stop()
        assertTrue(repo.dao.openVisits().isEmpty())
        assertEquals(1,repo.dao.totalVisitCount())
    }
    @Test fun interruptedRecordingClosesAtLastDurableFix() = runBlocking {
        repo.start(0)
        val fix=clock.now.plusSeconds(30)
        clock.now=fix
        repo.ingest(listOf(TrackPoint(coordinate=coordinate,time=fix,accuracy=5.0)))
        clock.now=clock.now.plusSeconds(3600)
        repo.markInterrupted("Restart required")
        assertEquals(fix,repo.dao.observeSessions().first().single().value.endedAt)
        assertFalse(repo.dao.recording()!!.requested)
        assertEquals("Restart required",repo.dao.recording()!!.interruption)
    }
    @Test fun stableInterruptionReasonsSurviveANewRepositoryAndStartingClearsTheOldNotice()=runBlocking {
        val session=repo.start(0).sessionId!!
        val fix=clock.now.plusSeconds(30);clock.now=fix
        repo.ingest(listOf(TrackPoint(coordinate=coordinate,time=fix,accuracy=5.0)))
        clock.now=fix.plusSeconds(3600)
        repo.markInterrupted(RecordingInterruption.phoneRestarted.code)
        val reopened=HistoryRepository(db,clock)
        assertEquals(RecordingInterruption.phoneRestarted,RecordingInterruption.decode(reopened.dao.recording()!!.interruption!!))
        assertEquals(fix,reopened.dao.session(session)!!.value.endedAt)
        assertFalse(reopened.dao.recording()!!.requested);assertEquals(0L,reopened.dao.openSessionCount())
        val next=reopened.start(0)
        assertNotEquals(session,next.sessionId);assertNull(next.interruption)
        reopened.stop(RecordingInterruption.permissionRemoved.code)
        assertEquals(RecordingInterruption.permissionRemoved.code,reopened.dao.recording()!!.interruption)
        assertEquals(0L,reopened.dao.openSessionCount())
    }
    @Test fun oldHistoryKeepsFullTotalsAndUniformPreviewBeyondPointLimit() = runBlocking {
        val start=clock.now.minusSeconds(86400*30)
        val range=HistoryRange(start,start.plusSeconds(6000))
        repeat(12) { batch ->
            repo.dao.putPoints((0 until 500).map { offset ->
                val index=batch*500+offset
                PointRow(TrackPoint(id="p-%05d".format(index),coordinate=Coordinate(18.0+index*0.000001,-66.0),time=start.plusSeconds(index.toLong()),accuracy=5.0),null)
            })
        }
        val old=repo.readWindow(range)
        assertEquals(6000L,old.summary.count)
        assertEquals(2500,old.points.size)
        assertEquals("p-00000",old.points.first().id)
        assertEquals("p-05999",old.points.last().id)
        assertTrue(old.summary.distanceMeters>600)
        assertEquals(0L,repo.readWindow(HistoryRange.day(clock.now.atZone(ZoneOffset.UTC).toLocalDate(),ZoneOffset.UTC)).summary.count)
    }
    @Test fun keysetPaginationDoesNotDropEqualTimestampPoints() = runBlocking {
        repo.dao.putPoints((0 until 1100).map { PointRow(TrackPoint(id="same-%04d".format(it),coordinate=coordinate,time=clock.now,accuracy=5.0),null) })
        val window=repo.readWindow(HistoryRange(clock.now,clock.now.plusSeconds(1)))
        assertEquals(1100L,window.summary.count)
        assertEquals(1100,window.points.map { it.id }.distinct().size)
    }
    @Test fun deletingOutingRemovesTimeWindowPointsButKeepsVisits() = runBlocking {
        val session=repo.start(0)
        val start=clock.now
        clock.now=start.plusSeconds(120)
        repo.ingest(listOf(TrackPoint(coordinate=coordinate,time=start.plusSeconds(20),accuracy=5.0)))
        repo.dao.put(PointRow(TrackPoint(coordinate=coordinate,time=start.plusSeconds(30),accuracy=5.0),null))
        repo.dao.put(PointRow(TrackPoint(coordinate=coordinate,time=start.minusSeconds(10),accuracy=5.0),null))
        repo.dao.put(VisitRow(Visit(coordinate=coordinate,arrivedAt=start.plusSeconds(30),source=VisitSource.manual)))
        repo.stop()
        repo.deleteSession(session.sessionId!!)
        assertEquals(1L,repo.dao.totalPointCount())
        assertEquals(1L,repo.dao.totalVisitCount())
        assertNull(repo.dao.session(session.sessionId!!))
    }
    @Test fun migrationPreservesVersionOneRecords() = runBlocking { migrateFrom(1) }
    @Test fun migrationPreservesVersionTwoRecords() = runBlocking { migrateFrom(2) }
    @Test fun migrationPreservesVersionThreeRecords() = runBlocking { migrateFrom(3) }
    @Test fun migrationPreservesVersionFourRecords() = runBlocking { migrateFrom(4) }
    @Test fun migrationPreservesVersionFiveRecords() = runBlocking { migrateFrom(5) }
    @Test fun migrationPreservesVersionSixRecords() = runBlocking { migrateFrom(6) }
    @Test fun migrationPreservesVersionSevenRecords() = runBlocking { migrateFrom(7) }
    @Test fun migrationPreservesVersionEightRecords() = runBlocking { migrateFrom(8) }
    @Test fun migrationPreservesVersionNineRecords() = runBlocking { migrateFrom(9) }
    @Test fun migrationPreservesVersionTenRecords() = runBlocking { migrateFrom(10) }
    @Test fun migrationPreservesVersionElevenRecords() = runBlocking { migrateFrom(11) }
    private suspend fun migrateFrom(version: Int) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val name="migration-${java.util.UUID.randomUUID()}.db"
        val file=context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        try {
            val schema=org.json.JSONObject(instrumentation.context.assets.open("com.bontecou.whereday.data.HistoryDatabase/$version.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val entity=entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                    val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                }
                val views=schema.optJSONArray("views") ?: org.json.JSONArray()
                for(i in 0 until views.length()) {val view=views.getJSONObject(i);old.execSQL(view.getString("createSql").replace("\${VIEW_NAME}",view.getString("viewName")))}
                val setup=schema.getJSONArray("setupQueries")
                for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO points(value_id,value_coordinate,value_time,value_accuracy,value_outlier) VALUES('synthetic','18.0|-66.0',1000,5.0,0)")
                old.execSQL("INSERT INTO visits(value_id,value_coordinate,value_arrivedAt,value_source,value_confirmation,value_departureInferred,value_name,value_countryCode,value_timeZone) VALUES('synthetic-visit','18.0|-66.0',1000,'manual','corrected',1,'Preserved visit','PR','America/Puerto_Rico')")
                old.execSQL("INSERT INTO visits(value_id,value_coordinate,value_arrivedAt,value_source,value_confirmation,value_departureInferred) VALUES('detector-visit','18.0|-66.0',1000,'automatic','unconfirmed',0)")
                old.execSQL("INSERT INTO dwell(id,state_anchor,state_candidateSince,state_lastTime,state_visitId,state_departureSince) VALUES(1,'18.0|-66.0',1000,5000,'detector-visit',4000)")
                old.execSQL("INSERT INTO photo_moments(uri,capturedAt,coordinate,coordinateSource,lastSyncedAt) VALUES('content://synthetic/legacy',1000,'18.0|-66.0','photo_gps',2000)")
                if(version>=5) old.execSQL("INSERT INTO photo_assets(uri,capturedAt,gps,selected,available,lastCheckedAt) VALUES('content://synthetic/legacy',1000,'18.0|-66.0',1,0,2000)")
                if(version>=8) {
                    old.execSQL("INSERT INTO webhook_policy(id,revision,enabled,capture,includeOutliers,createdAt,lastSuccess,lastStatus) VALUES(1,'00000000-0000-0000-0000-000000000001',1,1,0,1000,1500,503)")
                    old.execSQL("INSERT INTO webhook_batches(id,revision,createdAt,pointCount,kind,visitCount,bodyId,bodyFormat,bodyBytes,bodySha256,attempts,nextAttempt,stopped,attemptToken,leaseUntil,lastStatus) VALUES('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',2000,1,'points',0,'00000000-0000-0000-0000-000000000003','json',123,'${"a".repeat(64)}',2,NULL,0,'preserved-attempt',5000,503)")
                    old.execSQL("INSERT INTO webhook_points(revision,value_id,value_coordinate,value_time,value_accuracy,value_outlier,queuedAt,batchId) VALUES('00000000-0000-0000-0000-000000000001','queued-synthetic','18.0|-66.0',1000,5.0,0,2000,'00000000-0000-0000-0000-000000000002')")
                }
                old.execSQL("INSERT INTO recording(id,requested,sessionId) VALUES(1,1,'old-session')")
                if(version>=11) old.execSQL("INSERT INTO visit_monitoring(id,requested,generation,startedAt) VALUES(1,1,'old-lease',1000)")
                old.version=version
            }
            val migrated=HistoryDatabase.create(context,name)
            try {
                val recording=migrated.history().recording()!!
                assertTrue(recording.requested);assertEquals("old-session",recording.sessionId);assertNull(recording.startedBootCount)
                if(version>=11) {
                    val monitor=migrated.history().visitMonitoring()!!
                    assertTrue(monitor.requested);assertEquals("old-lease",monitor.generation);assertNull(monitor.startedBootCount)
                }
                if(version<8) assertNull(migrated.webhooks().policy())
                else {
                    val policy=migrated.webhooks().policy()!!;assertTrue(policy.enabled);assertTrue(policy.capture);assertEquals(503,policy.lastStatus);assertEquals(Instant.ofEpochMilli(1500),policy.lastSuccess)
                    val queued=migrated.webhooks().head(policy.revision)!!;assertEquals(2,queued.attempts);assertEquals("preserved-attempt",queued.attemptToken);assertEquals(Instant.ofEpochMilli(5000),queued.leaseUntil)
                    assertEquals("a".repeat(64),queued.bodySha256);assertEquals(123L,queued.bodyBytes);assertTrue(migrated.webhooks().referencesBody(queued.bodyId!!))
                    assertEquals("queued-synthetic",migrated.webhooks().firstBatchPage(queued.id).single().value.id)
                }
                if(version<11) assertNull(migrated.history().visitMonitoring())
                assertEquals(0L,migrated.webhooks().batchCount("unconfigured"))
                assertEquals(1L,migrated.history().totalPointCount()); assertEquals("synthetic",migrated.history().latestPoints().single().value.id)
                val visit=migrated.history().visit("synthetic-visit")!!.value
                assertEquals("Preserved visit",visit.name); assertEquals(Confirmation.corrected,visit.confirmation)
                assertEquals("PR",visit.countryCode); assertEquals("America/Puerto_Rico",visit.timeZone); assertTrue(visit.departureInferred)
                assertNull(visit.countryResolutionAttemptedAt)
                val detector=migrated.history().dwell()!!.state
                assertEquals(DwellState(Coordinate(18.0,-66.0),Instant.ofEpochMilli(1000),Instant.ofEpochMilli(5000),"detector-visit",Instant.ofEpochMilli(4000)),detector)
                assertNull(detector.nextAnchor);assertNull(detector.nextCandidateSince)
                assertNotNull(migrated.history().photoMoment("content://synthetic/legacy"))
                assertEquals(0L,migrated.history().photoCount(Instant.ofEpochMilli(0),Instant.ofEpochMilli(3000)))
                if(version<5) assertTrue(migrated.history().photoAssetPage().isEmpty())
                else {val asset=migrated.history().photoAsset("content://synthetic/legacy")!!;assertTrue(asset.selected);assertFalse(asset.available);assertEquals(Instant.ofEpochMilli(1000),asset.capturedAt)}
            }
            finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun inferredDetailAndDeletionKeepExactSessionPointsAndVisits() = runBlocking {
        val start=clock.now
        val exact=RecordingSession(startedAt=start.plusSeconds(20),endedAt=start.plusSeconds(40))
        repo.dao.put(SessionRow(exact))
        repo.dao.putPoints(listOf(0L,10,30,50,60).map { seconds -> PointRow(TrackPoint(coordinate=coordinate,time=start.plusSeconds(seconds),accuracy=5.0),null) })
        repo.dao.put(VisitRow(Visit(coordinate=coordinate,arrivedAt=start)))
        val header=repo.readWindow(HistoryRange(start,start.plusSeconds(61)),inference=InferenceRules()).inferred.single()
        val detail=repo.inferredDetail(header)
        assertTrue(detail.inferred)
        assertEquals(4L,detail.summary.count)
        assertEquals(4,detail.points.size)
        repo.deleteInferred(header)
        assertEquals(1L,repo.dao.totalPointCount())
        assertNotNull(repo.dao.session(exact.id))
        assertEquals(1L,repo.dao.totalVisitCount())
    }
    private class MutableClock(var now: Instant): Clock() {
        override fun getZone(): ZoneId=ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock=this
        override fun instant()=now
    }
}
