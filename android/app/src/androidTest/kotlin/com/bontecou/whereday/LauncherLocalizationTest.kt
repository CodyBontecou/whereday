package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

@SdkSuppress(minSdkVersion=33)
class LauncherLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocales:LocaleList?=null
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun noOverflow(text:String) {
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
        assertTrue(layouts.isNotEmpty())
        // A button can retain a wider paragraph than its intrinsic node; check actual line geometry.
        assertTrue(layouts.joinToString {"size=${it.size} lines=${it.lineCount}"},layouts.all {layout->
            layout.multiParagraph.height<=layout.size.height+1 && (0 until layout.lineCount).all {line->
                !layout.isLineEllipsized(line) && layout.getLineRight(line)-layout.getLineLeft(line)<=layout.size.width+1
            }
        })
    }
    @Test fun spanishStartExplainsBackgroundCaptureAndRequiresASingleExplicitChoice() {
        val calls=mutableListOf<Boolean>();var dismissals=0
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {LauncherRecordingConfirmation(LauncherAction.start,false,{dismissals++}) {calls.add(it)}}}
        compose.onNodeWithText("¿Iniciar grabación?").assertIsDisplayed()
        val body="Whereday guardará tu ubicación durante la grabación, incluso con la pantalla apagada. Puedes detenerla en cualquier momento."
        compose.onNodeWithText(body).assertIsDisplayed();noOverflow(body)
        capture("start");compose.onNodeWithText("Iniciar grabación").assertIsDisplayed();noOverflow("Iniciar grabación")
        compose.onNodeWithText("Cancelar").assertIsDisplayed();assertTrue(calls.isEmpty())
        compose.onNodeWithText("Iniciar grabación").performClick();assertEquals(listOf(true),calls);assertEquals(0,dismissals)
    }
    @Test fun spanishStopPreservesHistoryAndCancelDoesNotDispatch() {
        var commands=0;var dismissals=0
        compose.setContent {MaterialTheme {LauncherRecordingConfirmation(LauncherAction.stop,true,{dismissals++}) {commands++}}}
        compose.onNodeWithText("¿Detener grabación?").assertIsDisplayed()
        compose.onNodeWithText("Finaliza la grabación actual y conserva el historial guardado.").assertIsDisplayed()
        compose.onNodeWithText("Detener grabación").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick();assertEquals(0,commands);assertEquals(1,dismissals)
    }
    @Test fun spanishAlreadyActiveAndStoppedOnlyDismissWithoutRecordingCommands() {
        val action=mutableStateOf(LauncherAction.start);val active=mutableStateOf(true);var dismissals=0
        compose.setContent {MaterialTheme {LauncherRecordingConfirmation(action.value,active.value,{dismissals++}) {fail("Already-current state dispatched")}}}
        compose.onNodeWithText("La grabación ya está activa").assertIsDisplayed()
        compose.onNodeWithText("Listo").performClick();assertEquals(1,dismissals)
        compose.runOnIdle {action.value=LauncherAction.stop;active.value=false}
        compose.onNodeWithText("La grabación está detenida").assertIsDisplayed()
        compose.onNodeWithText("Listo").performClick();assertEquals(2,dismissals)
    }
    @Test fun commandProgressKeepsCancelOutsideTheScrollOnAShortWindow() {
        var canceled=0
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Box(Modifier.fillMaxWidth().height(300.dp)) {AutomationCommandContent {canceled++}}}}
        val title="Completando el comando de Whereday"
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed();noOverflow(title)
        val help="Puedes cancelar y consultar el diario si se interrumpe el resultado."
        compose.onNodeWithText(help).performScrollTo().assertIsDisplayed();noOverflow(help)
        val body=compose.onNodeWithTag("automation-command-body")
        body.performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,10000f)}
        compose.waitForIdle()
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(help,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
        val text=compose.onNodeWithText(help,useUnmergedTree=true).fetchSemanticsNode()
        val viewport=body.fetchSemanticsNode().boundsInRoot
        assertTrue(text.positionInRoot.y+layouts.single().getLineBottom(layouts.single().lineCount-1)<=viewport.bottom+1)
        compose.onNodeWithTag("automation-command-cancel").assertIsDisplayed()
        val cancelBounds=compose.onNodeWithTag("automation-command-cancel").fetchSemanticsNode().boundsInRoot
        assertTrue(viewport.bottom<=cancelBounds.top+1)
        capture("command")
        compose.onNodeWithTag("automation-command-cancel").performClick();assertEquals(1,canceled)
    }
    @Test fun commandProgressUsesTheCurrentLocaleWithoutInvokingCancel() {
        var canceled=0
        val es=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("es"))})
        val en=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("en"))})
        var spanish by mutableStateOf(true)
        compose.setContent {val context=if(spanish) es else en;CompositionLocalProvider(
            androidx.compose.ui.platform.LocalContext provides context,
            androidx.compose.ui.platform.LocalConfiguration provides context.resources.configuration
        ) {MaterialTheme {AutomationCommandContent {canceled++}}}}
        compose.onNodeWithText("Completando el comando de Whereday").assertExists()
        compose.runOnIdle {spanish=false}
        compose.onNodeWithText("Completing Whereday command").assertExists();assertEquals(0,canceled)
        compose.onNodeWithText("Cancel").performClick();assertEquals(1,canceled)
    }
    private fun capture(suffix:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        if(InstrumentationRegistry.getArguments().getString("captureLauncher")!="true") return
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-launcher-$suffix-large-20261030.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
}
