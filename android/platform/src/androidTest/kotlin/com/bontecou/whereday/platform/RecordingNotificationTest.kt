package com.bontecou.whereday.platform

import android.app.Notification
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.*
import java.time.Duration
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RecordingNotificationTest {
    @Test fun fullSessionDistanceUnitsCountdownAndLocale() {
        assertEquals("1.61 km · 1h 1m · 5011 points · 9 min left",RecordingNotification.detail(Duration.ofSeconds(3661),5011,1609.344,true,Duration.ofMinutes(9),Locale.US))
        assertEquals("1.00 mi · 0h 0m · 2 points",RecordingNotification.detail(Duration.ZERO,2,1609.344,false,null,Locale.US))
        assertEquals("1,61 km · 0h 0m · 2 points",RecordingNotification.detail(Duration.ZERO,2,1609.344,true,null,Locale.FRANCE))
    }
    @Test fun unknownInvalidDistanceAndClockSkewNeverInventProgress() {
        for(distance in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0)) {
            assertEquals("Distance updating · 0h 0m · 0 points · 0 min left",RecordingNotification.detail(Duration.ofSeconds(-1),-1,distance,true,Duration.ofMinutes(-1),Locale.US))
        }
    }
    @Test fun notificationStopPendingIntentClosesTheIsolatedSessionWithoutAnActivity()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val graph=AppGraph.from(context)
        assertFalse(graph.history.dao.recording()?.requested ?: false)
        val session=graph.history.start(0).sessionId!!
        try {
            val notification=RecordingNotification.build(context,"test-recording","Synthetic only")
            notification.actions.single().actionIntent.send()
            withTimeout(10_000) {
                while(graph.history.dao.recording()?.requested==true) delay(25)
            }
            assertNotNull(graph.history.dao.session(session)!!.value.endedAt)
            assertFalse(RecordingRuntime.active.value)
        } finally {graph.history.stop();graph.history.deleteSession(session)}
    }
    @Test fun lockScreenVersionOmitsStatisticsAndBothVersionsKeepAnImmutableStopAction() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val detail="Synthetic distance 123.45 km and 42 points"
        val notification=RecordingNotification.build(context,"test-recording",detail)
        assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility)
        assertEquals(detail,notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(detail,notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        val public=notification.publicVersion!!
        assertEquals(Notification.VISIBILITY_PUBLIC,public.visibility)
        assertEquals("Location recording is active",public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(public.extras.toString().contains(detail))
        for(value in listOf(notification,public)) {
            assertTrue(value.flags and Notification.FLAG_ONGOING_EVENT !=0)
            val action=value.actions.single();assertEquals("Stop",action.title)
            assertEquals(context.packageName,action.actionIntent.creatorPackage)
            if(Build.VERSION.SDK_INT>=31) assertTrue(action.actionIntent.isImmutable)
        }
    }
}
