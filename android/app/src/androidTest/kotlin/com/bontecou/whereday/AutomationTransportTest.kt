package com.bontecou.whereday

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationTransportTest {
    @get:Rule val activity=ActivityScenarioRule(MainActivity::class.java)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get()=AppGraph.from(context)
    @Before fun setup():Unit=runBlocking {
        check(context.packageName=="com.bontecou.whereday.mapqa")
        check(graph.automationAccess.installed(CLIENT)!=null) {"Install the separate automation-client debug APK before running this test."}
        graph.preferences.update {it.copy(onboarded=true)}
        graph.automationController.clearAll();graph.recordingCommands.stop()
        graph.webhookController.clearAll {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        graph.history.dao.put(PointRow(TrackPoint("automation-transport-fixture",Coordinate(0.0,0.0),Instant.now(),5.0),null))
    }
    @After fun cleanup():Unit=runBlocking {
        graph.automationController.clearAll()
        graph.webhookController.clearAll {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        context.startActivity(Intent().setClassName(CLIENT,"$CLIENT.ClientActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("mode","close").putExtra("token",UUID.randomUUID().toString()))
    }
    private suspend fun call(request:String="{\"version\":1,\"command\":\"statistics\"}",mode:String?=null,uri:String?=null):JSONObject {
        val token=UUID.randomUUID().toString()
        context.startActivity(Intent().setClassName(CLIENT,"$CLIENT.ClientActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("token",token).putExtra("request",request).putExtra("mode",mode).putExtra("uri",uri))
        return withTimeout(20_000) {
            var receipt:String?=null
            while(receipt==null) {
                context.contentResolver.query(Uri.parse("content://$CLIENT.receipts/$token"),null,null,null,null)?.use {if(it.moveToFirst()) receipt=it.getString(0)}
                if(receipt==null) delay(25)
            }
            JSONObject(requireNotNull(receipt))
        }
    }
    private suspend fun approve(vararg scopes:AutomationScope) {
        val identity=requireNotNull(graph.automationAccess.installed(CLIENT))
        assertNotEquals(context.applicationInfo.uid,identity.uid)
        graph.automationAccess.approve(identity,scopes.toSet())
    }
    @Test fun unapprovedForeignAppCannotSpoofAutomateThroughAnExtra():Unit=runBlocking {
        val reply=call()
        assertEquals(0,reply.getInt("code"));assertEquals("not_authorized",reply.getString("error"))
        assertFalse(reply.has("uri"));assertFalse(reply.has("payload"))
    }
    @Test fun approvedForeignUidReceivesReadOnlyTemporaryUriAndRevocationDeniesReopen():Unit=runBlocking {
        approve(AutomationScope.summary)
        val reply=call()
        assertEquals(-1,reply.getInt("code"));assertTrue(reply.getBoolean("readable"))
        assertTrue(reply.getBoolean("writeDenied"));assertTrue(reply.getBoolean("persistDenied"))
        val flags=reply.getInt("flags")
        assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
        assertEquals(0,flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        assertEquals("application/json",reply.getString("mime"))
        assertEquals(1,JSONObject(reply.getString("payload")).getJSONObject("data").getInt("pointCount"))
        val uri=reply.getString("uri");assertTrue(uri.startsWith("content://${context.packageName}.automation-results/"))
        // Even the provider's own app UID is not the approved result recipient.
        try {context.contentResolver.openInputStream(Uri.parse(uri))?.close();fail("Unapproved recipient read result")}
        catch(_:SecurityException) { }
        graph.automationController.clearAll()
        assertFalse(call(mode="reopen",uri=uri).getBoolean("readable"))
    }
    @Test fun missingScopeAndInvalidRequestReturnBoundedErrorsWithoutMutation():Unit=runBlocking {
        approve(AutomationScope.summary)
        val session=RecordingSession("transport-outing",Instant.now().minusSeconds(60),Instant.now(),"Original")
        graph.history.dao.put(SessionRow(session))
        val denied=call("{\"version\":1,\"command\":\"renameOuting\",\"id\":\"transport-outing\",\"name\":\"Forbidden\"}")
        assertEquals("not_authorized",denied.getString("error"));assertEquals(session.name,graph.history.dao.session(session.id)!!.value.name)
        val invalid=call("{\"version\":1,\"command\":\"statistics\",\"ignored\":true}")
        assertEquals("invalid_request",invalid.getString("error"));assertFalse(invalid.has("uri"))
    }
    @Test fun approvedBackgroundServiceCanReadAfterRequestingActivityFinishes():Unit=runBlocking {
        approve(AutomationScope.summary)
        val reply=call(mode="finishThenRead")
        assertEquals(-1,reply.getInt("code"))
        assertTrue("Fixture must finish the result recipient before reading",reply.getBoolean("requesterDestroyed"))
        assertTrue("Approved automation must remain readable without its requesting Activity",reply.getBoolean("readable"))
        assertEquals(1,JSONObject(reply.getString("payload")).getJSONObject("data").getInt("pointCount"))
        graph.automationController.clearAll()
        val revoked=call(mode="reopen",uri=reply.getString("uri"))
        assertFalse(revoked.getBoolean("readable"));assertFalse(revoked.getBoolean("granted"))
    }
    @Test fun forwardedResultTokenIsRejectedEvenWhenTheRecipientWasApproved():Unit=runBlocking {
        approve(AutomationScope.summary)
        val reply=call(mode="forward")
        assertEquals(0,reply.getInt("code"));assertFalse(reply.has("payload"));assertFalse(reply.has("uri"))
    }
    @Test fun authorizedRenameReturnsTheCommittedEntityWithoutOtherPrivateFields():Unit=runBlocking {
        approve(AutomationScope.renameVisits)
        val visit=Visit("transport-visit",Coordinate(0.0,0.0),Instant.ofEpochMilli(System.currentTimeMillis()-60_000),name="Original",notes="Keep private notes",
            originalCoordinate=Coordinate(1.0,1.0),originalName="Detected",confirmation=Confirmation.corrected)
        graph.history.dao.put(VisitRow(visit))
        val reply=call("{\"version\":1,\"command\":\"renameVisit\",\"id\":\"transport-visit\",\"name\":\"Renamed fixture\"}")
        assertEquals(-1,reply.getInt("code"));assertTrue(reply.getBoolean("readable"))
        val data=JSONObject(reply.getString("payload")).getJSONObject("data")
        assertEquals("Renamed fixture",data.getString("name"));assertFalse(data.has("notes"));assertFalse(data.has("coordinate"))
        val saved=graph.history.dao.visit(visit.id)!!.value
        assertNotNull(saved.updatedAt);assertEquals(visit.copy(name="Renamed fixture",updatedAt=saved.updatedAt),saved)
    }
    @Test fun exportScopeCannotReplaceTheActualPurchaseGate():Unit=runBlocking {
        approve(AutomationScope.exports)
        val selection=com.bontecou.whereday.export.ExportSelection(options=com.bontecou.whereday.export.ExportOptions(from=Instant.now().minusSeconds(120),until=Instant.now()))
        val reply=call("{\"version\":1,\"command\":\"export\",\"selection\":${com.bontecou.whereday.export.ExportSelectionCodec.encode(selection)}}")
        assertEquals(0,reply.getInt("code"));assertEquals("purchase_required",reply.getString("error"));assertFalse(reply.has("uri"))
    }
    @Test fun recordingPermissionIsNeverPromptedImplicitlyAndAnIdleStopIsAcknowledged():Unit=runBlocking {
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_COARSE_LOCATION))
        approve(AutomationScope.recording)
        val start=call("{\"version\":1,\"command\":\"startRecording\"}")
        assertEquals("open_app",start.getString("error"));assertFalse(RecordingRuntime.active.value)
        assertEquals(0L,graph.history.dao.openSessionCount())
        val stop=call("{\"version\":1,\"command\":\"stopRecording\"}")
        assertEquals(-1,stop.getInt("code"))
        assertFalse(JSONObject(stop.getString("payload")).getJSONObject("data").getBoolean("recordingActive"))
    }
    @Test fun manualVisitCommandsRequireTheirNewScopeAndNeverPromptForLocation():Unit=runBlocking {
        approve(AutomationScope.visits,AutomationScope.renameVisits,AutomationScope.recording)
        val denied=call("""{"version":1,"command":"checkIn","name":"Cafe","latitude":0,"longitude":0}""")
        assertEquals("not_authorized",denied.getString("error"));assertEquals(0L,graph.history.dao.totalVisitCount())
        approve(AutomationScope.manualVisits)
        val current=call("""{"version":1,"command":"checkIn","name":"Cafe"}""")
        assertEquals("open_app",current.getString("error"));assertFalse(current.has("uri"))
        assertEquals(0L,graph.history.dao.totalVisitCount());assertEquals(0L,graph.history.dao.openSessionCount())
        assertFalse(RecordingRuntime.active.value)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_FINE_LOCATION))
    }
    @Test fun foreignClientCanCheckInDisambiguateAndCheckOutWithBoundedResults():Unit=runBlocking {
        approve(AutomationScope.manualVisits,AutomationScope.visits,AutomationScope.places)
        graph.history.dao.put(PlaceRow(SavedPlace("library","Library",Coordinate(18.0,-66.0),"Synthetic address")))
        val places=JSONObject(call("""{"version":1,"command":"savedPlaces"}""").getString("payload")).getJSONArray("data")
        assertEquals(1,places.length());assertEquals(setOf("id","name"),places.getJSONObject(0).keys().asSequence().toSet())
        val request="""{"version":1,"command":"checkIn","name":"Cafe","latitude":0,"longitude":0}"""
        val first=call(request)
        assertEquals(-1,first.getInt("code"));assertTrue(first.getBoolean("readable"))
        val id=JSONObject(first.getString("payload")).getJSONObject("data").getString("id")
        assertEquals("overlap",call(request).getString("error"));assertEquals(1L,graph.history.dao.totalVisitCount())
        assertEquals(-1,call(request.dropLast(1)+",\"allowOverlap\":true}").getInt("code"))
        assertEquals("ambiguous_visit",call("""{"version":1,"command":"checkOut"}""").getString("error"))
        val open=JSONObject(call("""{"version":1,"command":"openManualVisits"}""").getString("payload")).getJSONArray("data")
        assertEquals(2,open.length())
        assertEquals(-1,call("""{"version":1,"command":"checkOut","id":"$id"}""").getInt("code"))
        assertEquals(-1,call("""{"version":1,"command":"checkOut"}""").getInt("code"))
        assertEquals("visit_closed",call("""{"version":1,"command":"checkOut","id":"$id"}""").getString("error"))
        assertEquals("no_open_visit",call("""{"version":1,"command":"checkOut"}""").getString("error"))
        val saved=call("""{"version":1,"command":"checkIn","savedPlaceId":"library"}""")
        assertEquals("Library",JSONObject(saved.getString("payload")).getJSONObject("data").getString("name"))
        assertEquals("missing_place",call("""{"version":1,"command":"checkIn","savedPlaceId":"missing"}""").getString("error"))
        assertEquals(3L,graph.history.dao.totalVisitCount());assertFalse(RecordingRuntime.active.value)
    }
    companion object {const val CLIENT="com.bontecou.whereday.automationclient"}
}
