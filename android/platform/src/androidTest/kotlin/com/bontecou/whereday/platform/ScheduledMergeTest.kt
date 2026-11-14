package com.bontecou.whereday.platform

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.UUID

class ScheduledMergeTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val tree=DocumentsContract.buildTreeDocumentUri("com.bontecou.whereday.export-test-documents","root")
    private val root=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
    @Test fun stagesVerifiedDeltaMergeWithoutChangingExistingDestinationAndChecksAccess()=runBlocking {
        check(context.packageName!="com.bontecou.whereday")
        val name="scheduled-merge-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name)
        val files=ExportFiles(context,db) {};val day=Instant.parse("2026-10-27T00:00:00Z")
        try {
            context.grantUriPermission(context.packageName,tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            db.history().putPoints((0..2).map {i ->PointRow(TrackPoint(id="synthetic-$i",coordinate=Coordinate(18.0,-66.0),time=day.plusSeconds(i*60L),accuracy=5.0),null)})
            fun plan(now:Instant,cursor:Instant?)=ScheduledExportPlan.resolve(ExportFormat.json,ExportOptions(kind=DataKind.points,zone=ZoneOffset.UTC),"track",ExportSchedule(9,0,3),ScheduledFileMode.append,cursor,now)
            val old=files.prepareScheduled(plan(day.plusSeconds(60),null)).files.single()
            val uri=DocumentsContract.createDocument(context.contentResolver,root,"application/json",old.file.name)!!
            files.saveNewDocument(old,uri)
            val next=plan(day.plusSeconds(120),day.plusSeconds(60));val delta=files.prepareScheduled(next)
            val merged=files.mergeScheduled(next,delta,uri)
            assertEquals(DestinationFingerprint(old.bytes,old.sha256),merged.expectedExisting)
            val result=JsonParser.parseString(merged.prepared.files.single().file.readText()).asJsonObject
            assertEquals(3,result["points"].asJsonArray.size())
            assertEquals(2,result["totalPoints"].asInt) // iOS refreshes scalar counts from the delta payload.
            assertEquals(old.file.readText(),context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()})
            assertTrue(merged.prepared.files.single().file.parentFile!!.walkTopDown().none {it.name.endsWith(".db") || it.name==".existing"})
            val delivery=UUID.randomUUID().toString()
            files.startScheduledMergedDelivery(delivery,merged,root)
            assertEquals(ScheduledDeliveryPhase.completed,files.resumeScheduledDelivery(delivery).phase)
            assertEquals(merged.prepared.files.single().file.readText(),context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()})
            val opens=ExportTestDocuments.outputOpens.get()
            assertEquals(ScheduledDeliveryPhase.completed,files.resumeScheduledDelivery(delivery).phase)
            assertEquals(opens,ExportTestDocuments.outputOpens.get())
            val before=File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet()
            val denied=ExportFiles(context,db) {error("Verified access is required")}
            try {denied.mergeScheduled(next,delta,uri);fail()} catch(_:IllegalStateException) {}
            context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write("malformed existing JSON".toByteArray())}
            try {files.mergeScheduled(next,delta,uri);fail()} catch(_:Exception) {}
            assertEquals("malformed existing JSON",context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()})
            assertEquals(before,File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet())
        } finally {
            files.clearCache();db.close();context.deleteDatabase(name)
            context.revokeUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            File(context.cacheDir,"synthetic-export-documents").deleteRecursively()
        }
    }
    @Test fun diskIdentityIndexDeduplicatesLargeJSONAndKeepsNamespacesSeparate()=runBlocking {
        val folder=File(context.cacheDir,"merge-synthetic-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val old=File(folder,"old.json");val fresh=File(folder,"fresh.json");val out=File(folder,"merged.json")
            fun generate(file:File,from:Int,until:Int) {file.bufferedWriter().use {w ->
                w.append("[");for(i in from until until) {if(i>from) w.append(',');w.append("{\"id\":\"$i\",\"value\":$i}")};w.append("]")
            }}
            generate(old,0,100_000);generate(fresh,50_000,150_000)
            val scratch=File(folder,"identities.db");val start=System.nanoTime()
            DiskMergeIdentityStore(scratch).use {index ->
                assertTrue(index.insert("other","id=0"));assertFalse(index.insert("other","id=0"));assertTrue(index.insert("another","id=0"))
                out.bufferedWriter().use {ScheduledExportMerger.merge(ExportFormat.json,old,fresh,it,index)}
            }
            assertFalse(scratch.exists())
            var count=0
            JsonReader(out.bufferedReader()).use {r ->r.beginArray();while(r.hasNext()) {val value=JsonParser.parseReader(r).asJsonObject;assertEquals(count.toString(),value["id"].asString);count++};r.endArray()}
            assertEquals(150_000,count)
            android.util.Log.i("ScheduledMergeTest","merged=$count elapsedMs=${(System.nanoTime()-start)/1_000_000} bytes=${out.length()}")
            Unit
        } finally {folder.deleteRecursively()}
    }
}
