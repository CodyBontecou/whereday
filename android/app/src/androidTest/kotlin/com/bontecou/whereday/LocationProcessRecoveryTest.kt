package com.bontecou.whereday

import android.Manifest
import android.app.NotificationManager
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** The host kills the live QA process between phases. Never run on the main package. */
class LocationProcessRecoveryTest {
    @Test fun locationServicesRecoverAcrossRealProcessDeath():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        val args=InstrumentationRegistry.getArguments()
        val phase=args.getString("locationRecoveryPhase")
        org.junit.Assume.assumeTrue("Use the explicit host process-recovery harness",phase!=null)
        val mode=requireNotNull(args.getString("locationRecoveryMode"))
        val termination=requireNotNull(args.getString("locationRecoveryTermination"))
        require(termination in setOf("kill","force-stop","reboot"))
        require(mode in setOf("route","visits","both"))
        val route=mode!="visits";val visits=mode!="route"
        val proof=File(context.noBackupFilesDir,"qa-location-process-proof.json")
        val graph=AppGraph.from(context);graph.journalClear.awaitReady()
        // After a reboot, BOOT_COMPLETED delivery on a slow CI emulator can take well over 20 s; a restart that never happens still fails.
        val recoveryTimeoutMs=if(termination=="reboot") 60_000L else 20_000L
        suspend fun waitFor(label:String="recovery condition",condition:suspend ()->Boolean) {
            try {withTimeout(recoveryTimeoutMs) {while(!condition()) delay(30)}}
            catch(timeout:TimeoutCancellationException) {
                throw AssertionError("$label; routeRequested=${graph.history.dao.recording()?.requested}; visitsRequested=${graph.history.dao.visitMonitoring()?.requested}; routeRuntime=${RecordingRuntime.active.value}; visitRuntime=${VisitMonitoringRuntime.state.value}",timeout)
            }
        }
        fun persist(value:JSONObject) {
            FileOutputStream(proof).use {it.write(value.toString().toByteArray());it.fd.sync()}
        }
        fun grantLocationAndNotifications() {
            val permissions=listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)+
                if(android.os.Build.VERSION.SDK_INT>=33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            for(permission in permissions) instrumentation.uiAutomation.grantRuntimePermission(context.packageName,permission)
        }
        suspend fun start(activity:ComponentActivity) {
            waitFor("visible window") {activity.hasWindowFocus()}
            if(visits) graph.visitMonitoringCommands.startVisible(activity,graph.journalClear.state.value.generation)
            if(route) graph.recordingCommands.startVisible(activity)
            waitFor {RecordingRuntime.active.value==route && VisitMonitoringRuntime.state.value==
                if(visits) {if(route) VisitMonitoringMode.route else VisitMonitoringMode.balanced} else VisitMonitoringMode.off}
        }
        when(phase) {
            "seed"->{
                assertFalse(proof.exists())
                graph.journalClear.clear().join();assertFalse(graph.journalClear.state.value.blocked)
                graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false)}
                grantLocationAndNotifications()
                val activity=ActivityScenario.launch(ComponentActivity::class.java)
                var host:ComponentActivity?=null;activity.onActivity {host=it}
                start(checkNotNull(host))
                val session=graph.history.dao.recording()?.sessionId
                val point=TrackPoint("process-proof-point",Coordinate(0.0,0.0),Instant.EPOCH,5.0)
                // A known durable row proves preservation even when FLP has not delivered a fix.
                graph.history.dao.put(PointRow(point,null))
                val visit=Visit(id="process-proof-visit",coordinate=Coordinate(0.0,0.0),arrivedAt=Instant.EPOCH,departedAt=Instant.EPOCH.plusSeconds(60),name="Synthetic preserved visit")
                graph.history.dao.put(VisitRow(visit))
                val notices=context.getSystemService(NotificationManager::class.java).activeNotifications
                assertTrue(notices.any {it.id==41}==visits)
                assertTrue(notices.any {it.id==40}==route)
                val boot=android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,-1)
                assertTrue(boot>=0)
                if(route) assertEquals(boot,graph.history.dao.recording()!!.startedBootCount)
                if(visits) assertEquals(boot,graph.history.dao.visitMonitoring()!!.startedBootCount)
                // A delayed BOOT_COMPLETED must not interrupt already acknowledged services.
                graph.reconcileLocationServices()
                assertEquals(route,RecordingRuntime.active.value)
                if(visits) assertTrue(graph.history.dao.visitMonitoring()!!.requested)
                persist(JSONObject().put("bootCount",boot).put("phase","seed").put("mode",mode).put("pid",Process.myPid()).put("session",session ?: JSONObject.NULL).put("visitGeneration",graph.history.dao.visitMonitoring()?.generation ?: JSONObject.NULL))
                // Keep both the Activity and acknowledged real services alive until the host
                // kills this PID. A host failure produces a bounded test failure, never a pass.
                try {withTimeout(60_000) {awaitCancellation()}} finally {activity.close()}
            }
            "recover"->{
                val saved=JSONObject(proof.readText())
                assertEquals("seed",saved.getString("phase"));assertEquals(mode,saved.getString("mode"))
                val boot=android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,-1)
                assertTrue(boot>=0)
                val rebooted=saved.getInt("bootCount")!=boot
                assertTrue(rebooted || saved.getInt("pid")!=Process.myPid())
                if(rebooted) waitFor("boot receiver before Activity") {graph.history.dao.recording()?.requested!=true && graph.history.dao.visitMonitoring()?.requested!=true}
                assertFalse(RecordingRuntime.active.value);assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
                val beforeRoute=graph.history.dao.recording()
                val beforeVisits=graph.history.dao.visitMonitoring()
                if(termination=="kill") {
                    assertEquals(route,beforeRoute?.requested==true);assertEquals(visits,beforeVisits?.requested==true)
                }
                assertEquals(termination=="reboot",rebooted)
                val count=graph.history.dao.totalPointCount()
                val point=checkNotNull(graph.history.dao.point("process-proof-point"))
                val visit=checkNotNull(graph.history.dao.visit("process-proof-visit"))
                val session=if(saved.isNull("session")) null else checkNotNull(graph.history.dao.session(saved.getString("session"))).value
                val expectedEnd=beforeRoute?.lastFix ?: session?.startedAt
                val main=ActivityScenario.launch(MainActivity::class.java)
                try {
                    waitFor {graph.history.dao.recording()?.requested!=true && graph.history.dao.visitMonitoring()?.requested!=true}
                    assertFalse(RecordingRuntime.active.value);assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
                    assertEquals(count,graph.history.dao.totalPointCount())
                    assertEquals(point,graph.history.dao.point(point.value.id));assertEquals(visit,graph.history.dao.visit(visit.value.id))
                    assertEquals(0L,graph.history.dao.openSessionCount())
                    if(route) {
                        assertEquals((if(rebooted) RecordingInterruption.phoneRestarted else RecordingInterruption.processInterrupted).code,graph.history.dao.recording()!!.interruption)
                        assertEquals(expectedEnd,graph.history.dao.session(session!!.id)!!.value.endedAt)
                    }
                    if(visits) assertEquals(if(rebooted) "visits.restarted" else "visits.interrupted",graph.history.dao.visitMonitoring()!!.interruption)
                    waitFor {context.getSystemService(NotificationManager::class.java).activeNotifications.none {it.id in setOf(40,41)}}
                    var host:MainActivity?=null;main.onActivity {host=it}
                    val hasLocation=listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION).any {
                        androidx.core.content.ContextCompat.checkSelfPermission(context,it)==android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if(!hasLocation) {
                        // Instrumentation-granted permissions may not survive the reboot.
                        // Prove denial is safe, then model explicit permission approval.
                        val failure=runCatching {start(checkNotNull(host))}.exceptionOrNull()
                        assertTrue("Restart without location must fail explicitly",failure is RecordingNeedsLocation)
                        assertFalse(RecordingRuntime.active.value);assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
                        assertFalse(graph.history.dao.recording()?.requested==true);assertFalse(graph.history.dao.visitMonitoring()?.requested==true)
                        grantLocationAndNotifications()
                    }
                    start(checkNotNull(host))
                    if(route) {
                        assertNotEquals(session!!.id,graph.history.dao.recording()!!.sessionId)
                        assertEquals(1L,graph.history.dao.openSessionCount())
                        assertEquals(expectedEnd,graph.history.dao.session(session.id)!!.value.endedAt)
                    }
                    if(visits) assertNotEquals(saved.getString("visitGeneration"),graph.history.dao.visitMonitoring()!!.generation)
                    graph.visitMonitoringCommands.stop();graph.recordingCommands.stop()
                    waitFor {context.getSystemService(NotificationManager::class.java).activeNotifications.none {it.id in setOf(40,41)}}
                    persist(saved.put("phase","recovered").put("recoveredPid",Process.myPid()).put("recoveredBootCount",boot).put("permissionsRegranted",!hasLocation).put("preservedPoints",count))
                } finally {main.close();graph.visitMonitoringCommands.stop();graph.recordingCommands.stop()}
            }
            else->error("Unknown process-recovery phase")
        }
    }
}
