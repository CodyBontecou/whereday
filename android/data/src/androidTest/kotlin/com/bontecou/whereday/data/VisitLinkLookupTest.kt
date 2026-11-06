package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class VisitLinkLookupTest {
    private lateinit var db:HistoryDatabase
    private lateinit var repo:HistoryRepository
    private val id="123e4567-e89b-12d3-a456-426614174000"
    private val visit=Visit(id,Coordinate(18.0,-66.0),Instant.EPOCH,name="Original",notes="Keep audit",source=VisitSource.manual)
    @Before fun setup() {val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.data.test");db=HistoryDatabase.create(context,null);repo=HistoryRepository(db)}
    @After fun cleanup() {db.close()}
    @Test fun uuidLookupHandlesSwiftAndAndroidCaseWithoutChangingRows()=runBlocking {
        val stored=visit.copy(id=id.uppercase());db.history().put(VisitRow(stored))
        assertEquals(stored,repo.linkedVisit(id));assertEquals(stored,db.history().visit(stored.id)!!.value)
        assertEquals(1L,db.history().totalVisitCount())
    }
    @Test fun missingAndMalformedIdsDoNotReadAnUnrelatedVisit()=runBlocking {
        db.history().put(VisitRow(visit))
        assertNull(repo.linkedVisit("123e4567-e89b-12d3-a456-426614174001"))
        try {repo.linkedVisit("1-1-1-1-1");fail()} catch(_:IllegalArgumentException) {}
        assertEquals(visit,db.history().visit(id)!!.value)
    }
    @Test fun collidingCaseVariantsAreAmbiguousRatherThanChoosingARecord()=runBlocking {
        val other=visit.copy(id=id.uppercase(),name="Other")
        db.history().put(VisitRow(visit));db.history().put(VisitRow(other))
        try {repo.linkedVisit(id);fail()} catch(_:IllegalArgumentException) {}
        assertEquals(visit,db.history().visit(id)!!.value);assertEquals(other,db.history().visit(other.id)!!.value)
    }
}
