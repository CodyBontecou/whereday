package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*

/** No journal, provider, purchase, permission or external Activity is used by these presentation fixtures. */
@SdkSuppress(minSdkVersion=33)
class SettingsLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocales:LocaleList?=null
    private val settings=mutableStateOf(AppSettings(onboarded=true))
    private val commands=mutableStateOf(SettingsActionState())
    private val clear=mutableStateOf(JournalClearState())
    private val places=mutableIntStateOf(1)
    private val points=mutableLongStateOf(1)
    private val visible=mutableStateOf(true)
    private val updates=mutableListOf<AppSettings>()
    private val calls=mutableListOf<String>()
    private val destinations=mutableListOf<SettingsDestination>()
    private val actions=SettingsActions({change->settings.value=change(settings.value);updates.add(settings.value)},
        {calls.add("folder")},{calls.add("permissions")},{destinations.add(it)},{calls.add("clear")},{calls.add("dismiss:$it")},{calls.add("dismiss-feedback")})
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun show(dark:Boolean=false) {compose.setContent {MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {Surface(Modifier.systemBarsPadding()) {if(visible.value) SettingsContent(settings.value,places.intValue,points.longValue,commands.value,clear.value,actions)}}}}
    private fun row(tag:String) {compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag(tag))}
    private fun click(text:String) {compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(text));compose.onNodeWithText(text).performClick()}
    private fun choose(tag:String,current:String,next:String) {
        row(tag);compose.onNode(hasText(current) and hasAnyAncestor(hasTestTag(tag))).performClick()
        compose.onNode(hasText(next) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }
    private fun openClear() {row("settings-clear");compose.onNodeWithTag("settings-clear").performClick();compose.onNodeWithText("¿Borrar todos los datos del diario?").assertIsDisplayed()}
    private fun heldConfirm():()->Boolean=compose.onNodeWithTag("settings-clear-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    @Test fun singularAndGroupedCountsAreLocalizedWithoutExposingTheFolderUri() {
        settings.value=settings.value.copy(exportFolder="content://synthetic-private-folder/tree/qa")
        show();row("settings-place-count");compose.onNodeWithTag("settings-place-count").assertTextEquals("1 lugar guardado")
        row("settings-point-count");compose.onNodeWithTag("settings-point-count").assertTextContains("1 punto registrado.",substring=true)
        compose.runOnIdle {places.intValue=1001;points.longValue=1001}
        row("settings-place-count");compose.onNodeWithTag("settings-place-count").assertTextEquals("1.001 lugares guardados")
        row("settings-point-count");compose.onNodeWithTag("settings-point-count").assertTextContains("1.001 puntos registrados.",substring=true)
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Cambiar carpeta de exportación"))
        compose.onNodeWithText("Cambiar carpeta de exportación").assertIsDisplayed()
        compose.onNodeWithText("content://synthetic-private-folder/tree/qa").assertDoesNotExist()
        assertTrue(updates.isEmpty());assertTrue(calls.isEmpty())
    }
    @Test fun distanceAndStopChoicesKeepTheirRawValuesAndOtherPreferences() {
        settings.value=settings.value.copy(metric=false,appearance="dark",inferredGapMinutes=120)
        show();var distance=5
        for(next in listOf(10,25,50,100,200,5)) {choose("settings-distance","$distance m","$next m");distance=next;compose.runOnIdle {assertEquals(next,settings.value.distanceFilter)}}
        var hours=0;fun label(value:Int)=if(value==0) "Nunca" else "$value h"
        for(next in listOf(1,2,4,8,12,0)) {choose("settings-stop",label(hours),label(next));hours=next;compose.runOnIdle {assertEquals(next,settings.value.stopAfterHours)}}
        compose.runOnIdle {assertFalse(settings.value.metric);assertEquals("dark",settings.value.appearance);assertEquals(120,settings.value.inferredGapMinutes);assertTrue(calls.isEmpty())}
    }
    @Test fun allInferenceChoicesRemainStoredWhenTheirControlsAreHidden() {
        show();var gap=30
        for(next in listOf(15,60,120,240)) {choose("settings-inferred-gap","$gap min","$next min");gap=next;compose.runOnIdle {assertEquals(next,settings.value.inferredGapMinutes)}}
        var duration=0;fun label(value:Int)=if(value==0) "Ninguna" else "$value min"
        for(next in listOf(5,15,30,60)) {choose("settings-inferred-duration",label(duration),label(next));duration=next;compose.runOnIdle {assertEquals(next,settings.value.inferredMinimumMinutes)}}
        var minimum=1
        for(next in listOf(2,3,5,10)) {choose("settings-inferred-points",minimum.toString(),next.toString());minimum=next;compose.runOnIdle {assertEquals(next,settings.value.inferredMinimumPoints)}}
        row("settings-inferred-enabled");compose.onNodeWithTag("settings-inferred-enabled").performClick().assertIsOff()
        compose.onNodeWithTag("settings-inferred-gap").assertDoesNotExist()
        compose.onNodeWithTag("settings-inferred-enabled").performClick().assertIsOn()
        compose.runOnIdle {assertEquals(240,settings.value.inferredGapMinutes);assertEquals(60,settings.value.inferredMinimumMinutes);assertEquals(10,settings.value.inferredMinimumPoints)}
    }
    @Test fun themeUnitsAndNetworkConsentUseStableKeysAndIndependentUpdates() {
        show();choose("settings-theme","Sistema","Oscuro");assertEquals("dark",settings.value.appearance)
        choose("settings-theme","Oscuro","Claro");assertEquals("light",settings.value.appearance)
        choose("settings-theme","Claro","Sistema");assertEquals("system",settings.value.appearance)
        row("settings-metric");compose.onNodeWithTag("settings-metric").performClick().assertIsOff()
        row("settings-geocoding");compose.onNodeWithTag("settings-geocoding").assertContentDescriptionEquals("Permitir la geocodificación en línea").performClick().assertIsOff()
        compose.runOnIdle {assertFalse(settings.value.metric);assertFalse(settings.value.networkGeocoding);assertTrue(settings.value.inferredEnabled);assertEquals(5,settings.value.distanceFilter)}
    }
    @Test fun everyTranslatedDestinationAndSystemActionDispatchesOnlyItsOwnIntent() {
        show()
        val rows=listOf("Días por país" to SettingsDestination.countries,"Fotos" to SettingsDestination.photos,"Administrar lugares guardados" to SettingsDestination.places,"Añadir lugar guardado" to SettingsDestination.addPlace,"Estadísticas de uso" to SettingsDestination.usage,"Visualización por carreteras" to SettingsDestination.roads,"Exportaciones automáticas" to SettingsDestination.exports,"Webhooks" to SettingsDestination.webhooks,"Acceso de automatización" to SettingsDestination.automation,"Datos geográficos y licencias" to SettingsDestination.licenses,"Ayuda e información" to SettingsDestination.support)
        for((label,destination) in rows) {click(label);assertEquals(destination,destinations.last())}
        click("Permisos de ubicación y notificaciones");click("Elegir carpeta de exportación")
        assertEquals(listOf("permissions","folder"),calls);assertTrue(updates.isEmpty())
    }
    @Test fun cancelAndObsoleteConfirmationsCannotClearAndOneConfirmationRunsOnce() {
        show();openClear();val canceled=heldConfirm()
        compose.onNodeWithTag("settings-clear-cancel").performClick()
        compose.runOnIdle {canceled();assertTrue(calls.isEmpty())}
        openClear();val current=heldConfirm()
        compose.runOnIdle {canceled();assertTrue(calls.isEmpty());current();current();assertEquals(listOf("clear"),calls)}
    }
    @Test fun anotherClearAttemptInvalidatesThePreviouslyReviewedConfirmation() {
        show();openClear();val held=heldConfirm()
        compose.runOnIdle {clear.value=JournalClearState(JournalClearPhase.complete,1)};compose.waitForIdle()
        compose.runOnIdle {held();assertTrue(calls.isEmpty())}
        row("settings-clear-result");compose.onNodeWithTag("settings-clear-result").assertTextEquals("Datos del diario borrados.")
        openClear();compose.onNodeWithTag("settings-clear-confirm").performClick();assertEquals(listOf("clear"),calls)
    }
    @Test fun backgroundAndDisposalRejectHeldClearConfirmationsWithoutAutomaticRestart() {
        show();openClear();val background=heldConfirm()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {background()}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        compose.onNodeWithTag("settings-clear-confirm").assertDoesNotExist();assertTrue(calls.isEmpty())
        openClear();val disposed=heldConfirm();compose.runOnIdle {visible.value=false};compose.waitForIdle()
        compose.runOnIdle {disposed();assertTrue(calls.isEmpty())}
    }
    @Test fun activeClearDisablesEditsAndShowsProgressUntilItsAttemptCompletes() {
        clear.value=JournalClearState(JournalClearPhase.clearing,9);show()
        row("settings-inferred-enabled");compose.onNodeWithTag("settings-inferred-enabled").assertIsNotEnabled()
        row("settings-clear");compose.onNodeWithTag("settings-clear").assertIsNotEnabled()
        compose.onNodeWithText("Borrando los datos del diario…").assertExists()
        compose.runOnIdle {clear.value=JournalClearState(JournalClearPhase.failed,9)}
        row("settings-clear-result");compose.onNodeWithTag("settings-clear-result").assertTextEquals("No se pudieron borrar por completo los datos del diario. Inténtalo de nuevo.")
        assertTrue(calls.isEmpty());assertTrue(updates.isEmpty())
    }
    @Test fun staleMenuActionCannotWriteWhileSettingsAreSaving() {
        show();row("settings-theme");compose.onNode(hasText("Sistema") and hasAnyAncestor(hasTestTag("settings-theme"))).performClick()
        val held=compose.onNode(hasText("Oscuro") and hasAnyAncestor(isPopup())).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {commands.value=SettingsActionState(pending=1)};compose.waitForIdle()
        compose.runOnIdle {held();assertTrue(updates.isEmpty());assertEquals("system",settings.value.appearance)}
    }
    @Test fun failuresAndSuccessfulFolderFeedbackAreLocalizedWithoutChangingPreferences() {
        show()
        val messages=mapOf(SettingsFailure.preferences to "No se pudieron guardar los ajustes. Inténtalo de nuevo.",SettingsFailure.folder to "No se pudo guardar el acceso a la carpeta. Elige una carpeta en la que se pueda escribir.",SettingsFailure.folderPicker to "No se pudo abrir el selector de carpetas. Inténtalo de nuevo.",SettingsFailure.permissions to "No se pudieron abrir los ajustes de la app del sistema. Inténtalo de nuevo.")
        for((failure,message) in messages) {compose.runOnIdle {commands.value=SettingsActionState(failure=failure)};click("Cerrar");compose.onNodeWithText(message).assertExists()}
        compose.runOnIdle {commands.value=SettingsActionState(folderSaved=true)}
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Carpeta de exportación guardada."));compose.onNodeWithText("Carpeta de exportación guardada.").assertIsDisplayed()
        assertTrue(updates.isEmpty());assertEquals(List(4) {"dismiss-feedback"},calls)
    }
    @Test fun unsupportedStoredLabelsRenderSafelyWithoutSilentlyRewritingTheirValues() {
        settings.value=settings.value.copy(appearance="future-theme",distanceFilter=300,stopAfterHours=3,inferredGapMinutes=90,inferredMinimumMinutes=7)
        show();row("settings-distance");compose.onNodeWithText("300 m").assertExists()
        row("settings-stop");compose.onNodeWithText("3 h").assertExists()
        row("settings-inferred-gap");compose.onNodeWithText("90 min").assertExists()
        row("settings-inferred-duration");compose.onNodeWithText("7 min").assertExists()
        row("settings-theme");compose.onNodeWithText("Sistema").assertExists()
        assertTrue(updates.isEmpty());assertEquals("future-theme",settings.value.appearance)
    }
    @Test fun largeTextDarkRowsAndScrollableClearDisclosureKeepBothActionsReachable() {
        show(dark=true);row("settings-inferred-enabled")
        compose.onNodeWithTag("settings-inferred-enabled").assertIsDisplayed()
        if(InstrumentationRegistry.getArguments().getString("captureSettings")=="true") capture("-inference")
        openClear()
        val body=compose.onNodeWithTag("settings-clear-disclosure")
        body.assertTextContains("Las fotos originales y los archivos exportados permanecerán",substring=true)
        val range=body.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        if(range!=null && range.maxValue()>0) {body.performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,10000f)};assertTrue(range.value()>0)}
        compose.onNodeWithTag("settings-clear-confirm").assertIsDisplayed();compose.onNodeWithTag("settings-clear-cancel").assertIsDisplayed()
        if(InstrumentationRegistry.getArguments().getString("captureSettings")=="true") capture("-clear")
        compose.onNodeWithTag("settings-clear-cancel").performClick();assertTrue(calls.isEmpty());assertTrue(updates.isEmpty())
    }
    private fun capture(suffix:String) {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-settings${suffix}-large-20261029.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
}
