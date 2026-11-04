package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.time.format.*
import java.util.Locale

/** Synthetic state and callbacks in mapQa only. No configured destination, store or history is contacted. */
@SdkSuppress(minSdkVersion=33)
class WebhookLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocales:LocaleList?=null
    private val editor=WebhookEditorViewModel()
    private val config=WebhookConfiguration("d747d833-00f6-4fea-8fd5-1ba75da9b957",WebhookSettings(endpoint="https://example.invalid/qa",mode=WebhookMode.manual))
    private val ui=mutableStateOf(WebhookUiState())
    private data class Save(val draft:WebhookDraft,val transition:WebhookQueueTransition,val revision:String)
    private val saves=mutableListOf<Save>()
    private val calls=mutableListOf<String>()
    private val actions=WebhookActions({a,b,c->saves.add(Save(a,b,c))},{calls.add("disable")},{calls.add("reset")},{calls.add("test")},{calls.add("history")},{calls.add("flush")},{calls.add("retry:$it")},{calls.add("discard:$it")},{calls.add("resume")},{calls.add("refresh")})
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun state(saved:WebhookConfiguration=config,points:Long=0,batches:Long=0,head:WebhookBatchRow?=null,policy:WebhookPolicyRow?=null)=WebhookUiState(loaded=true,state=WebhookState(saved,WebhookQueueSnapshot(policy,points,batches,null,head)))
    private fun show(value:WebhookUiState=state(),paid:Boolean=true,dark:Boolean=false) {
        ui.value=value
        compose.setContent {MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {Surface(Modifier.systemBarsPadding()) {
            WebhookSettingsContent(ui.value,editor,actions,paid,{calls.add("back")}) {Text("Panel de compra de prueba")}
        }}}
        if(value.state!=null) compose.waitUntil(5000) {editor.baseline!=null}
    }
    private fun click(text:String)=compose.onNodeWithText(text).performScrollTo().performClick()
    private fun choose(current:String,next:String) {
        // Format and send mode are chips now; other settings still open a menu.
        val chip=hasText(next) and (hasAnyAncestor(hasTestTag("webhook-format-chips")) or hasAnyAncestor(hasTestTag("webhook-mode-chips")))
        if(compose.onAllNodes(chip).fetchSemanticsNodes().isNotEmpty()) {compose.onNode(chip).performScrollTo().performClick();return}
        click(current)
        compose.onNode(hasText(next) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }
    @Test fun translatedFormRetainsEveryWireFormatAndDoesNotSubmitOnOpening() {
        show();compose.onNodeWithText("Actualmente desactivados").assertExists()
        compose.onNodeWithTag("webhook-url").assert(SemanticsMatcher.expectValue(SemanticsProperties.Password,Unit))
        compose.onNodeWithContentDescription("Mostrar URL de destino").assertExists()
        var current=ExportFormat.json
        fun title(f:ExportFormat)=recipeFormatName(f)
        for(format in ExportFormat.entries) {
            if(format!=current) choose(title(current),title(format))
            current=format
            compose.runOnIdle {assertEquals(format,editor.draft.settings.format);assertEquals(config.settings.endpoint,editor.draft.settings.endpoint)}
        }
        assertTrue(calls.isEmpty());assertTrue(saves.isEmpty())
    }
    @Test fun allAuthenticationChoicesKeepStableModesAndResetOnlyTheirDraftCredentials() {
        show()
        val labels=listOf(WebhookAuth.none to "Ninguna",WebhookAuth.apiKeyQuery to "Clave de API en la URL",WebhookAuth.bearer to "Token Bearer",WebhookAuth.basic to "Básica",WebhookAuth.customHeader to "Cabecera personalizada")
        var current="Ninguna"
        for((auth,label) in labels.drop(1)) {
            choose(current,label);current=label
            compose.runOnIdle {assertEquals(auth,editor.draft.settings.auth);assertEquals("",editor.draft.credentials.value);assertEquals(if(auth==WebhookAuth.customHeader) "X-API-Key" else "api_key",editor.draft.credentials.key)}
            compose.onNodeWithTag("webhook-auth-value").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Password,Unit)).performTextInput("synthetic-secret")
            val reveal=if(auth==WebhookAuth.basic) "Mostrar Contraseña" else "Mostrar Valor de autenticación"
            compose.onNodeWithContentDescription(reveal).performClick()
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("webhook-auth-value").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
            assertEquals("synthetic-secret",layouts.single().layoutInput.text.text)
            assertFalse(editor.draft.toString().contains("synthetic-secret"))
        }
        choose(current,"Ninguna");compose.onNodeWithTag("webhook-auth-value").assertDoesNotExist()
        compose.runOnIdle {assertEquals(WebhookAuth.none,editor.draft.settings.auth);assertTrue(editor.draft.credentials.value.isEmpty());assertTrue(calls.isEmpty());assertTrue(saves.isEmpty())}
    }
    @Test fun translatedCountAndTimeChoicesPreserveRawBatchSettings() {
        show();choose("Manual","Por cantidad")
        var count=10
        for(next in listOf(5,25,50,100,10)) {choose("$count puntos","$next puntos");count=next;compose.runOnIdle {assertEquals(next,editor.draft.settings.batchCount)}}
        choose("Por cantidad","Por tiempo");var minutes=5
        fun title(n:Int)=if(n==60) "1 hora" else "$n min"
        for(next in listOf(1,15,30,60,5)) {choose(title(minutes),title(next));minutes=next;compose.runOnIdle {assertEquals(next,editor.draft.settings.batchMinutes)}}
        choose("Por tiempo","En tiempo real")
        compose.runOnIdle {assertEquals(WebhookMode.realtime,editor.draft.settings.mode);assertEquals(10,editor.draft.settings.batchCount);assertEquals(5,editor.draft.settings.batchMinutes);assertTrue(saves.isEmpty());assertTrue(calls.isEmpty())}
    }
    @Test fun invalidEndpointAndAuthenticationAreLocalizedWithoutLeakingSecretsOrSaving() {
        show()
        val cases=listOf(
            WebhookDraft(config.settings.copy(endpoint="http://example.invalid/private-token")) to "Usa una URL de destino HTTPS.",
            WebhookDraft(config.settings.copy(endpoint="https://user:private-token@example.invalid/")) to "Introduce una URL sin credenciales incrustadas ni fragmentos.",
            WebhookDraft(config.settings.copy(auth=WebhookAuth.bearer),WebhookCredentials(value="private-token\n")) to "Introduce un valor de autenticación válido sin caracteres de control.",
            WebhookDraft(config.settings.copy(auth=WebhookAuth.apiKeyQuery),WebhookCredentials(key="")) to "Introduce el nombre y el valor de una clave de API.",
            WebhookDraft(config.settings.copy(auth=WebhookAuth.basic),WebhookCredentials(username="private:token")) to "Introduce un nombre de usuario y una contraseña válidos para la autenticación básica.",
            WebhookDraft(config.settings.copy(auth=WebhookAuth.basic)) to "Introduce un nombre de usuario o una contraseña para la autenticación básica.",
            WebhookDraft(config.settings.copy(auth=WebhookAuth.customHeader),WebhookCredentials(key="Host",value="private-token")) to "Elige un nombre de cabecera de autenticación válido."
        )
        for((draft,message) in cases) {
            compose.runOnIdle {editor.edit(draft)};click("Guardar los ajustes de webhooks")
            compose.onNodeWithTag("webhook-validation").performScrollTo().assertTextEquals(message)
            compose.runOnIdle {assertTrue(saves.isEmpty());assertTrue(calls.isEmpty());assertEquals(draft.settings,editor.draft.settings)}
        }
    }
    @Test fun spanishEnableAndQueueDecisionsKeepTheReviewedRevisionAndPayloadPolicy() {
        show();compose.onNodeWithTag("webhook-enabled").performClick();click("Guardar los ajustes de webhooks")
        compose.onNodeWithText("¿Activar los envíos de webhooks?").assertIsDisplayed();assertTrue(saves.isEmpty())
        compose.onNodeWithText("Cancelar").performClick();click("Guardar los ajustes de webhooks");compose.onNodeWithText("Activar y guardar").performClick()
        compose.runOnIdle {assertEquals(WebhookQueueTransition.requireEmpty,saves.single().transition);assertEquals(config.revision,saves.single().revision);assertTrue(saves.single().draft.settings.enabled)}
        val enabled=config.copy(settings=editor.draft.settings)
        compose.runOnIdle {editor.reload(enabled);ui.value=state(enabled,2,1)}
        choose("JSON","CSV");click("Guardar los ajustes de webhooks")
        compose.onNodeWithText("¿Qué debe ocurrir con los datos en cola?").assertIsDisplayed()
        compose.onNodeWithText("Guardar y conservar los datos en cola").assertDoesNotExist()
        compose.onNodeWithText("Guardar y descartar los datos en cola").performClick()
        compose.runOnIdle {assertEquals(WebhookQueueTransition.discard,saves.last().transition);assertEquals(ExportFormat.csv,saves.last().draft.settings.format);assertEquals(config.revision,saves.last().revision);assertTrue(calls.isEmpty())}
    }
    @Test fun destructiveAndSendConfirmationsAreLocalizedAndDoNotActOnCancel() {
        val enabled=config.copy(settings=config.settings.copy(enabled=true))
        val head=WebhookBatchRow("reviewed-request",config.revision,Instant.parse("2026-10-28T10:00:00Z"),2)
        show(state(enabled,2,1,head))
        val cases=listOf(
            Triple("Enviar historial ahora","¿Enviar todo el historial guardado?","Enviar historial"),
            Triple("Enviar los puntos en cola","¿Enviar los puntos en cola?","Enviar los puntos"),
            Triple("Descartar la solicitud más antigua","¿Descartar la solicitud más antigua en cola?","Descartar la solicitud"),
            Triple("Desactivar webhooks","¿Desactivar webhooks?","Desactivar webhooks"),
            Triple("Restablecer los ajustes de webhooks","¿Restablecer los ajustes de webhooks?","Restablecer webhooks")
        )
        for((action,title,confirm) in cases) {
            click(action);compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNode(hasText(confirm) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText("Cancelar").performClick();compose.runOnIdle {assertTrue(calls.isEmpty());assertTrue(saves.isEmpty())}
        }
        click("Enviar historial ahora");compose.onNodeWithText("Enviar historial").performClick()
        compose.runOnIdle {assertEquals(listOf("history"),calls)}
        click("Descartar la solicitud más antigua");compose.onNodeWithText("Descartar la solicitud").performClick()
        compose.runOnIdle {assertEquals(listOf("history","discard:reviewed-request"),calls)}
    }
    @Test fun freeDeliveryActionShowsLocalizedPurchaseWithoutDispatching() {
        show(paid=false);click("Probar la conexión")
        compose.onNodeWithText("Acceso de por vida a las exportaciones").assertIsDisplayed()
        compose.onNodeWithText("Panel de compra de prueba").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").performClick();compose.onNodeWithTag("webhook-screen").assertExists()
        compose.runOnIdle {assertTrue(calls.isEmpty());assertTrue(saves.isEmpty())}
    }
    @Test fun statusUsesPluralCountsAndCurrentLocaleDatesWhileKeepingHttpAndQueueIdentities() {
        val at=Instant.parse("2026-10-28T10:00:00Z")
        val policy=WebhookPolicyRow(revision=config.revision,enabled=true,capture=true,includeOutliers=false,createdAt=at,lastSuccess=at,lastStatus=429)
        val head=WebhookBatchRow("stable-head",config.revision,at,1001,visitCount=1)
        show(state(points=1001,batches=1,head=head,policy=policy).copy(nextAttempt=at.plusSeconds(60),feedback=WebhookFeedback(WebhookNotice.connectionRejected,429)))
        compose.onNodeWithText("1.001 puntos en tiempo real en espera · 1 solicitud pendiente").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Solicitud más antigua: 1.001 puntos · 1 visita").assertExists()
        compose.onNodeWithText("Última respuesta: HTTP 429").assertExists()
        val expected=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(Locale.forLanguageTag("es")).withZone(ZoneId.systemDefault()).format(at)
        compose.onNodeWithText("Último envío: $expected").assertExists()
        compose.onNodeWithText("No se pudo conectar (HTTP 429).").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {assertEquals("stable-head",ui.value.state!!.queue.head!!.id);assertEquals(1001L,ui.value.state!!.queue.queuedPoints);assertTrue(calls.isEmpty())}
    }
    @Test fun typedFailureAndFeedbackRelocalizeWithoutReinterpretingTheirIdentity() {
        val es=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("es"))})
        val en=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("en-US"))})
        var spanish by mutableStateOf(true)
        val feedback=WebhookFeedback(WebhookNotice.connected,204)
        compose.setContent {val context=if(spanish) es else en;CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides context.resources.configuration) {Column {
            Text(webhookFeedbackText(feedback));Text(webhookFailureText(WebhookFailure.changed));Text(webhookValidationText(WebhookDraftFailure.protocol(WebhookValidationFailure.headerName)))
        }}}
        compose.onNodeWithText("Conectado (HTTP 204).").assertExists();compose.onNodeWithText("Los ajustes de webhooks han cambiado. Vuelve a cargarlos antes de guardar.").assertExists()
        compose.runOnIdle {spanish=false}
        compose.onNodeWithText("Connected (HTTP 204).").assertExists();compose.onNodeWithText("Webhook settings changed. Reload them before saving.").assertExists()
        compose.onNodeWithText("Choose a valid authentication header name.").assertExists()
        assertEquals(WebhookNotice.connected,feedback.notice);assertEquals(204,feedback.httpStatus)
    }
    @Test fun loadingRecoveryAndLargeTextDarkSecretFormKeepActionsReachable() {
        show(WebhookUiState(failure=WebhookFailure.unavailable),dark=true)
        compose.onNodeWithText("No se pudieron abrir los ajustes de webhooks. Restablécelos antes de volver a configurar los envíos.").assertExists()
        click("Reintentar la carga de los ajustes de webhooks");assertEquals(listOf("refresh"),calls)
        val basic=config.copy(settings=config.settings.copy(auth=WebhookAuth.basic),credentials=WebhookCredentials(username="Nombre de usuario de prueba 日本語",value="synthetic-secret"))
        compose.runOnIdle {ui.value=state(basic)};compose.waitUntil(5000) {editor.baseline==basic}
        compose.onNodeWithTag("webhook-enabled").performScrollTo().assertContentDescriptionEquals("Activar los envíos de webhooks")
        compose.onNodeWithTag("webhook-username").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("webhook-auth-value").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Password,Unit))
        if(InstrumentationRegistry.getArguments().getString("captureWebhookLocalization")=="true") capture()
        click("Restablecer los ajustes de webhooks");compose.onNodeWithText("¿Restablecer los ajustes de webhooks?").assertIsDisplayed()
        compose.onNodeWithText("Restablecer webhooks").assertIsDisplayed();compose.onNodeWithText("Cancelar").assertIsDisplayed().performClick()
        assertEquals(listOf("refresh"),calls);assertTrue(saves.isEmpty())
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync()
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-webhook-localized-large-20261029.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
}
