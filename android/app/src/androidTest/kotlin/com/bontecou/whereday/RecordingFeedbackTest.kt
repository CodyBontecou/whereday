package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.RecordingRow
import com.bontecou.whereday.domain.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

@SdkSuppress(minSdkVersion=33)
class RecordingFeedbackTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocales:LocaleList?=null
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private val labels=mapOf(
        RecordingInterruption.historyUnavailable to R.string.recording_interruption_history,
        RecordingInterruption.locationUnavailable to R.string.recording_interruption_location,
        RecordingInterruption.permissionRemoved to R.string.recording_interruption_permission,
        RecordingInterruption.startInterrupted to R.string.recording_interruption_start,
        RecordingInterruption.startFailed to R.string.recording_interruption_start_failed,
        RecordingInterruption.processInterrupted to R.string.recording_interruption_process,
        RecordingInterruption.phoneRestarted to R.string.recording_interruption_reboot)
    @Test fun stableAndLegacyReasonsBothRenderInTheCurrentLanguageWithoutWritingTheirInput() {
        val stored=mutableStateOf(RecordingInterruption.historyUnavailable.code);var commands=0
        compose.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {RecordingControls(false,RecordingRow(interruption=stored.value)) {commands++}}}}
        for(reason in RecordingInterruption.entries) for(value in listOf(reason.code,reason.diagnostic)) {
            compose.runOnIdle {stored.value=value}
            compose.onNodeWithTag("recording-interruption").performScrollTo().assertTextEquals(target.getString(labels.getValue(reason)))
            assertEquals(value,stored.value)
        }
        assertEquals(0,commands)
    }
    @Test fun unknownStoredTextIsSanitizedAndRequestedStateCannotPretendTheServiceIsActive() {
        val raw="synthetic-private-file and coordinates 12.345,67.890"
        var commands=0
        compose.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {RecordingControls(false,RecordingRow(requested=true,sessionId="synthetic",interruption=raw)) {commands++}}}}
        compose.onNodeWithTag("recording-interruption").performScrollTo().assertTextEquals("Se interrumpió la grabación. Comprueba su estado antes de volver a iniciarla.")
        compose.onNodeWithText(raw,substring=true).assertDoesNotExist()
        compose.onNodeWithText("Detener grabación").assertDoesNotExist()
        compose.onNodeWithText("Iniciar grabación").performScrollTo().assertIsDisplayed();assertEquals(0,commands)
        compose.onNodeWithText("Iniciar grabación").performClick();assertEquals(1,commands)
    }
    @Test fun persistedReasonRelocalizesWithoutChangingItsIdentity() {
        val es=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("es"))})
        val en=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("en"))})
        var spanish by mutableStateOf(true)
        val stored=RecordingInterruption.phoneRestarted.code
        compose.setContent {val context=if(spanish) es else en;CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides context.resources.configuration) {Text(recordingInterruptionText(stored))}}
        compose.onNodeWithText("El teléfono se reinició. Abre Whereday para volver a grabar.").assertExists()
        compose.runOnIdle {spanish=false}
        compose.onNodeWithText("Your phone restarted. Open Whereday to resume recording.").assertExists()
        assertEquals(RecordingInterruption.phoneRestarted,RecordingInterruption.decode(stored))
    }
    @Test fun routePreviewUsesTranslatedEmptyAndPluralLabelsWithoutCountingOutliers() {
        val points=mutableStateOf<List<TrackPoint>>(emptyList())
        compose.setContent {MaterialTheme {RoutePreview(points.value)}}
        compose.onNodeWithText("Ruta grabada").assertExists();compose.onNodeWithText("Todavía no hay ninguna ruta grabada").assertExists()
        val point=TrackPoint("synthetic",Coordinate(0.0,0.0),Instant.parse("2026-10-29T12:00:00Z"),5.0)
        compose.runOnIdle {points.value=listOf(point,point.copy(id="outlier",outlier=true))}
        compose.onNodeWithText("Traza GPS original · 1 punto mostrado").assertExists()
        compose.runOnIdle {points.value=listOf(point,point.copy(id="second",time=point.time.plusSeconds(1)))}
        compose.onNodeWithText("Traza GPS original · 2 puntos mostrados").assertExists()
    }
    @Test fun activityFailureResourcesAreLocalizedAndKeepTheSameRecoveryMeaning() {
        assertEquals("No se pudo actualizar el historial de visitas. Prueba a volver a abrir la app.",target.getString(R.string.recording_reconciliation_failed))
        assertEquals("No se pudo detener la grabación. Comprueba su estado e inténtalo de nuevo.",target.getString(R.string.recording_stop_failed))
        assertEquals("No se pudo iniciar la grabación. Comprueba el acceso a la ubicación e inténtalo de nuevo.",target.getString(R.string.recording_permission_start_failed))
    }
    @Test fun largeTextInterruptionRemainsReadableAndNeverStartsRecordingByItself() {
        var commands=0
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Surface(Modifier.fillMaxSize().systemBarsPadding()) {Column(Modifier.verticalScroll(rememberScrollState())) {RecordingControls(false,RecordingRow(interruption=RecordingInterruption.locationUnavailable.code)) {commands++}}}}}
        compose.onNodeWithTag("recording-interruption").performScrollTo().assertIsDisplayed();assertEquals(0,commands)
        if(InstrumentationRegistry.getArguments().getString("captureRecordingFeedback")=="true") capture()
        compose.onNodeWithText("Iniciar grabación").performScrollTo().assertIsDisplayed();assertEquals(0,commands)
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-recording-feedback-large-20261030.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
}
