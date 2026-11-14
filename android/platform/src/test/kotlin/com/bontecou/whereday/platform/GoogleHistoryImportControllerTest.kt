package com.bontecou.whereday.platform

import com.bontecou.whereday.export.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class GoogleHistoryImportControllerTest {
    private class Target:StagedHistoryImportTarget {
        val applied=mutableListOf<ImportStageRow>()
        override suspend fun inspect(stage:HistoryImportStage,check:()->Unit)=0
        override suspend fun inspectPlan(stage:HistoryImportStage,check:()->Unit)=stage.inspectTimeline(check) {false}
        override suspend fun apply(stage:HistoryImportStage,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts {
            stage.verify(check);stage.batches(check) {applied+=it};return HistoryImportCounts(visits=applied.count {it.kind==ImportRecordKind.visit},points=applied.count {it.kind==ImportRecordKind.point})
        }
        override suspend fun inspect(records:ImportResult,check:()->Unit)=error("Staged path required")
        override suspend fun apply(records:ImportResult,baseline:HistoryImportBaseline,policy:HistoryImportPolicy,check:()->Unit)=error("Staged path required")
    }
    private fun input(text:String)=object:HistoryImportInput {override fun open()=text.byteInputStream()}
    @Test fun reviewReportsGoogleSourceAndDeduplicatesChronologicallyBeforeExplicitSelection():Unit=runBlocking {
        val visits=listOf(120,60,0).joinToString(",") {second->"""{"startTime":"1970-01-01T00:0${second/60}:00Z","endTime":"1970-01-01T00:10:00Z","visit":{"topCandidate":{"placeLocation":"geo:18,-66"}},"activity":{"start":"geo:18,-66"}}"""}
        val target=Target();val controller=HistoryImportController(target,memoryImportWorkspaces())
        try {
            controller.select(input("[$visits]"))!!.join();val state=controller.state.value
            assertEquals(state.error,HistoryImportPhase.review,state.phase);val review=state.review!!
            assertEquals(HistoryImportSource.googleDevice,review.source);assertEquals(2,review.visits);assertEquals(3,review.points);assertEquals(1,review.repeated);assertTrue(target.applied.isEmpty())
            assertNull(controller.commit(review.id,HistoryImportPolicy.replaceExisting))
            assertNull(controller.commit(review.id,HistoryImportPolicy.keepExisting,HistoryImportSelection(false,false)))
            controller.commit(review.id,HistoryImportPolicy.keepExisting,HistoryImportSelection(false,true))!!.join()
            assertEquals(HistoryImportPhase.complete,controller.state.value.phase);assertEquals(3,target.applied.size);assertTrue(target.applied.all {it.kind==ImportRecordKind.point})
        } finally {controller.close()}
    }
    @Test fun repeatedGooglePointsKeepTheFirstPayloadAndDiscardedReviewCannotApply():Unit=runBlocking {
        val target=Target();val controller=HistoryImportController(target,memoryImportWorkspaces())
        try {
            controller.select(input("""{"locations":[{"latitudeE7":180000000,"longitudeE7":-660000000,"timestampMs":0,"accuracy":5},{"latitudeE7":180000001,"longitudeE7":-660000000,"timestampMs":1,"accuracy":8},{"latitudeE7":180000000,"longitudeE7":-660000000,"timestampMs":0,"accuracy":99}]}"""))!!.join()
            val review=controller.state.value.review!!;assertEquals(1,review.points);assertEquals(2,review.repeated)
            controller.discard();assertNull(controller.commit(review.id,HistoryImportPolicy.keepExisting));assertTrue(target.applied.isEmpty())
        } finally {controller.close()}
    }
    @Test fun sameArrivalAndLocationDoNotCollapseVisitsWithDifferentDepartureIntervals():Unit=runBlocking {
        val target=Target();val controller=HistoryImportController(target,memoryImportWorkspaces())
        val rows=listOf("01","02").joinToString(",") {hour->"""{"startTime":"2020-01-01T00:00:00Z","endTime":"2020-01-01T$hour:00:00Z","visit":{"topCandidate":{"placeLocation":"geo:18,-66"}}}"""}
        try {
            controller.select(input("[$rows]"))!!.join();val review=controller.state.value.review!!
            assertEquals(2,review.visits);assertEquals(0,review.repeated)
            controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join()
            assertEquals(2,target.applied.size);assertEquals(2,target.applied.map {it.id}.toSet().size)
        } finally {controller.close()}
    }
}
