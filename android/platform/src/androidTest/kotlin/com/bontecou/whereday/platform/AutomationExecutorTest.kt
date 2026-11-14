package com.bontecou.whereday.platform

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class AutomationExecutorTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    private lateinit var db:HistoryDatabase
    private lateinit var access:AutomationAccess
    private lateinit var results:AutomationResults
    private lateinit var files:ExportFiles
    private lateinit var executor:AutomationExecutor
    private val clock=Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"),ZoneOffset.UTC)
    private val at=clock.instant();private val coordinate=Coordinate(0.0,0.0)
    private val identity=AutomationIdentity("com.example.client",1234,1L,setOf("b".repeat(64)))
    private var paid=true;private var gateCalls=0;private var revokeAt:Int?=null
    private var starts=0;private var stops=0;private var runtime=AutomationRecordingStatus(false,0)
    private var locations=0;private var location:suspend ()->Coordinate={coordinate}
    private fun requirePaid() {gateCalls++;if(gateCalls==revokeAt) paid=false;if(!paid) throw AutomationExportAccessRequired()}
    @Before fun setup()=runBlocking {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        root=File(context.cacheDir,"executor-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        db=HistoryDatabase.create(context,null)
        access=AutomationAccess(File(root,"access")) {identity}
        access.approve(identity,AutomationScope.entries.toSet())
        results=AutomationResults(File(root,"results"),access,{clock.millis()},{1000},{1},authorizeExport={requirePaid()})
        files=ExportFiles(context,db) {requirePaid()}
        executor=AutomationExecutor(JournalCommands(HistoryRepository(db,clock),clock),results,object:AutomationRecording {
            override suspend fun status()=runtime
            override suspend fun start() {starts++;runtime=AutomationRecordingStatus(true,42)}
            override suspend fun stop() {stops++;runtime=AutomationRecordingStatus(false,0)}
        },files,{null},clock,{ZoneOffset.UTC},{java.util.Locale.US},currentLocation={locations++;location()})
    }
    @After fun cleanup():Unit=runBlocking {access.revoke();results.clear();files.clearCache();db.close();root.deleteRecursively()}
    private fun request(command:String,extra:String="")=AutomationRequest.decode("{\"version\":1,\"command\":\"$command\"$extra}")
    private suspend fun json(request:AutomationRequest)=executor.execute(identity.packageName,request).let {result ->
        ParcelFileDescriptor.AutoCloseInputStream(results.open(identity.packageName,result.id)).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
    }
    private suspend inline fun <reified T:Throwable> fails(noinline operation:suspend ()->Unit) {
        try {operation();fail("Expected ${T::class.java.simpleName}")} catch(e:Throwable) {if(e !is T) throw e}
    }
    @Test fun readResultsStreamEveryEntityAndExposeOnlyTheReadContractFields()=runBlocking {
        db.writeTransaction {repeat(1201) {i ->
            val id="fixture-%04d".format(i)
            db.history().put(VisitRow(Visit(id,coordinate,at,name="Place $i",address="Synthetic address",notes="Private notes")))
            db.history().put(SessionRow(RecordingSession(id,at,at.plusSeconds(10),"Outing $i",notes="Private notes")))
        }}
        val visits=json(request("visits"))["data"].asJsonArray
        val outings=json(request("outings"))["data"].asJsonArray
        assertEquals(1201,visits.size());assertEquals(1201,outings.size())
        assertEquals("fixture-1200",visits.last().asJsonObject["id"].asString)
        assertEquals(setOf("id","name","address","arrivedAt","departedAt"),visits.first().asJsonObject.keySet())
        assertEquals(setOf("id","name","startedAt","endedAt"),outings.first().asJsonObject.keySet())
        assertFalse(visits.toString().contains("Private notes"));assertFalse(outings.toString().contains("Private notes"))
    }
    @Test fun statisticsUseFullPointTotalsAndActualRuntimeRatherThanPointTimeSpan()=runBlocking {
        db.history().putPoints((0..1200).map {i ->PointRow(TrackPoint("p-%04d".format(i),Coordinate(0.0,i*.000001),at,5.0,outlier=i==600),null)})
        runtime=AutomationRecordingStatus(true,42)
        val data=json(request("statistics"))["data"].asJsonObject
        assertEquals(1201L,data["pointCount"].asLong);assertEquals(1L,data["outlierCount"].asLong)
        assertEquals(42L,data["currentRecordingSeconds"].asLong);assertTrue(data["recordingActive"].asBoolean)
        assertEquals(coordinate.distanceTo(Coordinate(0.0,.0012)),data["distanceMeters"].asDouble,.001)
        runtime=AutomationRecordingStatus(false,0)
        assertEquals(0L,json(request("statistics"))["data"].asJsonObject["currentRecordingSeconds"].asLong)
    }
    @Test fun currentAndRenameResponsesUseCommittedValuesAndPreserveUnrelatedFields()=runBlocking {
        val visit=Visit("visit",coordinate,at.minusSeconds(10),name="Before",notes="Keep",detectedName="Detected")
        val outing=RecordingSession("outing",at.minusSeconds(10),name="Before",notes="Keep outing")
        db.history().put(VisitRow(visit));db.history().put(SessionRow(outing))
        assertEquals("Before",json(request("currentOuting"))["data"].asJsonObject["name"].asString)
        assertEquals("Visit renamed",json(request("renameVisit",",\"id\":\"visit\",\"name\":\" Visit renamed \""))["data"].asJsonObject["name"].asString)
        assertEquals(visit.copy(name="Visit renamed",updatedAt=at),db.history().visit("visit")!!.value)
        assertEquals("Walk",json(request("renameOuting",",\"id\":\"outing\",\"name\":\"Walk\""))["data"].asJsonObject["name"].asString)
        assertEquals("Run",json(request("renameCurrentOuting",",\"name\":\"Run\""))["data"].asJsonObject["name"].asString)
        assertEquals(outing.copy(name="Run"),db.history().session("outing")!!.value)
    }
    @Test fun unauthorizedRequestsNeverInvokeRecordingOrRename()=runBlocking {
        access.approve(identity,setOf(AutomationScope.summary))
        fails<AutomationNotAuthorized> {executor.execute(identity.packageName,request("startRecording"));Unit}
        fails<AutomationNotAuthorized> {executor.execute(null,request("stopRecording"));Unit}
        fails<AutomationNotAuthorized> {executor.execute(identity.packageName,request("renameVisit",",\"id\":\"missing\",\"name\":\"Name\""));Unit}
        assertEquals(0,starts);assertEquals(0,stops)
    }
    @Test fun recordingDispatchReportsAcceptanceAndTheRuntimeBindingResult()=runBlocking {
        assertTrue(json(request("startRecording"))["data"].asJsonObject["recordingActive"].asBoolean)
        val stopped=json(request("stopRecording"))["data"].asJsonObject
        assertTrue(stopped["requestAccepted"].asBoolean);assertFalse(stopped["recordingActive"].asBoolean)
        assertEquals(1,starts);assertEquals(1,stops)
    }
    @Test fun manualCommandsUseCommittedSavedOrCoordinateValuesWithoutPaidAccessOrGps()=runBlocking {
        paid=false
        val place=SavedPlace("library","Library",Coordinate(18.0,-66.0),"Synthetic address")
        db.history().put(PlaceRow(place))
        val places=json(request("savedPlaces"))["data"].asJsonArray
        assertEquals(setOf("id","name"),places.single().asJsonObject.keySet())
        assertFalse(places.toString().contains("Synthetic address"))
        val checked=json(request("checkIn",",\"savedPlaceId\":\"library\",\"arrivedAt\":\"${at.minusSeconds(60)}\""))["data"].asJsonObject
        val id=checked["id"].asString
        assertEquals("Library",checked["name"].asString);assertTrue(checked["departedAt"].isJsonNull)
        assertEquals(place.coordinate,db.history().visit(id)!!.value.coordinate)
        val closed=json(request("checkOut"))["data"].asJsonObject
        assertEquals(id,closed["id"].asString);assertEquals(at.toString(),closed["departedAt"].asString)
        val other=json(request("checkIn",",\"name\":\"Cafe\",\"latitude\":0,\"longitude\":0"))["data"].asJsonObject
        assertEquals("Cafe",other["name"].asString)
        assertEquals(0,locations);assertEquals(0,gateCalls);assertEquals(0,starts);assertEquals(0,stops)
    }
    @Test fun openManualQueryExcludesAutomaticAndClosedVisitsAndKeepsReadFieldsMinimal()=runBlocking {
        val manual=Visit("manual",coordinate,at,source=VisitSource.manual,notes="Private notes")
        db.history().put(VisitRow(manual));db.history().put(VisitRow(manual.copy(id="closed",departedAt=at)))
        db.history().put(VisitRow(manual.copy(id="automatic",source=VisitSource.automatic)))
        val values=json(request("openManualVisits"))["data"].asJsonArray
        assertEquals(1,values.size());assertEquals("manual",values.single().asJsonObject["id"].asString)
        assertEquals(setOf("id","name","address","arrivedAt","departedAt"),values.single().asJsonObject.keySet())
    }
    @Test fun oldReadRenameOrRecordingApprovalsCannotReadPlacesOrMutateManualVisits()=runBlocking {
        access.approve(identity,setOf(AutomationScope.summary,AutomationScope.visits,AutomationScope.renameVisits,AutomationScope.recording))
        fails<AutomationNotAuthorized> {executor.execute(identity.packageName,request("checkIn",",\"name\":\"Cafe\""));Unit}
        fails<AutomationNotAuthorized> {executor.execute(identity.packageName,request("checkOut"));Unit}
        fails<AutomationNotAuthorized> {executor.execute(identity.packageName,request("savedPlaces"));Unit}
        assertEquals(0,locations);assertEquals(0L,db.history().totalVisitCount())
    }
    @Test fun currentCheckInUsesOneBoundedLocatorAndFutureArrivalNeverRequestsIt()=runBlocking {
        paid=false
        val data=json(request("checkIn",",\"name\":\"Cafe\""))["data"].asJsonObject
        assertEquals(1,locations);assertEquals(coordinate,db.history().visit(data["id"].asString)!!.value.coordinate)
        fails<IllegalArgumentException> {json(request("checkIn",",\"name\":\"Future\",\"arrivedAt\":\"${at.plusNanos(1)}\""));Unit}
        assertEquals(1,locations);assertEquals(1L,db.history().totalVisitCount());assertEquals(0,gateCalls)
    }
    @Test fun revocationDuringCurrentLocationCancelsBeforeTheManualCommit()=runBlocking {
        val started=CompletableDeferred<Unit>()
        location={started.complete(Unit);awaitCancellation()}
        val pending=async {executor.execute(identity.packageName,request("checkIn",",\"name\":\"Cafe\""))}
        started.await();access.revoke();pending.join()
        assertTrue(pending.isCancelled);assertEquals(0L,db.history().totalVisitCount())
        assertEquals(0,File(root,"results").listFiles().orEmpty().size)
    }
    private fun exportRequest():AutomationRequest {
        val selection=ExportSelection(format=ExportFormat.json,additionalFormats=ExportFormat.entries.toSet()-ExportFormat.json,
            options=ExportOptions(kind=DataKind.points,from=at.minusSeconds(1),until=at.plusSeconds(86401),zone=ZoneOffset.UTC,excludeOutliers=true),
            filename="{year}/{date}/{type}",splitDays=true)
        return request("export",",\"selection\":${ExportSelectionCodec.encode(selection)}")
    }
    private suspend fun seedExport() {
        db.history().putPoints(listOf(
            PointRow(TrackPoint("first",coordinate,at,5.0),null),
            PointRow(TrackPoint("next",Coordinate(0.0,.001),at.plusSeconds(86400),5.0),null),
            PointRow(TrackPoint("outlier",Coordinate(0.0,.5),at,5.0,outlier=true),null)))
    }
    @Test fun paidExportZipPreservesEveryFormatDayAndSubfolderAndRechecksAccessOnOpen()=runBlocking {
        seedExport()
        val result=executor.execute(identity.packageName,exportRequest())
        assertEquals("application/zip",result.mime)
        val entries=linkedMapOf<String,String>()
        ZipInputStream(ParcelFileDescriptor.AutoCloseInputStream(results.open(identity.packageName,result.id))).use {zip ->
            while(true) {val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes().toString(Charsets.UTF_8)}
        }
        assertEquals(16,entries.size)
        assertTrue(entries.keys.all {it.startsWith("2026/2026-10-") && !it.contains("..")})
        val json=entries.filterKeys {it.endsWith("points.json")}
        assertEquals(2,json.size)
        for(value in json.values) assertEquals(1,JsonParser.parseString(value).asJsonObject["points"].asJsonArray.size())
        assertTrue(gateCalls>=4)
        paid=false
        fails<AutomationExportAccessRequired> {results.open(identity.packageName,result.id);Unit}
    }
    @Test fun unpaidOrMidPreparationRevocationLeavesNoAutomationResult()=runBlocking {
        seedExport();paid=false
        fails<AutomationExportAccessRequired> {executor.execute(identity.packageName,exportRequest());Unit}
        assertTrue(File(root,"results").listFiles().orEmpty().isEmpty())
        for(stage in listOf(3,4,5)) {
            paid=true;gateCalls=0;revokeAt=stage
            fails<AutomationExportAccessRequired> {executor.execute(identity.packageName,exportRequest());Unit}
            assertTrue(gateCalls>=stage);assertTrue(File(root,"results").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun preparedReadRejectsChangedBytesBeforeTheConsumerSeesThem()=runBlocking {
        seedExport()
        val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json),ExportOptions(kind=DataKind.points,from=at,until=at.plusSeconds(1))))
        val file=prepared.files.single().file
        val bytes=file.readBytes();bytes[bytes.lastIndex]='X'.code.toByte();file.writeBytes(bytes)
        var consumed=false
        fails<IllegalStateException> {files.readPrepared(prepared) {_,_ ->consumed=true}}
        assertFalse(consumed)
        files.discard(prepared)
    }
    @Test fun clearingPreparedExportsFencesAnInProgressReadBeforePublication()=runBlocking {supervisorScope {
        seedExport()
        val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json),ExportOptions(kind=DataKind.points,from=at,until=at.plusSeconds(1))))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val read=async {runCatching {files.readPrepared(prepared) {_,_ ->entered.complete(Unit);release.await()}}}
        entered.await()
        val clear=async(start=CoroutineStart.UNDISPATCHED) {files.clearCache()}
        release.complete(Unit)
        assertTrue(read.await().exceptionOrNull() is CancellationException)
        clear.await()
        assertFalse(prepared.files.single().file.exists())
    }}

}
