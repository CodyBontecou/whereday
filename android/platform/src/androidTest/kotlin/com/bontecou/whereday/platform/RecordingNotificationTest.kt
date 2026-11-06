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
    private fun context(tag:String="en-US"):android.content.Context {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val config=android.content.res.Configuration(base.resources.configuration).apply {setLocales(android.os.LocaleList.forLanguageTags(tag))}
        return base.createConfigurationContext(config)
    }
    @Test fun fullSessionDistanceUnitsCountdownAndLocale() {
        assertEquals("1.61 km · 1h 1m · 5,011 points · 9 min left",RecordingNotification.detail(context(),Duration.ofSeconds(3661),5011,1609.344,true,Duration.ofMinutes(9),Locale.US))
        assertEquals("1.00 mi · 0h 0m · 2 points",RecordingNotification.detail(context(),Duration.ZERO,2,1609.344,false,null,Locale.US))
        assertEquals("1,61 km · 0h 0m · 2 points",RecordingNotification.detail(context(),Duration.ZERO,2,1609.344,true,null,Locale.FRANCE))
    }
    @Test fun unknownInvalidDistanceAndClockSkewNeverInventProgress() {
        for(distance in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0)) {
            assertEquals("Distance updating · 0h 0m · 0 points · 0 min left",RecordingNotification.detail(context(),Duration.ofSeconds(-1),-1,distance,true,Duration.ofMinutes(-1),Locale.US))
        }
    }
    @Test fun spanishDetailsUseResourcePluralsNumbersAndCurrentConfiguration() {
        val es=context("es-ES")
        assertEquals("1,61 km · 1 h 1 min · ${java.text.NumberFormat.getIntegerInstance(es.resources.configuration.locales[0]).format(5011)} puntos · Quedan 9 min",RecordingNotification.detail(es,Duration.ofSeconds(3661),5011,1609.344,true,Duration.ofMinutes(9)))
        assertEquals("1,00 mi · 0 h 0 min · 1 punto · Queda 1 min",RecordingNotification.detail(es,Duration.ZERO,1,1609.344,false,Duration.ofMinutes(1)))
        assertEquals("Actualizando distancia · 0 h 0 min · 0 puntos · Quedan 0 min",RecordingNotification.detail(es,Duration.ofSeconds(-1),-1,Double.NaN,true,Duration.ofMinutes(-1)))
    }
    @Test fun hugeCountsRetainTheirLongValueAndUnsupportedLanguageUsesEnglishText() {
        val en=context("de-DE")
        val detail=RecordingNotification.detail(en,Duration.ZERO,Long.MAX_VALUE,null,true,null)
        assertTrue(detail.startsWith("Distance updating"));assertTrue(detail.contains(java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(Long.MAX_VALUE)+" points"))
    }
    @Test fun spanishLockScreenTextAndStopKeepPrivacyAndActionIdentity() {
        val es=context("es-ES")
        val notification=RecordingNotification.build(es,"test-recording","SYNTHETIC PRIVATE DETAILS")
        assertEquals("Whereday está grabando",notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("La grabación de ubicación está activa",notification.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(notification.publicVersion!!.extras.toString().contains("SYNTHETIC PRIVATE DETAILS"))
        assertEquals("Detener grabación",notification.actions.first().title)
        assertEquals("Registrar una parada",notification.actions[1].title);assertEquals(1,notification.publicVersion!!.actions.count {it.title=="Detener grabación"})
        assertEquals(notification.actions.first().actionIntent,notification.publicVersion!!.actions.first().actionIntent)
        assertEquals(es.packageName,notification.actions.first().actionIntent.creatorPackage)
    }
    @Test fun channelNameIsLocalizedWithoutChangingItsStableIdentityOrImportance() {
        val en=RecordingNotification.channel(context(),"recording")
        val es=RecordingNotification.channel(context("es-ES"),"recording")
        assertEquals("Location recording",en.name.toString());assertEquals("Grabación de ubicación",es.name.toString())
        assertEquals(en.id,es.id);assertEquals(android.app.NotificationManager.IMPORTANCE_LOW,es.importance)
    }
    @Test fun preparingNotificationDoesNotClaimActiveRecordingOrExposePrivateDetail() {
        for(tag in listOf("en-US","es-ES")) {
            val context=context(tag)
            val value=RecordingNotification.build(context,"test-recording","SYNTHETIC PRIVATE PREPARATION",preparing=true)
            assertEquals("Whereday",value.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(context.getString(R.string.notification_recording_preparing),value.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertFalse(value.publicVersion!!.extras.toString().contains("SYNTHETIC PRIVATE PREPARATION"))
            assertEquals(value.actions.first().actionIntent,value.publicVersion!!.actions.first().actionIntent)
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
            notification.actions.first().actionIntent.send()
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
            val action=value.actions.first();assertEquals("Stop recording",action.title)
            assertEquals(context.packageName,action.actionIntent.creatorPackage)
            if(Build.VERSION.SDK_INT>=31) assertTrue(action.actionIntent.isImmutable)
        }
    }
}
