package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class RoadConsentScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val settings=MutableStateFlow(AppSettings())
    private val journal=MutableStateFlow(JournalClearState(generation="synthetic"))
    private lateinit var session:RoadConsentSession
    @Volatile private var fail=false
    private fun setup(provider:Boolean=true) {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        session=RoadConsentSession(if(provider) RoadProviderDisclosure("Synthetic routing","https://example.test/privacy") else null,{provider},journal,{change->
            if(fail) error("synthetic storage failure")
            settings.value=change(settings.value).copy(roadAccessRevision=java.util.UUID.randomUUID().toString())
        })
    }
    @After fun close() {if(::session.isInitialized) session.close()}
    @Composable private fun Content(spanish:Boolean=false) {
        val base=LocalContext.current;val config=LocalConfiguration.current
        val translated=remember(config,spanish) {android.content.res.Configuration(config).apply {if(spanish) setLocales(android.os.LocaleList.forLanguageTags("es"))}}
        val context=remember(base,translated) {base.createConfigurationContext(translated)}
        val value by settings.collectAsState();val clear by journal.collectAsState()
        CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides translated) {
            MaterialTheme(colorScheme=if(spanish) darkColorScheme() else lightColorScheme()) {
                SideEffect {androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).apply {
                    isAppearanceLightStatusBars=!spanish;isAppearanceLightNavigationBars=!spanish
                }}
                Surface(Modifier.fillMaxSize()) {Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    RoadSettingsContent(session,value,RoadDisplayControllerState(configured=session.provider!=null,loading=false,journalReady=true),clear,{})
                }}
            }
        }
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureRoadConsent")!="true") return
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300,5000)
        val frames=java.util.concurrent.atomic.AtomicInteger()
        val ticker=object:android.view.Choreographer.FrameCallback {
            override fun doFrame(time:Long) {if(frames.incrementAndGet()<3) android.view.Choreographer.getInstance().postFrameCallback(this)}
        }
        compose.runOnIdle {android.view.Choreographer.getInstance().postFrameCallback(ticker)}
        try {compose.waitUntil(5000) {frames.get()>=3}}
        finally {compose.runOnIdle {android.view.Choreographer.getInstance().removeFrameCallback(ticker)}}
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            if(name.startsWith("spanish-dark")) {
                val background=bitmap.getPixel(bitmap.width-20,bitmap.height-250)
                assertTrue("Dark surface must reach the displayed frame",android.graphics.Color.red(background)<100 && android.graphics.Color.green(background)<100 && android.graphics.Color.blue(background)<100)
            }
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-road-consent-$name-20261030.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {bitmap.recycle()}
    }
    @Test fun explicitConfirmationCancelAndDisableUseTheRealSession() {
        setup();compose.setContent {Content()}
        compose.onNodeWithTag("road-consent-switch").assertIsOff().performClick()
        compose.onNodeWithTag("road-consent-disclosure").assertIsDisplayed();assertFalse(settings.value.roadDisplayEnabled)
        capture("english-review")
        compose.onNodeWithText("Not now").performClick();assertFalse(settings.value.roadDisplayEnabled)
        compose.onNodeWithTag("road-consent-switch").performClick();compose.onNodeWithTag("road-consent-confirm").performClick()
        compose.waitUntil(5000) {settings.value.roadDisplayEnabled}
        compose.onNodeWithTag("road-consent-switch").assertIsOn().performClick()
        compose.waitUntil(5000) {!settings.value.roadDisplayEnabled}
    }
    @Test fun unavailableBuildCanOnlyTurnOffAnExistingPreference() {
        setup(false);compose.setContent {Content()}
        compose.onNodeWithTag("road-consent-switch").assertIsOff().assertIsNotEnabled();capture("unavailable")
        compose.runOnIdle {settings.value=settings.value.copy(roadDisplayEnabled=true)}
        compose.onNodeWithTag("road-consent-switch").assertIsEnabled().performClick()
        compose.waitUntil(5000) {!settings.value.roadDisplayEnabled}
        compose.onNodeWithTag("road-consent-switch").assertIsNotEnabled()
    }
    @Test fun storageFailureKeepsOffAndRetryNeedsNewConfirmation() {
        setup();fail=true;compose.setContent {Content()}
        compose.onNodeWithTag("road-consent-switch").performClick();compose.onNodeWithTag("road-consent-confirm").performClick()
        compose.waitUntil(5000) {session.state.value.failure==RoadConsentFailure.storage}
        compose.onNodeWithText(target.getString(R.string.road_consent_storage_error)).performScrollTo().assertIsDisplayed();capture("storage-error")
        assertFalse(settings.value.roadDisplayEnabled);fail=false
        compose.onNodeWithTag("road-consent-switch").performScrollTo().performClick()
        assertFalse(settings.value.roadDisplayEnabled);compose.onNodeWithTag("road-consent-confirm").performClick()
        compose.waitUntil(5000) {settings.value.roadDisplayEnabled}
    }
    @Test fun spanishDarkDisclosureAndConfirmationRemainReachable() {
        setup();compose.setContent {Content(true)}
        compose.onNodeWithTag("road-consent-switch").performScrollTo().assertIsDisplayed();capture("spanish-dark-top")
        val context=target.createConfigurationContext(android.content.res.Configuration(target.resources.configuration).apply {setLocales(android.os.LocaleList.forLanguageTags("es"))})
        compose.onNodeWithText(context.getString(R.string.road_settings_revoke)).performScrollTo().assertIsDisplayed();capture("spanish-dark-bottom")
        compose.onNodeWithTag("road-consent-switch").performScrollTo().performClick()
        compose.onNodeWithTag("road-consent-confirm").assertTextEquals(context.getString(R.string.road_consent_allow)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.road_consent_title)).assertIsDisplayed();capture("spanish-dark-review")
        compose.onNode(hasText(context.getString(R.string.road_settings_revoke)) and hasAnyAncestor(hasTestTag("road-consent-disclosure"))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("road-consent-disclosure").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {it(0f,100000f)}
        compose.onNodeWithTag("road-consent-confirm").assertIsDisplayed();capture("spanish-dark-review-bottom")
        compose.onNodeWithTag("road-consent-confirm").performClick();compose.waitUntil(5000) {settings.value.roadDisplayEnabled}
    }
    @Test fun actualSettingsNavigationReportsTheUnconfiguredProductionBuild() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(target)
        compose.setContent {MaterialTheme {SettingsScreen(graph,AppSettings(onboarded=true),emptyList(),0)}}
        compose.waitUntil(10000) {!graph.journalClear.state.value.busy}
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(target.getString(R.string.road_settings_title)))
        compose.onNodeWithText(target.getString(R.string.road_settings_title)).performClick()
        compose.onNodeWithTag("road-settings-screen").assertExists()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("road-consent-switch").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("road-consent-switch").assertIsOff().assertIsNotEnabled()
        kotlinx.coroutines.runBlocking {graph.preferences.update {it.copy(roadDisplayEnabled=true)}}
        compose.waitUntil(5000) {graph.roadDisplay.state.value.enabled}
        compose.onNodeWithTag("road-consent-switch").assertIsOn().assertIsEnabled().performClick()
        compose.waitUntil(5000) {!graph.roadDisplay.state.value.enabled}
        assertFalse(kotlinx.coroutines.runBlocking {graph.preferences.settings.first()}.roadDisplayEnabled)
    }
    @Test fun networkGateAndBackgroundingCannotApproveSharing() {
        setup();settings.value=settings.value.copy(networkGeocoding=false)
        compose.setContent {Content()}
        compose.onNodeWithTag("road-consent-switch").assertIsNotEnabled()
        compose.onNodeWithText(target.getString(R.string.road_settings_network)).performScrollTo().assertIsDisplayed();capture("network-off")
        compose.runOnIdle {settings.value=settings.value.copy(networkGeocoding=true)}
        compose.onNodeWithTag("road-consent-switch").performScrollTo().performClick()
        compose.onNodeWithTag("road-consent-confirm").assertExists()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(5000) {session.state.value.review==null}
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("road-consent-confirm").assertDoesNotExist();assertFalse(settings.value.roadDisplayEnabled)
    }

}
