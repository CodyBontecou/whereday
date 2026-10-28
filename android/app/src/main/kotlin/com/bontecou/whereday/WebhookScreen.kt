package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.billing.AccessState
import com.bontecou.whereday.data.WebhookQueueTransition
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class WebhookActions(
    val save:(WebhookDraft,WebhookQueueTransition,String)->Unit,val disable:()->Unit,val reset:()->Unit,
    val test:()->Unit,val sendHistory:()->Unit,val flush:()->Unit,val retry:(String)->Unit,val discard:(String)->Unit,
    val resume:()->Unit,val refresh:()->Unit,
)

@Composable fun WebhookScreen(graph:AppGraph,back:()->Unit) {
    val ui by graph.webhookController.state.collectAsStateWithLifecycle()
    val purchase by graph.purchases.state.collectAsStateWithLifecycle()
    val checkout by graph.purchases.checkoutInFlight.collectAsStateWithLifecycle()
    val activity=LocalActivity.current
    val owner=activity as ViewModelStoreOwner
    val editor=remember(owner) {ViewModelProvider(owner)[WebhookEditorViewModel::class.java]}
    LaunchedEffect(Unit) {graph.webhookController.refresh()}
    val actions=remember(graph) {WebhookActions(
        {draft,transition,revision->graph.webhookController.configure(draft.settings,draft.credentials,transition,revision)},
        graph.webhookController::disable,graph.webhookController::reset,graph.webhookController::testConnection,
        graph.webhookController::sendHistory,graph.webhookController::flush,graph.webhookController::retry,
        graph.webhookController::discard,graph.webhookController::foreground,graph.webhookController::refresh,
    )}
    WebhookSettingsContent(ui,editor,actions,purchase.access==AccessState.active,back) {
        LaunchedEffect(Unit) {graph.usageFunnel.paywall(com.bontecou.whereday.platform.UsagePaywall.webhook)}
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("One individual lifetime purchase unlocks exports and webhooks. Recording, imports and previews remain free.")
            purchase.message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
            if(purchase.access==AccessState.active) Text("Purchase verified")
            else purchase.product?.let {product->Button(enabled=!purchase.busy && !checkout && purchase.access!=AccessState.pending,onClick={graph.purchases.purchase(activity,com.bontecou.whereday.platform.UsagePaywall.webhook)}) {Text("Buy lifetime access · ${product.formattedPrice}")}}
            OutlinedButton(enabled=!purchase.busy && !checkout,onClick={graph.purchases.restore(com.bontecou.whereday.platform.UsagePaywall.webhook)}) {Text("Restore purchases")}
            if(purchase.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable internal fun WebhookSettingsContent(ui:WebhookUiState,editor:WebhookEditorViewModel,actions:WebhookActions,
    hasAccess:Boolean,back:()->Unit,purchasePanel:@Composable ()->Unit={}) {
    val currentUi by rememberUpdatedState(ui)
    val currentAccess by rememberUpdatedState(hasAccess)
    val config=ui.state?.configuration
    val queue=ui.state?.queue
    LaunchedEffect(config) {config?.let(editor::reconcile)}
    val draft=editor.draft;val settings=draft.settings;val credentials=draft.credentials
    val conflict=config!=null && editor.baseline!=null && config.revision!=editor.baseline?.revision
    val editable=ui.loaded && config!=null && editor.baseline!=null && !ui.busy
    val dirty=editor.dirty
    var validation by remember {mutableStateOf<String?>(null)}
    var dialog by remember {mutableStateOf<String?>(null)}
    var discardId by remember {mutableStateOf<String?>(null)}
    val focus=LocalFocusManager.current
    fun guarded(block:()->Unit) {if(currentAccess) block() else {focus.clearFocus();dialog="purchase"}}
    fun leave() {focus.clearFocus();if(editor.dirty) dialog=if(currentUi.busy) "leave-busy" else "leave" else back()}
    BackHandler {leave()}
    fun submit(choice:WebhookQueueTransition) {
        val normalized=editor.draft.normalized();editor.edit(normalized)
        val error=normalized.validate();validation=error
        if(error!=null || config==null || conflict) return
        focus.clearFocus();actions.save(normalized,choice,config.revision)
    }
    fun save() {
        focus.clearFocus();validation=editor.draft.normalized().validate()
        if(validation!=null || conflict || config==null) return
        if(settings.enabled && !hasAccess) {dialog="purchase";return}
        if((queue?.queuedPoints ?: 0)>0 || (queue?.batches ?: 0)>0) {dialog=if(settings.enabled) "queue" else "disable-save";return}
        if(settings.enabled && (!config.settings.enabled || settings.endpoint.trim()!=config.settings.endpoint.trim())) {dialog="enable";return}
        submit(WebhookQueueTransition.requireEmpty)
    }
    fun edit(value:WebhookDraft) {editor.edit(value)}
    val date=remember {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withZone(ZoneId.systemDefault())}
    Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("webhook-screen").verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick={leave()}) {Text("Back to settings")}
        Text("Webhooks",style=MaterialTheme.typography.headlineMedium)
        Text("Send location data to a server you configure. Enable and save to start delivery.")
        if(!ui.loaded && ui.error==null) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading webhook settings…")}
        if(ui.loaded && config!=null) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {Text("Enable webhook delivery");Text(if(config.settings.enabled) "Currently enabled" else "Currently disabled",style=MaterialTheme.typography.bodySmall)}
                Switch(settings.enabled,{edit(draft.copy(settings=settings.copy(enabled=it)))},enabled=editable,modifier=Modifier.testTag("webhook-enabled").semantics {contentDescription="Enable webhook delivery"})
            }
            Text("Changes apply when saved. Automatic modes send newly recorded points; manual mode sends only at your request.",style=MaterialTheme.typography.bodySmall)
            WebhookInput("Endpoint URL",settings.endpoint,{edit(draft.copy(settings=settings.copy(endpoint=it.take(16384))))},editable,"webhook-url",secret=true,keyboard=KeyboardType.Uri)
            Text("Use HTTPS. URLs can contain credentials; revealing or copying them may expose secrets.",style=MaterialTheme.typography.bodySmall)
            WebhookMenu("Format",settings.format,ExportFormat.entries.map {it to when(it) {ExportFormat.owntracks->"OwnTracks";ExportFormat.overland->"Overland";ExportFormat.markdown->"Markdown";else->it.name.uppercase() }},editable) {edit(draft.copy(settings=settings.copy(format=it)))}
            WebhookMenu("Authentication",settings.auth,listOf(WebhookAuth.none to "None",WebhookAuth.apiKeyQuery to "API key in URL",WebhookAuth.bearer to "Bearer token",WebhookAuth.basic to "Basic",WebhookAuth.customHeader to "Custom header"),editable) {
                edit(draft.copy(settings=settings.copy(auth=it),credentials=WebhookCredentials(key=if(it==WebhookAuth.customHeader) "X-API-Key" else "api_key")))
            }
            if(settings.auth in setOf(WebhookAuth.apiKeyQuery,WebhookAuth.customHeader)) WebhookInput(if(settings.auth==WebhookAuth.apiKeyQuery) "Parameter name" else "Header name",credentials.key,{edit(draft.copy(credentials=credentials.copy(key=it.take(256))))},editable,"webhook-auth-key")
            if(settings.auth==WebhookAuth.basic) WebhookInput("Username",credentials.username,{edit(draft.copy(credentials=credentials.copy(username=it.take(1024))))},editable,"webhook-username")
            if(settings.auth!=WebhookAuth.none) key(settings.auth) {WebhookInput(if(settings.auth==WebhookAuth.basic) "Password" else "Authentication value",credentials.value,{edit(draft.copy(credentials=credentials.copy(value=it.take(8192))))},editable,"webhook-auth-value",secret=true)}
            if(settings.auth==WebhookAuth.apiKeyQuery) Text("API keys in URLs can appear in server and proxy logs. Use header authentication when your server supports it.",style=MaterialTheme.typography.bodySmall)
            WebhookMenu("Send mode",settings.mode,listOf(WebhookMode.realtime to "Realtime",WebhookMode.batchCount to "By count",WebhookMode.batchTime to "By time",WebhookMode.manual to "Manual"),editable) {edit(draft.copy(settings=settings.copy(mode=it)))}
            if(settings.mode==WebhookMode.batchCount) WebhookMenu("Batch size",settings.batchCount,listOf(5,10,25,50,100).map {it to "$it points"},editable) {edit(draft.copy(settings=settings.copy(batchCount=it)))}
            if(settings.mode==WebhookMode.batchTime) WebhookMenu("Batch interval",settings.batchMinutes,listOf(1,5,15,30,60).map {it to if(it==60) "1 hour" else "$it min"},editable) {edit(draft.copy(settings=settings.copy(batchMinutes=it)))}
            Text(when(settings.mode) {WebhookMode.realtime->"Newly accepted fixes are queued for delivery as soon as Android can run the job.";WebhookMode.batchCount->"Send all buffered points when the selected count is reached.";WebhookMode.batchTime->"Send buffered points at the selected interval. Android may delay background runs.";WebhookMode.manual->"Send all saved history with Send history now. Automatic points are not captured."},style=MaterialTheme.typography.bodySmall)
            validation?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("webhook-validation"))}
            if(conflict) {Text("Saved settings changed. Reload to review them; unsaved edits will be discarded.",color=MaterialTheme.colorScheme.error);OutlinedButton(onClick={editor.reload(config);validation=null}) {Text("Reload saved settings")}}
            Button(enabled=editable && dirty && !conflict,onClick={save()},modifier=Modifier.fillMaxWidth()) {Text("Save webhook settings")}
            if(dirty) Text("Unsaved changes",style=MaterialTheme.typography.bodySmall)
            if(config.settings.enabled) OutlinedButton(onClick={focus.clearFocus();dialog="disable"},modifier=Modifier.fillMaxWidth()) {Text("Disable webhooks")}
            HorizontalDivider()
            Text("Delivery status",style=MaterialTheme.typography.titleLarge)
            Text(queue?.policy?.lastSuccess?.let {"Last sent: ${date.format(it)}"} ?: "No successful webhook delivery yet.")
            Text("${queue?.queuedPoints ?: 0} live points waiting · ${queue?.batches ?: 0} pending requests")
            ui.nextAttempt?.let {Text("Next planned attempt: ${date.format(it)}. Android may delay it.",style=MaterialTheme.typography.bodySmall)}
            queue?.policy?.lastStatus?.let {Text(if(it==0) "Last attempt was interrupted or could not reach the server." else "Last response: HTTP $it",style=MaterialTheme.typography.bodySmall)}
            queue?.head?.let {head->
                Text("Oldest request: ${head.pointCount} points, ${head.visitCount} visits",style=MaterialTheme.typography.bodySmall)
                if(settings.mode==WebhookMode.manual && head.kind=="points") Text("This automatic batch is paused in manual mode. Flush or discard it explicitly.",style=MaterialTheme.typography.bodySmall)
                if(head.stopped) OutlinedButton(enabled=!ui.busy && !dirty,onClick={guarded {actions.retry(head.id)}}) {Text("Retry oldest request")}
                TextButton(enabled=!ui.busy,onClick={discardId=head.id;dialog="discard"}) {Text("Discard oldest request")}
            }
            if(ui.backgroundPaused) OutlinedButton(enabled=!ui.busy && !dirty,onClick={guarded(actions.resume)}) {Text("Retry background delivery")}
            if((queue?.queuedPoints ?: 0)>0) OutlinedButton(enabled=config.settings.enabled && !ui.busy && !dirty,onClick={guarded {dialog="flush"}}) {Text("Flush queued points")}
            OutlinedButton(enabled=!ui.busy && !dirty && config.settings.endpoint.isNotBlank(),onClick={guarded(actions.test)},modifier=Modifier.fillMaxWidth()) {Text("Test connection")}
            Text("A connection test sends one synthetic point at 0°, 0°, without reading saved history.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=config.settings.enabled && !ui.busy && !dirty,onClick={guarded {dialog="history"}},modifier=Modifier.fillMaxWidth()) {Text("Send history now")}
            Text("Manual history sends include all saved visits and points. Delivery can be repeated after an interrupted response; your receiver must support idempotency to suppress duplicates.",style=MaterialTheme.typography.bodySmall)
            if(dirty) Text("Save or discard edits before sending or testing.",style=MaterialTheme.typography.bodySmall)
        }
        if(ui.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Updating webhook delivery…")}
        ui.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        ui.message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        if(!ui.loaded) TextButton(enabled=!ui.busy,onClick=actions.refresh) {Text("Retry loading webhook settings")}
        TextButton(enabled=!ui.busy,onClick={focus.clearFocus();dialog="purchase"}) {Text("Lifetime access and purchases")}
        TextButton(onClick={focus.clearFocus();dialog="reset"}) {Text("Reset webhook settings",color=MaterialTheme.colorScheme.error)}
    }
    when(dialog) {
        "purchase"->AlertDialog(onDismissRequest={dialog=null},title={Text("Lifetime export access")},text=purchasePanel,confirmButton={TextButton(onClick={dialog=null}) {Text("Close")}})
        "leave-busy"->ConfirmWebhook("Leave while webhook work finishes?","Submitted work can finish after you leave. Your unsaved edits remain in memory while this app stays open.","Leave screen",{dialog=null;back()},{dialog=null})
        "leave"->ConfirmWebhook("Discard unsaved webhook edits?","Your saved settings and queued deliveries stay as they are.","Discard edits",{editor.reload(config ?: WebhookConfiguration());dialog=null;back()},{dialog=null})
        "enable"->ConfirmWebhook("Enable webhook delivery?","Location data will be sent to your configured server. Review the endpoint and authentication before enabling.","Enable and save",{dialog=null;submit(WebhookQueueTransition.requireEmpty)},{dialog=null})
        "disable-save"->ConfirmWebhook("Disable and clear queued deliveries?","Pending webhook data will be removed. Your journal history stays on this phone.","Disable and save",{dialog=null;submit(WebhookQueueTransition.discard)},{dialog=null})
        "disable"->ConfirmWebhook("Disable webhooks?","Active delivery will be canceled and queued webhook data removed. Journal history stays on this phone.","Disable webhooks",{dialog=null;actions.disable()},{dialog=null})
        "queue"->AlertDialog(onDismissRequest={dialog=null},title={Text("What should happen to queued data?")},text={Text(if(settings.format!=config?.settings?.format) "Changing format requires discarding the queued payloads. Journal history stays on this phone." else "Keeping queued data sends it using the endpoint and authentication you are saving. Discarding removes only pending webhook data.")},confirmButton={Column {
            if(settings.format==config?.settings?.format) TextButton(onClick={dialog=null;submit(WebhookQueueTransition.retain)}) {Text("Save and keep queued data")}
            TextButton(onClick={dialog=null;submit(WebhookQueueTransition.discard)}) {Text("Save and discard queued data")}
        }},dismissButton={TextButton(onClick={dialog=null}) {Text("Cancel")}})
        "history"->ConfirmWebhook("Send all saved history?","All saved visits and points will be sent to your configured server. This can include data already sent earlier.","Send history",{dialog=null;actions.sendHistory()},{dialog=null})
        "flush"->ConfirmWebhook("Flush queued points?","Send buffered points even if the selected count or interval has not been reached.","Flush points",{dialog=null;actions.flush()},{dialog=null})
        "discard"->ConfirmWebhook("Discard oldest queued request?","This removes its pending webhook payload. Journal history stays on this phone.","Discard request",{val id=discardId;dialog=null;if(id!=null) actions.discard(id)},{dialog=null})
        "reset"->ConfirmWebhook("Reset webhook settings?","Disable delivery and remove the saved endpoint, credentials and queued webhook data. Journal history stays on this phone.","Reset webhooks",{dialog=null;editor.resetDraft();actions.reset()},{dialog=null})
    }
}

@Composable private fun ConfirmWebhook(title:String,message:String,confirm:String,action:()->Unit,cancel:()->Unit) {
    AlertDialog(onDismissRequest=cancel,title={Text(title)},text={Text(message)},confirmButton={TextButton(onClick=action) {Text(confirm)}},dismissButton={TextButton(onClick=cancel) {Text("Cancel")}})
}
@Composable private fun WebhookInput(label:String,value:String,change:(String)->Unit,enabled:Boolean,tag:String,secret:Boolean=false,keyboard:KeyboardType=KeyboardType.Text) {
    var revealed by remember {mutableStateOf(false)}
    val focus=LocalFocusManager.current
    OutlinedTextField(value,change,label={Text(label)},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth().testTag(tag),
        visualTransformation=if(secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.None,autoCorrectEnabled=false,keyboardType=if(secret && keyboard!=KeyboardType.Uri) KeyboardType.Password else keyboard,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={focus.clearFocus()}),
        trailingIcon=if(secret) {{IconButton(onClick={revealed=!revealed}) {Icon(if(revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,contentDescription=if(revealed) "Hide $label" else "Show $label")}}} else null)
}
@Composable private fun <T> WebhookMenu(label:String,value:T,choices:List<Pair<T,String>>,enabled:Boolean,change:(T)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth()) {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {OutlinedButton(enabled=enabled,onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {Text(choices.firstOrNull {it.first==value}?.second.orEmpty())}
            DropdownMenu(expanded,{expanded=false}) {choices.forEach {(choice,text)->DropdownMenuItem(text={Text(text)},onClick={expanded=false;change(choice)})}}
        }
    }
}
