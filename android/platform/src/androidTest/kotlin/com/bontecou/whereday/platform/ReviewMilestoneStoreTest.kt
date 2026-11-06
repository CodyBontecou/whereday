package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.ReviewMilestone
import java.time.LocalDate
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReviewMilestoneStoreTest {
    @Test fun atomicPrivateStoreRoundTripsAndReconstructionKeepsTheConsumedAttempt()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val path=File(context.filesDir,"review-test-${UUID.randomUUID()}/state.json")
        try {
            val store=AndroidReviewMilestoneStore(path);assertEquals(ReviewMilestone(),store.read())
            val saved=ReviewMilestone(setOf(LocalDate.of(2026,10,28),LocalDate.of(2026,10,29)),true,true)
            store.write(saved);assertEquals(saved,AndroidReviewMilestoneStore(path).read());assertTrue(path.length()<4096)
            val controller=ReviewMilestoneController(store)
            try {controller.clearJournalMilestones();assertEquals(ReviewMilestone(requested=true),store.read())} finally {controller.close()}
        } finally {path.parentFile!!.deleteRecursively()}
    }
    @Test fun corruptOrOversizedPreferencesFailClosedInsteadOfInventingAnEligibleMilestone()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val path=File(context.filesDir,"review-test-${UUID.randomUUID()}/state.json");path.parentFile!!.mkdirs()
        try {
            val store=AndroidReviewMilestoneStore(path)
            for(raw in listOf("not json","x".repeat(4097),"""{"version":2,"days":[],"exported":true,"requested":false}""", """{"version":1,"days":[],"exported":"true","requested":false}""")) {
                path.writeText(raw);var failed=false;try {store.read()} catch(_:Exception) {failed=true};assertTrue(failed)
            }
            var launches=0;val controller=ReviewMilestoneController(store)
            try {
                controller.maybeRequest(object:ReviewPromptHost {override fun ready()=true;override suspend fun prepare()=ReviewPromptLaunch {launches++}})
                assertEquals(0,launches)
                controller.clearJournalMilestones();assertEquals(ReviewMilestone(requested=true),store.read())
            } finally {controller.close()}
        } finally {path.parentFile!!.deleteRecursively()}
    }
}
