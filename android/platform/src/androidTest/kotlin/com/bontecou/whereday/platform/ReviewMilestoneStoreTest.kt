package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.ReviewMilestone
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReviewMilestoneStoreTest {
    private val now=Instant.parse("2026-11-21T12:00:00Z")
    private val clock=Clock.fixed(now,ZoneOffset.UTC)
    @Test fun atomicPrivateStoreRoundTripsAndClearKeepsTheLastRequest()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val path=File(context.filesDir,"review-test-${UUID.randomUUID()}/state.json")
        try {
            val store=AndroidReviewMilestoneStore(path,clock);assertEquals(ReviewMilestone(),store.read())
            val saved=ReviewMilestone(2,LocalDate.of(2026,10,29),"1.4.0",now)
            store.write(saved);assertEquals(saved,AndroidReviewMilestoneStore(path,clock).read());assertTrue(path.length()<4096)
            val unasked=ReviewMilestone(1,LocalDate.of(2026,10,29));store.write(unasked);assertEquals(unasked,store.read())
            store.write(saved)
            val controller=ReviewMilestoneController(store,"1.4.0",clock)
            try {controller.clearJournalMilestones();assertEquals(ReviewMilestone(requestedVersion="1.4.0",requestedAt=now),store.read())} finally {controller.close()}
        } finally {path.parentFile!!.deleteRecursively()}
    }
    @Test fun versionOneStateMigratesOnceAndAnAlreadyAskedUserStartsTheCooldownNow()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val path=File(context.filesDir,"review-test-${UUID.randomUUID()}/state.json");path.parentFile!!.mkdirs()
        try {
            path.writeText("""{"version":1,"days":["2026-10-28","2026-10-29"],"exported":true,"requested":true}""")
            val migrated=AndroidReviewMilestoneStore(path,clock).read()
            assertEquals(ReviewMilestone(2,LocalDate.of(2026,10,29),null,now),migrated)
            assertTrue("rewritten as version 2",path.readText().contains("\"version\":2"))
            val later=Clock.fixed(now.plusSeconds(3600),ZoneOffset.UTC)
            assertEquals("migration doesn't restart the cooldown",now,AndroidReviewMilestoneStore(path,later).read().requestedAt)

            path.writeText("""{"version":1,"days":["2026-10-28"],"exported":false,"requested":false}""")
            assertEquals(ReviewMilestone(1,LocalDate.of(2026,10,28)),AndroidReviewMilestoneStore(path,clock).read())
        } finally {path.parentFile!!.deleteRecursively()}
    }
    @Test fun corruptOrOversizedPreferencesFailClosedInsteadOfInventingAnEligibleMilestone()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val path=File(context.filesDir,"review-test-${UUID.randomUUID()}/state.json");path.parentFile!!.mkdirs()
        try {
            val store=AndroidReviewMilestoneStore(path,clock)
            for(raw in listOf("not json","x".repeat(4097),"""{"version":3,"useDays":2}""","""{"version":1,"days":[],"exported":"true","requested":false}""",
                """{"version":2,"useDays":31,"lastUseDay":"2026-10-28","requestedVersion":null,"requestedAt":null}""",
                """{"version":2,"useDays":2,"lastUseDay":null,"requestedVersion":null,"requestedAt":null}""",
                """{"version":2,"useDays":"2","lastUseDay":"2026-10-28","requestedVersion":null,"requestedAt":null}""")) {
                path.writeText(raw);var failed=false;try {store.read()} catch(_:Exception) {failed=true};assertTrue(raw,failed)
            }
            var launches=0;val controller=ReviewMilestoneController(store,"1.0",clock)
            try {
                controller.succeeded(com.bontecou.whereday.domain.ReviewSuccess.fileExport);delay(100)
                controller.maybeRequest(object:ReviewPromptHost {override fun ready()=true;override suspend fun prepare()=ReviewPromptLaunch {launches++}})
                assertEquals(0,launches)
                controller.clearJournalMilestones();assertEquals(ReviewMilestone(requestedAt=now),store.read())
            } finally {controller.close()}
        } finally {path.parentFile!!.deleteRecursively()}
    }
}
