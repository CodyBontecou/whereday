package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.RootMatchers.isDialog as dialogRoot
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.ExportSchedule
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.time.format.*
import java.util.Locale

@SdkSuppress(minSdkVersion=33)
class AutomaticExportLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocales:LocaleList?=null
    private val initial=AutomaticExportSettings(schedule=ExportSchedule(9,17,0),kind=DataKind.points)
    private val ui=mutableStateOf(AutomaticExportUiState(loaded=true,state=AutomaticExportState(settings=initial)))
    private val visible=mutableStateOf(true)
    private val calls=mutableListOf<String>()
    private val updates=mutableListOf<AutomaticExportSettings>()
    private val actions=AutomaticExportActions({change->val next=change(ui.value.state.settings);updates.add(next);ui.value=ui.value.copy(state=ui.value.state.copy(settings=next))},{value->calls.add("enable:$value");ui.value=ui.value.copy(state=ui.value.state.copy(settings=ui.value.state.settings.copy(enabled=value)))},{calls.add("run")},{calls.add("retry")},{calls.add("cancel")},{calls.add("reset")},{calls.add("refresh")})
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun show(folder:Boolean=false,time:((Int,Int,(Int,Int)->Unit)->Unit)?=null,dark:Boolean=false) {
        compose.setContent {MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {Surface(Modifier.systemBarsPadding()) {if(visible.value) AutomaticExportsContent(ui.value,folder,actions,{calls.add("back")},time)}}}
    }
    private fun click(text:String)=compose.onNodeWithText(text).performScrollTo().performClick()
    private fun choose(current:String,next:String) {click(current);compose.onNode(hasText(next) and hasAnyAncestor(isPopup())).performScrollTo().performClick()}
    private fun anchor()=compose.onNodeWithTag("automatic-export-anchor").performScrollTo().performClick()
    private fun pending():PendingAutomaticExport {
        val at=Instant.parse("2026-10-28T23:30:00Z")
        return PendingAutomaticExport("d747d833-00f6-4fea-8fd5-1ba75da9b957",at,ScheduledExportPlan.resolve(ExportFormat.json,ExportOptions(zone=ZoneId.of("Europe/Paris")),"track-{date}",ExportSchedule(9),ScheduledFileMode.rewrite,null,at),"content://example.invalid/tree/synthetic-private-destination")
    }
    @Test fun everyRepeatChoiceKeepsItsStableHourValueAndUnrelatedAnchorFields() {
        show();var current=0
        fun label(n:Int)=if(n==0) "Una vez al día" else if(n==1) "Cada 1 hora" else "Cada $n horas"
        for(next in (1..23).toList()+0) {
            choose(label(current),label(next));current=next
            compose.runOnIdle {assertEquals(next,ui.value.state.settings.schedule.intervalHours);assertEquals(9,ui.value.state.settings.schedule.hour);assertEquals(17,ui.value.state.settings.schedule.minute);assertEquals(DataKind.points,ui.value.state.settings.kind)}
        }
        assertEquals(24,updates.size);assertTrue(calls.isEmpty())
    }
    @Test fun formatAndDataLabelsPreserveWireKeysAndHiddenOutingPreferences() {
        show();var format=ExportFormat.json
        for(next in ExportFormat.entries) {
            if(next!=format) click(recipeFormatName(next))
            format=next;compose.runOnIdle {assertEquals(next,ui.value.state.settings.format)}
        }
        click("Salidas");compose.onNodeWithText("Las salidas se seleccionan por su hora de inicio y se incluyen sus rutas completas. Las salidas inferidas siguen los Ajustes; un reintento pendiente conserva las opciones de inferencia capturadas.").assertExists()
        compose.runOnIdle {assertTrue(ui.value.state.settings.outings);assertEquals(DataKind.points,ui.value.state.settings.kind)}
        click("Visitas");compose.runOnIdle {assertFalse(ui.value.state.settings.outings);assertEquals(DataKind.visits,ui.value.state.settings.kind)}
        click("Todo");compose.runOnIdle {assertEquals(DataKind.all,ui.value.state.settings.kind);assertEquals(ExportFormat.overland,ui.value.state.settings.format)}
        assertTrue(calls.isEmpty())
    }
    @Test fun fileModesAndContainerExplanationDoNotChangeTheScheduleOrStoredMode() {
        show();choose("Una vez al día","Cada 9 horas");choose("Sobrescribir","Añadir")
        click("GPX");compose.onNodeWithText("GPX y KML siempre sobrescriben el día completo hasta el momento.").assertExists()
        compose.runOnIdle {assertEquals(ScheduledFileMode.append,ui.value.state.settings.fileMode);assertEquals(9,ui.value.state.settings.schedule.intervalHours)}
        choose("Cada 9 horas","Una vez al día");compose.onNodeWithText("Actualizar el archivo del día").assertDoesNotExist()
        compose.runOnIdle {assertEquals(ScheduledFileMode.append,ui.value.state.settings.fileMode)}
        choose("Una vez al día","Cada 23 horas");compose.onNodeWithText("Añadir").assertExists()
    }
    @Test fun actualTimePickerUpdatesOnlyCivilAnchorAndUsesPhoneClockFormatting() {
        show();anchor()
        Espresso.onView(ViewMatchers.isAssignableFrom(TimePicker::class.java)).inRoot(dialogRoot()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints():org.hamcrest.Matcher<android.view.View> = ViewMatchers.isAssignableFrom(TimePicker::class.java)
            override fun getDescription()="Set synthetic automatic export anchor"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {(view as TimePicker).apply {hour=23;minute=45};controller.loopMainThreadUntilIdle()}
        })
        compose.onNodeWithTag("automatic-anchor-confirm").performClick()
        compose.runOnIdle {assertEquals(ExportSchedule(23,45,0),ui.value.state.settings.schedule);assertEquals(DataKind.points,ui.value.state.settings.kind)}
        val locale=Locale.forLanguageTag("es")
        val pattern=android.text.format.DateFormat.getBestDateTimePattern(locale,if(android.text.format.DateFormat.is24HourFormat(compose.activity)) "Hm" else "hm")
        compose.onNodeWithText("Hora de referencia diaria: ${DateTimeFormatter.ofPattern(pattern,locale).format(LocalTime.of(23,45))}").assertExists()
    }
    @Test fun cancelingTheNativeTimePickerLeavesSettingsUntouched() {
        show();anchor();Espresso.onView(ViewMatchers.isRoot()).inRoot(dialogRoot()).perform(ViewActions.pressBack());compose.runOnIdle {assertTrue(updates.isEmpty())}
    }
    @Test fun anchorCallbackIsSingleUseAndCannotOverwriteChangedSettings() {
        var result:((Int,Int)->Unit)?=null
        show(time={_,_,callback->result=callback});anchor()
        compose.runOnIdle {result!!(10,15);result!!(11,30);assertEquals(1,updates.size);assertEquals(10,ui.value.state.settings.schedule.hour)}
        anchor();compose.runOnIdle {ui.value=ui.value.copy(state=ui.value.state.copy(settings=ui.value.state.settings.copy(format=ExportFormat.csv)))}
        compose.waitForIdle();compose.runOnIdle {result!!(12,0);assertEquals(1,updates.size);assertEquals(ExportFormat.csv,ui.value.state.settings.format)}
    }
    @Test fun busyOrPendingSettingsRejectAnAlreadyOpenAnchorCallback() {
        var result:((Int,Int)->Unit)?=null
        show(time={_,_,callback->result=callback});anchor()
        compose.runOnIdle {ui.value=ui.value.copy(busy=true)};compose.waitForIdle()
        compose.runOnIdle {result!!(12,0);assertTrue(updates.isEmpty());ui.value=ui.value.copy(busy=false)}
        anchor();compose.runOnIdle {ui.value=ui.value.copy(state=ui.value.state.copy(pending=pending()))};compose.waitForIdle()
        compose.runOnIdle {result!!(12,0);assertTrue(updates.isEmpty())}
    }
    @Test fun backgroundInvalidatesTheAnchorWithoutApplyingOrAutomaticallyReopeningIt() {
        var result:((Int,Int)->Unit)?=null;var opens=0
        show(time={_,_,callback->opens++;result=callback});anchor()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {result!!(12,0)}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        compose.runOnIdle {result!!(13,0);assertTrue(updates.isEmpty());assertEquals(1,opens)}
    }
    @Test fun disposalRejectsTheHeldAnchorResult() {
        var result:((Int,Int)->Unit)?=null
        show(time={_,_,callback->result=callback});anchor();compose.runOnIdle {visible.value=false};compose.waitForIdle()
        compose.runOnIdle {result!!(12,0);assertTrue(updates.isEmpty())}
    }
    @Test fun pendingWindowUsesItsCapturedZoneAndKeepsRetryAndCancelSeparateFromEditing() {
        val saved=pending()
        ui.value=ui.value.copy(state=ui.value.state.copy(settings=initial.copy(enabled=true),pending=saved,lastFailure=AutomaticExportFailure.execution,lastStatus=AutomaticExportNotice.missingDay))
        show(folder=true,dark=true)
        compose.onNodeWithTag("automatic-export-anchor").performScrollTo().assertIsNotEnabled()
        val date=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(Locale.forLanguageTag("es")).withZone(saved.plan.options.zone).format(saved.plan.filenameDate)
        compose.onNodeWithText("Exportación pendiente: $date. El reintento conserva este intervalo y destino capturados.").performScrollTo().assertIsDisplayed()
        click("Reintentar la exportación pendiente");click("Cancelar la exportación pendiente")
        compose.runOnIdle {assertEquals(listOf("retry","cancel"),calls);assertTrue(updates.isEmpty());assertEquals(saved,ui.value.state.pending);assertNull(ui.value.state.lastSuccess)}
        compose.onNodeWithText("Faltaba el archivo del día, por lo que se reconstruyó el día completo capturado.").performScrollTo().assertIsDisplayed()
        if(InstrumentationRegistry.getArguments().getString("captureAutomaticExports")=="true") capture()
    }
    @Test fun busyStateStillAllowsDisableAndHeldRunCannotStartAgainstANewPendingRequest() {
        show()
        val held=compose.onNodeWithTag("automatic-export-run").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {ui.value=ui.value.copy(busy=true,state=ui.value.state.copy(settings=initial.copy(enabled=true),pending=pending()))};compose.waitForIdle()
        compose.onNodeWithTag("automatic-export-enabled").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {held();assertEquals(listOf("enable:false"),calls);assertTrue(updates.isEmpty())}
    }
    @Test fun loadingFailureResetIsExplicitAndCannotClearAConfigurationThatRecovered() {
        ui.value=AutomaticExportUiState(failure=AutomaticExportFailure.settingsUnavailable);show()
        click("Reintentar la carga de exportaciones automáticas");assertEquals(listOf("refresh"),calls)
        click("Restablecer los ajustes de exportaciones automáticas");compose.onNodeWithText("¿Restablecer las exportaciones automáticas?").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick();assertEquals(listOf("refresh"),calls)
        click("Restablecer los ajustes de exportaciones automáticas")
        compose.runOnIdle {ui.value=AutomaticExportUiState(loaded=true,state=AutomaticExportState(settings=initial))};compose.waitForIdle()
        compose.onNodeWithText("Restablecer las exportaciones automáticas").performClick();assertEquals(listOf("refresh"),calls)
        compose.runOnIdle {ui.value=AutomaticExportUiState(failure=AutomaticExportFailure.settingsUnavailable)}
        click("Restablecer los ajustes de exportaciones automáticas");compose.onNodeWithText("Restablecer las exportaciones automáticas").performClick()
        compose.runOnIdle {assertEquals(listOf("refresh","reset"),calls)}
    }
    @Test fun feedbackRelocalizesWithoutChangingStatusIdentity() {
        val es=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("es"))})
        val en=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("en-US"))})
        var spanish by mutableStateOf(true)
        compose.setContent {val context=if(spanish) es else en;CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides context.resources.configuration) {Column {Text(automaticExportFailureText(AutomaticExportFailure.purchase));Text(automaticExportNoticeText(AutomaticExportNotice.jsonRewritten));Text(automaticExportActionText(AutomaticExportActionNotice.verified))}}}
        compose.onNodeWithText("Exportación guardada y verificada.").assertExists();compose.onNodeWithText("Se requiere un acceso de por vida a las exportaciones verificado.").assertExists()
        compose.runOnIdle {spanish=false}
        compose.onNodeWithText("Export saved and verified.").assertExists();compose.onNodeWithText("The existing JSON could not be merged; the complete captured day was rewritten.").assertExists()
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync()
        // Native Material ripples run beyond Compose idleness; wait for a quiet capture window.
        instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-automatic-export-large-20261029.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
}
