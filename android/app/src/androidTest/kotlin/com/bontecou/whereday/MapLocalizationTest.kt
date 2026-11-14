package com.bontecou.whereday

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.bontecou.whereday.domain.*
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.maps.MapView
import java.time.*
import java.util.Locale

@SdkSuppress(minSdkVersion=33)
class MapLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var originalLocales:LocaleList?=null
    @Before fun guard() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val manager=target.getSystemService(android.app.LocaleManager::class.java)
        originalLocales=manager.applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun restoreLocale() {
        originalLocales?.let {applyQaAppLocales(compose,it)}
    }
    private fun context(tag:String):Context {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {setLocales(LocaleList(Locale.forLanguageTag(tag)))})
    }
    @Composable private fun Localized(context:Context,large:Boolean=false,content:@Composable ()->Unit) {
        CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides context.resources.configuration,
            LocalDensity provides Density(LocalDensity.current.density,if(large) 1.8f else 1f)) {MaterialTheme(content=content)}
    }
    @Test fun translatedDateLabelsReturnStableKeysAndSurviveLocaleChangesAndRestoration() {
        val es=context("es");val en=context("en")
        var spanish by mutableStateOf(true)
        val picked=mutableListOf<String>()
        var selectedKey=""
        val tester=StateRestorationTester(compose)
        tester.setContent {Localized(if(spanish) es else en) {
            var selected by rememberSaveable {mutableStateOf("Today")}
            selectedKey=selected
            MapDateFilter(selected) {selected=it;picked+=it}
        }}
        val labels=listOf("Hoy","Ayer","7 días","30 días","Todo","Personalizado")
        for((index,key) in mapDatePresets.withIndex()) {
            compose.onNodeWithText(labels.getOrElse(index-1) {"Hoy"}).performClick()
            compose.onNode(hasText(labels[index]) and hasAnyAncestor(isPopup())).performClick()
            compose.runOnIdle {assertEquals(key,picked.last());assertEquals(key,selectedKey)}
        }
        tester.emulateSavedInstanceStateRestore();assertEquals("Custom",selectedKey)
        compose.runOnIdle {spanish=false}
        compose.onNodeWithText("Custom").assertIsDisplayed();assertEquals("Custom",selectedKey)
    }
    @Test fun customRangeKeepsCivilDatesWhileTranslatingActions() {
        val first=LocalDate.of(2026,12,31);val last=LocalDate.of(2027,1,2)
        var result:Pair<LocalDate,LocalDate>?=null
        compose.setContent {Localized(context("es")) {CustomDateRange(first,last,{}) {a,b->result=a to b}}}
        compose.onNodeWithText("Elegir fechas").assertIsDisplayed()
        compose.onNodeWithText("Aplicar").assertIsEnabled().performClick()
        assertEquals(first to last,result)
    }
    @Test fun spanishMapCountsSelectSingularAndPluralForms() {
        val resources=context("es").resources
        assertEquals("1 marcador GPS.",resources.getQuantityString(R.plurals.map_a11y_points,1,1))
        assertEquals("2 marcadores GPS.",resources.getQuantityString(R.plurals.map_a11y_points,2,2))
        assertEquals("1 foto.",resources.getQuantityString(R.plurals.map_a11y_photos,1,1L))
        assertEquals("5000 fotos.",resources.getQuantityString(R.plurals.map_a11y_photos,5000,5000L))
    }
    private fun View.mapView():MapView?=if(this is MapView) this else if(this is ViewGroup) (0 until childCount).firstNotNullOfOrNull {getChildAt(it).mapView()} else null
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureMapLocalization")!="true") return
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-map-localization-$name-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
    @Test fun spanishLayerChoicesUpdateActualMapAccessibilityWithoutChangingRawHistory() {
        val at=Instant.parse("2026-10-29T12:00:00Z")
        val points=listOf(TrackPoint("one",Coordinate(48.8584,2.2945),at,5.0))
        compose.setContent {Localized(context("es")) {Column(Modifier.verticalScroll(rememberScrollState())) {JournalMap(points,emptyList(),onOutliers={})}}}
        compose.onNodeWithText("Capas del mapa").performScrollTo().performClick()
        compose.onNodeWithTag("map-layer-points").assertIsOn().performClick()
        compose.onNodeWithTag("map-layer-path").performClick()
        compose.onNodeWithText("Terminar con las capas").performClick()
        compose.waitUntil(10000) {compose.runOnIdle {compose.activity.window.decorView.mapView()?.contentDescription?.contains("Ruta recorrida oculta.")==true}}
        val description=compose.runOnIdle {compose.activity.window.decorView.mapView()!!.contentDescription.toString()}
        assertTrue(description.contains("0 marcadores GPS."));assertTrue(description.contains("Mapa del diario."));assertFalse(description.contains("Journal map"))
        assertEquals(1,points.size);assertEquals(Coordinate(48.8584,2.2945),points.single().coordinate)
    }
    @Test fun longSpanishMapControlsStayReachableAtLargeText() {
        compose.setContent {Localized(context("es"),large=true) {Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {JournalMap(emptyList(),emptyList())}}}
        for(label in listOf("Mapa en línea","Capas del mapa","Ajustar a la ruta")) {
            compose.onNodeWithText(label).performScrollTo()
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        capture("controls-large")
        compose.onNodeWithText("Capas del mapa").performScrollTo().performClick()
        compose.onNodeWithTag("map-layer-endpoints").performScrollTo().assertIsDisplayed()
        capture("layers-large")
        compose.onNodeWithText("Terminar con las capas").assertIsDisplayed().performClick()
    }
}
