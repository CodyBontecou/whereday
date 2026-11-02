package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class HistoryWriteCancellationTest {
    private lateinit var db:HistoryDatabase
    private val row=VisitRow(Visit("cancel-fixture",Coordinate(0.0,0.0),Instant.EPOCH))
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        db=HistoryDatabase.create(context,null)
    }
    @After fun close() {db.close()}
    @Test(timeout=30_000) fun cancellationDuringAnActiveWriterRollsBackItsPartialMutation()=runBlocking {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val job=async(Dispatchers.IO) {db.writeTransaction {
            db.history().put(row);started.complete(Unit);release.await()
        }}
        started.await();job.cancel();release.complete(Unit);job.join()
        assertTrue(job.isCancelled);assertNull(db.history().visit(row.value.id))
    }
    @Test(timeout=30_000) fun anExplicitNonCancellableCleanupCanStillCommitAfterCallerCancellation()=runBlocking {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val job=async(Dispatchers.IO) {withContext(NonCancellable) {db.writeTransaction {
            started.complete(Unit);release.await();db.history().put(row)
        }}}
        started.await();job.cancel();release.complete(Unit);job.join()
        assertTrue(job.isCancelled);assertEquals(row,db.history().visit(row.value.id))
    }
    @Test(timeout=30_000) fun nestedWritesRetainTheTransactionAndRollbackTogether()=runBlocking {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val job=async(Dispatchers.IO) {db.writeTransaction {
            db.history().put(row)
            db.writeTransaction {db.history().put(row.copy(value=row.value.copy(id="nested")))}
            started.complete(Unit);release.await()
        }}
        started.await();job.cancel();release.complete(Unit);job.join()
        assertNull(db.history().visit(row.value.id));assertNull(db.history().visit("nested"))
    }
}
