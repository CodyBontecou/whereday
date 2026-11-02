package com.bontecou.whereday

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.PointSummary
import com.bontecou.whereday.platform.UsageAuthorization
import org.junit.*
import org.junit.Assert.*
import java.util.Locale

class LaunchLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun context(tag:String):Context {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val locale=Locale.forLanguageTag(tag)
        val configuration=Configuration(base.resources.configuration).apply {setLocales(LocaleList(locale));setLayoutDirection(locale);fontScale=1.8f}
        return base.createConfigurationContext(configuration)
    }
    @Composable private fun Localized(context:Context,content:@Composable ()->Unit) {
        val configuration=context.resources.configuration
        CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides configuration,
            LocalDensity provides Density(LocalDensity.current.density,configuration.fontScale),
            LocalLayoutDirection provides if(configuration.layoutDirection==android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            MaterialTheme(colorScheme=darkColorScheme(),content=content)
        }
    }
    private fun noOverflow(text:String,singleLine:Boolean=false) {
        val results=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(results)}
        assertTrue(results.isNotEmpty())
        // Text's paragraph may keep its parent's width while the node wraps its intrinsic text.
        // Check actual line extents/ellipsis, not didOverflowWidth's unused paragraph area.
        val fits=results.all {layout->
            (!singleLine || layout.lineCount==1) && layout.multiParagraph.height<=layout.size.height+1 &&
                (0 until layout.lineCount).all {line->!layout.isLineEllipsized(line) && layout.getLineRight(line)-layout.getLineLeft(line)<=layout.size.width+1}
        }
        if(!fits) capture("overflow-${text.hashCode().toUInt()}")
        assertTrue("Text lines overflow their rendered bounds: $text",fits)
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureLocalization")!="true") return
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-localization-$name-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        context.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
    @Test fun actualSpanishResourcesFormatCountsAndUnsupportedLocalesFallbackToEnglish() {
        val es=context("es")
        assertEquals("Mapa",es.getString(R.string.shell_map))
        assertEquals("Continuar",es.getString(R.string.common_continue))
        assertEquals("Ruta de la grabación actual: se muestran 2 de 2500 puntos.",es.getString(R.string.active_recording_map_summary,2,2500))
        assertEquals("1 visita",es.resources.getQuantityString(R.plurals.history_import_visit_count,1,1))
        assertEquals("2 visitas",es.resources.getQuantityString(R.plurals.history_import_visit_count,2,2))
        assertEquals("3 puntos",es.resources.getQuantityString(R.plurals.history_import_point_count,3,3))
        assertEquals("Your days, remembered",context("de").getString(R.string.onboarding_title_welcome))
    }
    private fun onboarding(tag:String) {
        val context=context(tag)
        val completed=mutableListOf<OnboardingDecision>()
        var locations=0;var notifications=0
        val tester=StateRestorationTester(compose)
        tester.setContent {Localized(context) {Onboarding({locations++},{notifications++},authorization=UsageAuthorization.foreground) {completed+=it}}}
        val titles=listOf(R.string.onboarding_title_welcome,R.string.onboarding_title_journal,R.string.onboarding_title_background,R.string.onboarding_title_notification,R.string.onboarding_title_ready)
        if(tag=="ar-XB") assertNotEquals("Your days, remembered",context.getString(titles.first()))
        for(i in titles.indices) {
            val title=context.getString(titles[i])
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed();noOverflow(title)
            if(i==2) compose.onNodeWithText(context.getString(R.string.onboarding_allow_location)).performScrollTo().performClick()
            if(i==3) compose.onNodeWithText(context.getString(R.string.onboarding_allow_notifications)).performScrollTo().performClick()
            if(i<4) compose.onNodeWithTag("onboarding-continue").performScrollTo().performClick()
        }
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOff().performClick()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOn().assertIsDisplayed()
        assertTrue(completed.isEmpty());assertEquals(1,locations);assertEquals(1,notifications)
        compose.onNodeWithTag("onboarding-continue").performScrollTo().assertIsDisplayed()
        noOverflow(context.getString(R.string.onboarding_start_finish))
        capture(tag)
        compose.onNodeWithTag("onboarding-continue").performClick()
        compose.waitUntil {completed.size==1};assertTrue(completed.single().start)
    }
    @Test fun spanishOnboardingWrapsAtLargeTextAndPreservesExplicitStartChoice()=onboarding("es")
    @Test fun rtlPseudolocaleOnboardingWrapsAndPreservesExplicitStartChoice()=onboarding("ar-XB")
    @Test fun spanishRecordingControlsUseLocalizedActionsWithoutImplicitRecording() {
        val context=context("es")
        var active by mutableStateOf(false);var clicks=0
        compose.setContent {Localized(context) {RecordingControls(active,null) {clicks++;active=!active}}}
        compose.onNodeWithText("Tu diario de ubicaciones").assertIsDisplayed()
        compose.onNodeWithText("Iniciar grabación").assertIsDisplayed().performClick()
        compose.onNodeWithText("Detener grabación").assertIsDisplayed();assertEquals(1,clicks)
    }
    @Test fun spanishStatisticsHaveDisjointBoundsAtLargeText() {
        val context=context("es")
        compose.setContent {Localized(context) {RecordingScreen(emptyList(),emptyList(),PointSummary(count=1_000_000,distanceMeters=12345678.0),true) {}}}
        val labels=listOf("Puntos registrados","Visitas","Distancia")
        val bounds=labels.map {text->noOverflow(text);compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot}
        assertTrue(bounds.zipWithNext().all {(a,b)->a.right<=b.left+.5f || a.bottom<=b.top+.5f})
        noOverflow("1000000",singleLine=true)
        noOverflow("12345,68 km",singleLine=true)
        capture("statistics-es")
    }
}
