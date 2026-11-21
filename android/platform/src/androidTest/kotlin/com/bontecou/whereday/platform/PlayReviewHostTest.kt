package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.ReviewMilestone
import com.bontecou.whereday.domain.ReviewSuccess
import com.google.android.play.core.review.*
import com.google.android.play.core.review.testing.FakeReviewManager
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PlayReviewHostTest {
    @Test fun officialFakeManagerExercisesTheSdkBridgeWithoutARealRatingOrStoreRequest()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        ActivityScenario.launch<ReviewTestActivity>(Intent(context,ReviewTestActivity::class.java)).use {scenario->
            val activity=AtomicReference<ReviewTestActivity>();scenario.onActivity {activity.set(it)}
            val launches=AtomicInteger();val fake=FakeReviewManager(context)
            val manager=object:ReviewManager by fake {
                override fun launchReviewFlow(activity:Activity,info:ReviewInfo):Task<Void> {launches.incrementAndGet();return fake.launchReviewFlow(activity,info)}
            }
            val host=PlayReviewHost(activity.get(),true) {manager}
            withTimeout(5000) {while(!withContext(Dispatchers.Main) {host.ready()}) delay(10)}
            var saved=ReviewMilestone(1,LocalDate.of(2026,10,28))
            val store=object:ReviewMilestoneStore {override suspend fun read()=saved;override suspend fun write(value:ReviewMilestone) {saved=value}}
            val clock=Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"),ZoneOffset.UTC)
            val controller=ReviewMilestoneController(store,"1.0",clock,{ZoneOffset.UTC})
            try {
                // Resume-style use alone never reaches the SDK.
                controller.appUsed();withTimeout(5000) {while(controller.state.value.useDays!=2) delay(10)}
                withContext(Dispatchers.Main) {controller.maybeRequest(host)}
                assertEquals(0,launches.get())
                // A success moment does, once per version.
                controller.succeeded(ReviewSuccess.countryDaysExport);withTimeout(5000) {while(controller.pendingSuccess.value==null) delay(10)}
                withContext(Dispatchers.Main) {controller.maybeRequest(host);controller.maybeRequest(host)}
                assertEquals(1,launches.get());assertEquals("1.0",saved.requestedVersion);assertEquals(clock.instant(),saved.requestedAt)
                controller.succeeded(ReviewSuccess.fileExport);delay(100)
                withContext(Dispatchers.Main) {controller.maybeRequest(host)}
                assertEquals(1,launches.get())
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                assertFalse(withContext(Dispatchers.Main) {host.ready()})
            } finally {controller.close()}
        }
    }
    @Test fun unavailableBuildNeverConstructsAReviewManagerOrLaunchesTheSdk()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        ActivityScenario.launch<ReviewTestActivity>(Intent(context,ReviewTestActivity::class.java)).use {scenario->
            val activity=AtomicReference<ReviewTestActivity>();scenario.onActivity {activity.set(it)}
            var constructed=false;val host=PlayReviewHost(activity.get(),false) {constructed=true;error("Unexpected store access")}
            assertNull(withContext(Dispatchers.Main) {host.prepare()});assertFalse(constructed)
            assertFalse(PlayReviews.available)
        }
    }
}
