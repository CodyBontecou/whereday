package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real production graph/build binding and settings only. No map session or route preparation is created. */
class GeoapifyRoutingConfigurationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph:AppGraph
    private var original:AppSettings?=null
    private val visible=mutableStateOf(true)
    @Before fun setup()=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        graph=AppGraph.from(target);original=graph.preferences.settings.first()
        graph.preferences.update {it.copy(networkGeocoding=true,roadDisplayEnabled=false)}
        Unit
    }
    @After fun cleanup()=runBlocking {
        compose.runOnIdle {visible.value=false};compose.waitForIdle()
        original?.let {saved->graph.preferences.update {saved}}
        Unit
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureRoutingConfiguration")!="true") return
        compose.waitForIdle()
        val screenshot=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-routing-config-$name-20261031.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.use {check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {screenshot.recycle()}
    }
    @Test fun productionBuildBindingRequiresFreshConsentWithoutCreatingARoutingSession() {
        val configured=com.bontecou.whereday.platform.BuildConfig.GEOAPIFY_ROUTING_AVAILABLE
        compose.waitUntil(5000) {!graph.roadDisplay.state.value.loading && !graph.roadDisplay.state.value.policyUnavailable && graph.roadDisplay.state.value.journalReady}
        assertEquals(configured,graph.roadDisplay.state.value.configured)
        val declaration=graph.roadConsentSession()
        try {
            if(configured) {
                assertEquals("Geoapify Routing",declaration.provider?.name)
                assertEquals("https://www.geoapify.com/privacy-policy/",declaration.provider?.privacyUrl)
            } else assertNull(declaration.provider)
        } finally {declaration.close()}
        compose.setContent {MaterialTheme {
            SideEffect {androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).apply {
                isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true
            }}
            Surface(Modifier.fillMaxSize()) {Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if(visible.value) RoadSettingsScreen(graph,{})
            }}
        }}
        compose.onNodeWithTag("road-consent-switch").assertIsOff()
        if(configured) {
            compose.onNodeWithTag("road-consent-switch").assertIsEnabled().performClick()
            compose.onNodeWithTag("road-consent-disclosure").assertExists()
            compose.onNode(hasText(target.getString(R.string.road_settings_sharing,"Geoapify Routing")) and hasAnyAncestor(hasTestTag("road-consent-disclosure"))).assertExists()
            assertFalse(runBlocking {graph.preferences.settings.first()}.roadDisplayEnabled)
            capture("enabled-review")
            compose.onNodeWithText(target.getString(R.string.road_consent_cancel)).performClick()
            assertFalse(runBlocking {graph.preferences.settings.first()}.roadDisplayEnabled)
            compose.onNodeWithTag("road-consent-switch").performClick()
            compose.onNodeWithTag("road-consent-confirm").performClick()
            compose.waitUntil(5000) {graph.roadDisplay.state.value.enabled}
            compose.onNodeWithTag("road-consent-switch").assertIsOn().performClick()
            compose.waitUntil(5000) {!graph.roadDisplay.state.value.enabled}
        } else {
            compose.onNodeWithTag("road-consent-switch").assertIsNotEnabled()
            compose.onNodeWithText(target.getString(R.string.road_settings_unavailable)).assertExists()
            capture("disabled")
        }
        assertFalse(runBlocking {graph.preferences.settings.first()}.roadDisplayEnabled)
        assertEquals(0,graph.roadDisplay.state.value.foregroundSessions)
    }
}
