package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.VisitMonitoringMode
import org.junit.*
import org.junit.Assert.*

class VisitMonitoringScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val target get()=instrumentation.targetContext
    private val spanish get()=InstrumentationRegistry.getArguments().getString("spanishVisits")=="true"
    private val mode=mutableStateOf(VisitMonitoringMode.off)
    private val requested=mutableStateOf(false)
    private val interrupted=mutableStateOf(false)
    private val busy=mutableStateOf(false)
    private val access=mutableStateOf(RecordingAccessState(LocationPrecision.none,false))
    private var starts=0;private var stops=0;private var permissions=0
    private val localized get()=target.createConfigurationContext(android.content.res.Configuration(target.resources.configuration).apply {
        if(spanish) setLocales(android.os.LocaleList.forLanguageTags("es"))
    })
    @Before fun isolated() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        InstrumentationRegistry.getArguments().getString("expectedVisitsFont")?.toFloat()?.let {
            assertEquals(it,android.provider.Settings.System.getFloat(target.contentResolver,"font_scale",1f),0.01f)
        }
    }
    private fun show() {compose.setContent {
        val configuration=android.content.res.Configuration(LocalConfiguration.current).apply {if(spanish) setLocales(android.os.LocaleList.forLanguageTags("es"))}
        CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides configuration) {
            MaterialTheme(colorScheme=if(spanish) darkColorScheme() else lightColorScheme()) {
                SideEffect {androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).apply {
                    isAppearanceLightStatusBars=!spanish;isAppearanceLightNavigationBars=!spanish
                }}
                Surface(Modifier.fillMaxSize()) {Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    VisitMonitoringContent(mode.value,requested.value,interrupted.value,busy.value,false,access.value,false,{},
                        {starts++},{stops++},{permissions++},{permissions++},{permissions++})
                }}
            }
        }
    }}
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureVisits")!="true") return
        compose.waitForIdle();instrumentation.uiAutomation.waitForIdle(300,5000)
        val frames=java.util.concurrent.atomic.AtomicInteger()
        val callback=object:android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos:Long) {if(frames.incrementAndGet()<3) android.view.Choreographer.getInstance().postFrameCallback(this)}
        }
        compose.runOnIdle {android.view.Choreographer.getInstance().postFrameCallback(callback)}
        compose.waitUntil(5000) {frames.get()>=3}
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,
            "whereday-visits-${if(spanish) "es-dark" else "en"}-${target.resources.configuration.fontScale}-$name-${System.nanoTime()}.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun deniedAccessCannotStartAndReviewNeverEnablesTheMode() {
        show();compose.onNodeWithTag("visits-start").assertIsNotEnabled()
        capture("off")
        compose.onNodeWithText(localized.getString(R.string.visits_location)).performScrollTo().performClick()
        assertEquals(1,permissions);assertEquals(0,starts)
        compose.onNodeWithTag("visits-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(localized.getString(R.string.visits_recovery)).performScrollTo().assertIsDisplayed();capture("recovery")
    }
    @Test fun interruptedStatusCanBeReviewedAndPreparingStopRemainsAvailable() {
        access.value=RecordingAccessState(LocationPrecision.approximate,false)
        requested.value=true;interrupted.value=true;show()
        compose.onNodeWithTag("visits-status").assertTextEquals(localized.getString(R.string.visits_interrupted))
        compose.onNodeWithTag("visits-start").performScrollTo().assertIsEnabled().performClick();assertEquals(1,starts)
        compose.runOnIdle {mode.value=VisitMonitoringMode.preparing;busy.value=true}
        compose.onNodeWithTag("visits-stop").performScrollTo().assertIsEnabled().performClick();assertEquals(1,stops)
        compose.onNodeWithText(localized.getString(R.string.visits_approximate)).performScrollTo().assertIsDisplayed();capture("approximate")
    }
    @Test fun balancedAndRouteHandoffStatusesStayDistinctAndStopIsReachable() {
        access.value=RecordingAccessState(LocationPrecision.precise,true)
        requested.value=true;mode.value=VisitMonitoringMode.balanced;show()
        compose.onNodeWithTag("visits-status").assertTextEquals(localized.getString(R.string.visits_active))
        compose.onNodeWithTag("visits-start").assertDoesNotExist()
        compose.onNodeWithTag("visits-stop").performScrollTo().assertIsDisplayed();capture("active")
        compose.runOnIdle {mode.value=VisitMonitoringMode.route}
        compose.onNodeWithTag("visits-status").performScrollTo().assertTextEquals(localized.getString(R.string.visits_route));capture("route")
        compose.onNodeWithTag("visits-stop").performScrollTo().performClick();assertEquals(1,stops)
    }
}
