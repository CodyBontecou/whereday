package com.bontecou.whereday.platform

import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.time.*
import java.util.Locale
import java.util.concurrent.CountDownLatch

class HistoryImportControllerTest {
    private val point=TrackPoint(coordinate=Coordinate(18.0,-66.0),time=Instant.parse("2026-10-26T12:00:00Z"),accuracy=5.0)
    private fun source(text:String=HistoryExporter.render(ExportFormat.json,emptyList(),listOf(point)))=object:HistoryImportInput {override fun open()=text.byteInputStream()}
    private open class Target:StagedHistoryImportTarget {
        private fun records(stage:HistoryImportStage):ImportResult {
            val visits=mutableListOf<Visit>();val points=mutableListOf<TrackPoint>()
            stage.batches({}) {rows->rows.forEach {if(it.kind==ImportRecordKind.visit) visits+=it.visit() else points+=it.point()}}
            return ImportResult(visits,points)
        }
        override suspend fun inspect(stage:HistoryImportStage,check:()->Unit):Int {
            val found=inspect(records(stage),check);return found.visits.size+found.points.size
        }
        override suspend fun apply(stage:HistoryImportStage,policy:HistoryImportPolicy,check:()->Unit)=
            apply(records(stage),HistoryImportBaseline(emptyMap(),emptyMap()),policy,check)
        var applied=0;var inspected=0
        override suspend fun inspect(records:ImportResult,check:()->Unit):HistoryImportBaseline {check();inspected++;return HistoryImportBaseline(emptyMap(),emptyMap())}
        override suspend fun apply(records:ImportResult,baseline:HistoryImportBaseline,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts {check();applied++;return HistoryImportCounts(records.visits.size,records.points.size)}
    }
    private suspend fun review(controller:HistoryImportController):HistoryImportReview {
        withTimeout(3_000) {while(controller.state.value.phase!=HistoryImportPhase.review) delay(1)}
        return controller.state.value.review!!
    }
    @Test fun reviewReportsAllRejectedRowsWithOnlyTenBoundedDiagnostics():Unit=runBlocking {
        val invalid="""{"latitude":"bad","longitude":-66,"timestamp":"bad"}"""
        val valid="""{"latitude":18,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}"""
        val text="""{"points":["""+List(2000) {invalid}.joinToString(",")+",$valid]}"
        val target=Target();val c=HistoryImportController(target,memoryImportWorkspaces())
        try {
            c.select(source(text))!!.join();val summary=review(c)
            assertEquals(1,summary.points);assertEquals(2000,summary.rejected)
            assertEquals(10,summary.errors.size);assertTrue(summary.errors.all {it.length<=200})
            assertEquals(0,target.applied)
        } finally {c.close()}
    }
    @Test fun selectionOnlyReviewsAndDiscardOrStaleIdentityCannotApply():Unit=runBlocking {
        val target=Target();val c=HistoryImportController(target,memoryImportWorkspaces())
        try {
            c.select(source(),Locale.US,ZoneOffset.UTC)!!.join();val summary=review(c)
            assertEquals(1,summary.points);assertEquals(0,target.applied)
            assertNull(c.commit("old",HistoryImportPolicy.keepExisting));c.discard()
            assertNull(c.commit(summary.id,HistoryImportPolicy.keepExisting));assertEquals(0,target.applied)
            c.select(source())!!.join();c.commit(review(c).id,HistoryImportPolicy.keepExisting)!!.join()
            assertEquals(HistoryImportPhase.complete,c.state.value.phase);assertEquals(1,target.applied)
        } finally {c.close()}
    }
    @Test fun cancelClosesABlockedInputAndCannotPublishALateReview():Unit=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var closed=false
        val input=object:HistoryImportInput {
            override fun open()=object:InputStream() {
                override fun read():Int {entered.countDown();release.await();throw java.io.IOException("closed")}
                override fun close() {closed=true;release.countDown()}
            }
            override fun cancel() {release.countDown()}
        }
        val c=HistoryImportController(Target(),memoryImportWorkspaces())
        try {
            val read=c.select(input)!!;withContext(Dispatchers.IO) {assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))}
            c.cancel();withTimeout(3_000) {read.join()}
            assertEquals(HistoryImportPhase.canceled,c.state.value.phase);assertTrue(closed)
        } finally {release.countDown();c.close()}
    }
    @Test fun cancelBeforeTransactionDecisionReportsCanceledButAfterCommitReportsActualCounts():Unit=runBlocking {
        for(committed in listOf(false,true)) {
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val target=object:Target() {
                override suspend fun apply(records:ImportResult,baseline:HistoryImportBaseline,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts {
                    if(committed) {check();applied++}
                    entered.complete(Unit);release.await()
                    if(!committed) {check();applied++}
                    return HistoryImportCounts(points=1)
                }
            }
            val c=HistoryImportController(target,memoryImportWorkspaces())
            try {
                c.select(source())!!.join();val work=c.commit(review(c).id,HistoryImportPolicy.keepExisting)!!;entered.await()
                c.cancel();release.complete(Unit);work.join()
                assertEquals(if(committed) HistoryImportPhase.complete else HistoryImportPhase.canceled,c.state.value.phase)
                assertEquals(if(committed) 1 else 0,target.applied)
            } finally {release.complete(Unit);c.close()}
        }
    }
    @Test fun conflictingSourceIdsFailAndIdenticalRepeatedRowsAreCombined():Unit=runBlocking {
        val c=HistoryImportController(Target(),memoryImportWorkspaces())
        try {
            c.select(source(HistoryExporter.render(ExportFormat.json,emptyList(),listOf(point,point))))!!.join()
            assertEquals(1,review(c).repeated);assertEquals(1,c.state.value.review!!.points);c.discard()
            val conflict=com.google.gson.JsonParser.parseString(HistoryExporter.render(ExportFormat.json,emptyList(),listOf(point,point.copy(accuracy=9.0)))).asJsonObject
            conflict.getAsJsonArray("points").forEach {it.asJsonObject.addProperty("id",point.id)}
            c.select(source(conflict.toString()))!!.join()
            assertEquals(HistoryImportPhase.failed,c.state.value.phase)
        } finally {c.close()}
    }
    @Test fun clearWaitsForCanceledWorkAndBlocksNewSelectionsThroughTheActualClear():Unit=runBlocking {
        val applying=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val clearing=CompletableDeferred<Unit>();val finishClear=CompletableDeferred<Unit>()
        val target=object:Target() {
            override suspend fun apply(records:ImportResult,baseline:HistoryImportBaseline,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts {
                applying.complete(Unit);release.await();check();applied++;return HistoryImportCounts(points=1)
            }
        }
        val c=HistoryImportController(target,memoryImportWorkspaces())
        try {
            c.select(source())!!.join();c.commit(review(c).id,HistoryImportPolicy.keepExisting);applying.await()
            val clear=async(start=CoroutineStart.UNDISPATCHED) {c.clearAll {clearing.complete(Unit);finishClear.await()}}
            assertNull(c.select(source()));release.complete(Unit);clearing.await();assertEquals(0,target.applied)
            assertNull(c.select(source()));finishClear.complete(Unit);clear.await()
            assertEquals(HistoryImportPhase.idle,c.state.value.phase)
        } finally {release.complete(Unit);finishClear.complete(Unit);c.close()}
    }
    @Test fun cancelClickArrivingAfterParsingStillDiscardsTheUncommittedReview():Unit=runBlocking {
        val target=Target();val c=HistoryImportController(target,memoryImportWorkspaces())
        try {
            c.select(source())!!.join();val id=review(c).id
            c.cancel();assertEquals(HistoryImportPhase.canceled,c.state.value.phase)
            assertNull(c.commit(id,HistoryImportPolicy.keepExisting));assertEquals(0,target.applied)
        } finally {c.close()}
    }
    @Test fun immediateCancellationCannotLeaveTheControllerStuck():Unit=runBlocking {
        repeat(20) {
            val target=Target();val c=HistoryImportController(target,memoryImportWorkspaces())
            try {
                val work=c.select(source())!!;c.cancel();withTimeout(3_000) {work.join()}
                assertNotEquals(HistoryImportPhase.canceling,c.state.value.phase);assertEquals(0,target.applied)
            } finally {c.close()}
        }
    }
    @Test fun providerFailureClosesTheSourceWithoutReachingTheDatabase():Unit=runBlocking {
        var closed=false;var delivered=0;val target=Target();val c=HistoryImportController(target,memoryImportWorkspaces())
        try {
            val reading=c.select(object:HistoryImportInput {override fun open()=object:InputStream() {
                override fun read()=120
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {if(delivered>=32768) throw java.io.IOException("Synthetic provider failure");buffer.fill(120,offset,offset+length);delivered+=length;return length}
                override fun close() {closed=true}
            }})!!
            withTimeout(5_000) {reading.join()}
            assertEquals(HistoryImportPhase.failed,c.state.value.phase);assertTrue(closed)
            assertNotNull(c.state.value.error);assertEquals(0,target.inspected)
            assertTrue(delivered<=32768);assertEquals(0,target.applied)
        } finally {c.close()}
    }
    @Test fun anUncooperativeReadCannotBlockClearOrPublishIntoANewerReview():Unit=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val c=HistoryImportController(Target(),memoryImportWorkspaces())
        val source=object:HistoryImportInput {override fun open()=object:InputStream() {
            override fun read():Int {entered.countDown();release.await();throw java.io.IOException("synthetic delayed provider")}
            override fun close() {} // Deliberately ignores cancellation/close until the test releases it.
        }}
        try {
            val old=c.select(source)!!
            withContext(Dispatchers.IO) {assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))}
            val cleared=CompletableDeferred<Unit>();val clear=async {c.clearAll {cleared.complete(Unit)}}
            try {withTimeout(2_000) {cleared.await()}} finally {release.countDown()}
            clear.await()
            c.select(this@HistoryImportControllerTest.source())!!.join();val id=review(c).id
            old.join();assertEquals(id,c.state.value.review!!.id)
        } finally {release.countDown();c.close()}
    }
    @Test fun timestampsOutsideStorageRangeAreRejectedBeforeReview():Unit=runBlocking {
        val target=Target();val c=HistoryImportController(target,memoryImportWorkspaces())
        try {
            val json="""{"points":[{"latitude":18,"longitude":-66,"timestamp":"${Instant.MAX}","horizontalAccuracy":5}]}"""
            c.select(source(json))!!.join()
            assertEquals(HistoryImportPhase.failed,c.state.value.phase);assertEquals(0,target.inspected);assertEquals(0,target.applied)
            assertEquals("This file contains a timestamp outside the supported history range.",c.state.value.error)
        } finally {c.close()}
    }
    @Test fun invalidUtf8IsNotSilentlyReplaced():Unit=runBlocking {
        val c=HistoryImportController(Target(),memoryImportWorkspaces())
        try {
            c.select(object:HistoryImportInput {override fun open()=byteArrayOf(0xc3.toByte(),0x28).inputStream()})!!.join()
            assertEquals(HistoryImportPhase.failed,c.state.value.phase);assertEquals("Choose a UTF-8 history file.",c.state.value.error)
        } finally {c.close()}
    }
}
