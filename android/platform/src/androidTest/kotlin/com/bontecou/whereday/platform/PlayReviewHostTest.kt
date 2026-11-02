package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.ReviewMilestone
import com.google.android.play.core.review.*
import com.google.android.play.core.review.testing.FakeReviewManager
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import java.time.LocalDate
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
            var saved=ReviewMilestone(setOf(LocalDate.of(2026,10,28),LocalDate.of(2026,10,29)),true)
            val store=object:ReviewMilestoneStore {override suspend fun read()=saved;override suspend fun write(value:ReviewMilestone) {saved=value}}
            val controller=ReviewMilestoneController(store)
            try {
                withContext(Dispatchers.Main) {controller.maybeRequest(host);controller.maybeRequest(host)}
                assertEquals(1,launches.get());assertTrue(saved.requested)
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
