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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Scoped consent presentation with synthetic callbacks; only mapQa's own installation is used as identity. */
@SdkSuppress(minSdkVersion=33)
class AutomationLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph:AppGraph
    private lateinit var identity:AutomationIdentity
    private var oldLocales:LocaleList?=null
    private val ui=mutableStateOf(AutomationUiState())
    private val visible=mutableStateOf(true)
    private val calls=mutableListOf<String>()
    private val grants=mutableListOf<Pair<AutomationIdentity,Set<AutomationScope>>>()
    private val titles=mapOf(AutomationScope.summary to "Leer estadísticas",AutomationScope.visits to "Leer visitas",AutomationScope.outings to "Leer salidas",AutomationScope.renameVisits to "Cambiar nombres de visitas",AutomationScope.renameOutings to "Cambiar nombres de salidas",AutomationScope.recording to "Controlar la grabación",AutomationScope.exports to "Crear archivos de exportación",AutomationScope.places to "Leer lugares guardados",AutomationScope.manualVisits to "Crear y finalizar visitas manuales")
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target);identity=requireNotNull(graph.automationAccess.installed(target.packageName))
        ui.value=AutomationUiState(loaded=true,installed=identity)
    }
    @After fun cleanup():Unit=runBlocking {
        try {if(::graph.isInitialized) {graph.automationAccess.revoke();graph.automationResults.clear()}}
        finally {oldLocales?.let {applyQaAppLocales(compose,it)}}
    }
    @Composable private fun content() {MaterialTheme {Surface(Modifier.systemBarsPadding()) {if(visible.value) AutomationForm(ui.value,{calls.add("back")},{id,scopes->grants.add(id to scopes)},{calls.add("disable")},{calls.add("reload")},{calls.add("install")})}}}
    private fun show() {compose.setContent {content()}}
    private fun click(text:String)=compose.onNodeWithText(text).performScrollTo().performClick()
    private fun toggle(scope:AutomationScope)=compose.onNodeWithTag("automation-scope-${scope.name}").performScrollTo().performClick()
    private fun review() {compose.onNodeWithTag("automation-save").performScrollTo().performClick();compose.onNodeWithText("¿Permitir estas acciones de Automate?").assertIsDisplayed()}
    private fun held(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private suspend fun approval(scopes:Set<AutomationScope>):AutomationApproval {graph.automationAccess.approve(identity,scopes);return requireNotNull(graph.automationAccess.approval())}
    private fun replacementIdentity():AutomationIdentity=runBlocking {
        // Read a synthetic stale-installation receipt through the strict public reader.
        approval(setOf(AutomationScope.summary))
        val file=java.io.File(target.filesDir,"automation/approval.json")
        val json=org.json.JSONObject(file.readText())
        json.getJSONObject("approval").put("installed",identity.firstInstalled+1)
        file.writeText(json.toString())
        requireNotNull(graph.automationAccess.approval()).identity
    }
    @Test fun absentClientCannotSelectOrGrantButInstallAndReloadRemainExplicit() {
        ui.value=AutomationUiState(loaded=true);show()
        compose.onNodeWithTag("automation-status").assertTextEquals("Acceso desactivado")
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("automation-save").performScrollTo().assertIsNotEnabled()
        click("Obtener Automate");click("Comprobar la app instalada")
        assertEquals(listOf("install","reload"),calls);assertTrue(grants.isEmpty())
    }
    @Test fun allNineTranslatedScopesReviewTheirExactEnumSetAndKeepTheProtocolActionLiteral() {
        show()
        for(scope in AutomationScope.entries) {toggle(scope);compose.onNodeWithTag("automation-scope-${scope.name}").assertIsOn();compose.onNodeWithText(titles.getValue(scope)).assertExists()}
        assertTrue(grants.isEmpty());review()
        val body=compose.onNodeWithTag("automation-review-body")
        val range=body.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        if(range!=null && range.maxValue()>0) body.performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,10000f)}
        compose.onNodeWithTag("automation-allow").assertIsDisplayed()
        if(InstrumentationRegistry.getArguments().getString("captureAutomation")=="true") capture("-review")
        compose.onNodeWithTag("automation-allow").performClick()
        assertEquals(identity,grants.single().first);assertEquals(AutomationScope.entries.toSet(),grants.single().second)
        compose.onNodeWithText("En Automate, usa App decision con la acción ${AutomationActivity.ACTION_RUN} y el paquete de Whereday. Los resultados se devuelven como URI de archivos de solo lectura. Empieza por las estadísticas antes de activar cambios.").performScrollTo().assertIsDisplayed()
    }
    @Test fun canceledAndReplacedReviewCallbacksCannotGrantAndOneReviewSubmitsOnce() {
        show();toggle(AutomationScope.summary);review();val canceled=held("automation-allow")
        compose.onNodeWithText("Cancelar").performClick();compose.runOnIdle {canceled();assertTrue(grants.isEmpty())}
        review();val current=held("automation-allow")
        compose.runOnIdle {canceled();assertTrue(grants.isEmpty());current();current()}
        assertEquals(listOf(identity to setOf(AutomationScope.summary)),grants)
    }
    @Test fun changingTheDraftAfterReviewRequiresANewReviewInsteadOfGrantingOldChoices() {
        show();toggle(AutomationScope.summary)
        val lateToggle=held("automation-scope-exports")
        review();val old=held("automation-allow")
        compose.runOnIdle {lateToggle()};compose.waitForIdle()
        compose.runOnIdle {old();assertTrue(grants.isEmpty())}
        review();compose.onNodeWithTag("automation-allow").performClick()
        assertEquals(setOf(AutomationScope.summary,AutomationScope.exports),grants.single().second)
    }
    @Test fun savedChoicesRestoreWithoutRestoringConsentAndBindToTheInstallation() {
        val restoration=StateRestorationTester(compose);restoration.setContent {content()}
        toggle(AutomationScope.summary);toggle(AutomationScope.exports);review()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("automation-allow").assertDoesNotExist()
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().assertIsOn()
        compose.onNodeWithTag("automation-scope-exports").performScrollTo().assertIsOn();assertTrue(grants.isEmpty())
        val replacement=replacementIdentity()
        compose.runOnIdle {ui.value=ui.value.copy(installed=replacement)}
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().assertIsOff()
        compose.onNodeWithTag("automation-scope-exports").performScrollTo().assertIsOff();assertTrue(grants.isEmpty())
        assertNull(AutomationChoiceDraft.Saver.restore(listOf("1","binding","future_scope")))
        assertNull(AutomationChoiceDraft.Saver.restore(listOf("2","binding","summary")))
    }
    @Test fun aNewSavedApprovalReplacesOnlyTheOldBoundDraft():Unit=runBlocking {
        val saved=approval(setOf(AutomationScope.summary));ui.value=ui.value.copy(approval=saved)
        val restoration=StateRestorationTester(compose);restoration.setContent {content()}
        toggle(AutomationScope.exports);restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("automation-scope-exports").performScrollTo().assertIsOn()
        val newer=approval(setOf(AutomationScope.visits))
        compose.runOnIdle {ui.value=ui.value.copy(approval=newer,consentRevision=1)}
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().assertIsOff()
        compose.onNodeWithTag("automation-scope-exports").performScrollTo().assertIsOff()
        compose.onNodeWithTag("automation-scope-visits").performScrollTo().assertIsOn()
        compose.onNodeWithTag("automation-save").performScrollTo().assertIsNotEnabled();assertTrue(grants.isEmpty())
    }
    @Test fun failedSavingAnExistingApprovalShowsUnknownAccessAndAllowsAnExplicitReviewedRetry():Unit=runBlocking {
        val saved=approval(setOf(AutomationScope.summary))
        ui.value=ui.value.copy(approval=saved,failure=AutomationFailure.save,consentRevision=2);show()
        compose.onNodeWithTag("automation-status").assertTextEquals("Estado de acceso no disponible")
        review();assertTrue(grants.isEmpty());compose.onNodeWithTag("automation-allow").performClick()
        assertEquals(listOf(identity to setOf(AutomationScope.summary)),grants)
    }
    @Test fun removalOrReplacementOfTheInstalledClientInvalidatesAReviewedGrant() {
        show();toggle(AutomationScope.visits);review();val old=held("automation-allow")
        compose.runOnIdle {ui.value=ui.value.copy(installed=null)};compose.waitForIdle()
        compose.runOnIdle {old();assertTrue(grants.isEmpty())}
        val replacement=replacementIdentity()
        compose.runOnIdle {ui.value=ui.value.copy(installed=replacement)}
        compose.onNodeWithTag("automation-scope-visits").performScrollTo().assertIsOff()
        toggle(AutomationScope.exports);review();compose.onNodeWithTag("automation-allow").performClick()
        assertEquals(listOf(replacement to setOf(AutomationScope.exports)),grants)
    }
    @Test fun anotherConsentRevisionRejectsAnOldReviewButKeepsRetryChoices() {
        show();toggle(AutomationScope.summary);review();val old=held("automation-allow")
        compose.runOnIdle {ui.value=ui.value.copy(consentRevision=1,failure=AutomationFailure.save)};compose.waitForIdle()
        compose.runOnIdle {old();assertTrue(grants.isEmpty())}
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().assertIsOn()
        review();compose.onNodeWithTag("automation-allow").performClick()
        assertEquals(setOf(AutomationScope.summary),grants.single().second)
    }
    @Test fun revokeNeedsAFreshConfirmationAndCannotRevokeANewerConsentSnapshot():Unit=runBlocking {
        ui.value=ui.value.copy(approval=approval(setOf(AutomationScope.summary)));show()
        click("Desactivar el acceso de automatización");val canceled=held("automation-revoke")
        compose.onNodeWithText("Cancelar").performClick();compose.runOnIdle {canceled();assertTrue(calls.isEmpty())}
        click("Desactivar el acceso de automatización");val stale=held("automation-revoke")
        compose.runOnIdle {ui.value=ui.value.copy(consentRevision=1)};compose.waitForIdle()
        compose.runOnIdle {stale();assertTrue(calls.isEmpty())}
        click("Desactivar el acceso de automatización");val current=held("automation-revoke")
        compose.runOnIdle {current();current();assertEquals(listOf("disable"),calls)}
    }
    @Test fun backgroundAndDisposalInvalidateReviewsWithoutGrantingOrAutomaticallyReopening() {
        show();toggle(AutomationScope.summary);review();val background=held("automation-allow")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {background()}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        compose.onNodeWithTag("automation-allow").assertDoesNotExist();assertTrue(grants.isEmpty())
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().assertIsOn()
        review();val disposed=held("automation-allow");compose.runOnIdle {visible.value=false};compose.waitForIdle()
        compose.runOnIdle {disposed();assertTrue(grants.isEmpty())}
    }
    @Test fun disableWithNoPriorApprovalResetsChoicesButReloadDoesNotEraseANewDraft() {
        show();toggle(AutomationScope.summary)
        compose.runOnIdle {ui.value=ui.value.copy(resetRevision=1,consentRevision=1,notice=AutomationNotice.disabled)}
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().assertIsOff()
        toggle(AutomationScope.visits)
        compose.runOnIdle {ui.value=ui.value.copy(notice=null)}
        compose.onNodeWithTag("automation-scope-visits").performScrollTo().assertIsOn()
        compose.runOnIdle {ui.value=ui.value.copy(loaded=false,installed=null)}
        compose.onNodeWithText("Cargando el acceso de automatización…").assertExists()
        compose.runOnIdle {ui.value=ui.value.copy(loaded=true,installed=identity)}
        compose.onNodeWithTag("automation-scope-visits").performScrollTo().assertIsOn();assertTrue(grants.isEmpty())
    }
    @Test fun typedFeedbackAndScopeDescriptionsUseCurrentLocaleWithoutChangingTheirIdentity() {
        val es=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("es"))})
        val en=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("en-US"))})
        var spanish by mutableStateOf(true)
        compose.setContent {val context=if(spanish) es else en;CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides context.resources.configuration) {Column {
            Text(automationFailureText(AutomationFailure.load));Text(automationNoticeText(AutomationNotice.saved));Text(AutomationScope.recording.detail())
        }}}
        compose.onNodeWithText("Acceso de automatización guardado.").assertExists()
        compose.onNodeWithText("Inicia o detén la grabación mientras Whereday está abierto. Para iniciarla se requiere acceso a la ubicación ya concedido.").assertExists()
        compose.runOnIdle {spanish=false}
        compose.onNodeWithText("Automation access saved.").assertExists()
        compose.onNodeWithText("Saved automation access could not be opened. Disable access to reset it.").assertExists()
    }
    @Test fun unsavedDiscardAndBusyNavigationNeverChangeTheSavedGrant() {
        show();toggle(AutomationScope.visits);click("Volver a Ajustes")
        compose.onNodeWithText("¿Descartar las opciones de automatización sin guardar?").assertIsDisplayed()
        val canceled=held("automation-discard");compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle {canceled();assertTrue(calls.isEmpty())}
        click("Volver a Ajustes");compose.onNodeWithTag("automation-discard").performClick()
        assertEquals(listOf("back"),calls);assertTrue(grants.isEmpty())
        compose.runOnIdle {ui.value=ui.value.copy(busy=true,consentRevision=1)}
        compose.onNodeWithTag("automation-scope-visits").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("automation-save").performScrollTo().assertIsNotEnabled()
        click("Volver a Ajustes");assertEquals(listOf("back","back"),calls);assertTrue(grants.isEmpty())
    }
    @Test fun largeTextDarkScopeRowsAndAccessFailureRemainReadable() {
        ui.value=ui.value.copy(failure=AutomationFailure.load)
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {Surface(Modifier.systemBarsPadding()) {AutomationForm(ui.value,{},{_,_->},{},{},{})}}}
        compose.onNodeWithTag("automation-scope-renameVisits").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cambia los nombres de las visitas y lee el nombre actualizado, la dirección y las horas.").assertExists()
        if(InstrumentationRegistry.getArguments().getString("captureAutomation")=="true") capture("-scopes")
        compose.onNodeWithTag("automation-error").performScrollTo().assertTextEquals("No se pudo abrir el acceso de automatización guardado. Desactiva el acceso para restablecerlo.")
        compose.onNodeWithText("Desactivar el acceso de automatización").performScrollTo().assertIsDisplayed()
    }
    private fun capture(suffix:String) {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-automation${suffix}-large-20261029.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
}
