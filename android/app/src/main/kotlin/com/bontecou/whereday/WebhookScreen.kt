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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
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
            Text(stringResource(R.string.webhook_purchase_body))
            purchase.notice?.let {Text(billingNoticeText(it),style=MaterialTheme.typography.bodySmall)}
            if(purchase.access==AccessState.active) Text(stringResource(R.string.webhook_verified))
            else purchase.product?.let {product->Button(enabled=!purchase.busy && !checkout && purchase.access!=AccessState.pending,onClick={graph.purchases.purchase(activity,com.bontecou.whereday.platform.UsagePaywall.webhook)}) {Text(stringResource(R.string.webhook_buy,product.formattedPrice))}}
            OutlinedButton(enabled=!purchase.busy && !checkout,onClick={graph.purchases.restore(com.bontecou.whereday.platform.UsagePaywall.webhook)}) {Text(stringResource(R.string.webhook_restore))}
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
    var validation by remember {mutableStateOf<WebhookDraftFailure?>(null)}
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
    val locale=LocalConfiguration.current.locales[0]
    val zone=ZoneId.systemDefault()
    val date=remember(locale,zone) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale).withZone(zone)}
    val number=NumberFormat.getIntegerInstance(locale)
    val enabledLabel=stringResource(R.string.webhook_enabled_label)
    Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("webhook-screen").verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick={leave()}) {Text(stringResource(R.string.webhook_back))}
        Text(stringResource(R.string.webhook_title),style=MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.webhook_intro))
        if(!ui.loaded && ui.error==null) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.webhook_loading))}
        if(ui.loaded && config!=null) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {Text(stringResource(R.string.webhook_enabled_label));Text(if(config.settings.enabled) stringResource(R.string.webhook_enabled) else stringResource(R.string.webhook_disabled),style=MaterialTheme.typography.bodySmall)}
                Switch(settings.enabled,{edit(draft.copy(settings=settings.copy(enabled=it)))},enabled=editable,modifier=Modifier.testTag("webhook-enabled").semantics {contentDescription=enabledLabel})
            }
            Text(stringResource(R.string.webhook_save_help),style=MaterialTheme.typography.bodySmall)
            WebhookInput(stringResource(R.string.webhook_endpoint),settings.endpoint,{edit(draft.copy(settings=settings.copy(endpoint=it.take(16384))))},editable,"webhook-url",secret=true,keyboard=KeyboardType.Uri)
            Text(stringResource(R.string.webhook_endpoint_help),style=MaterialTheme.typography.bodySmall)
            WebhookMenu(stringResource(R.string.webhook_format),settings.format,ExportFormat.entries.map {it to when(it) {ExportFormat.owntracks->"OwnTracks";ExportFormat.overland->"Overland";ExportFormat.markdown->"Markdown";else->it.name.uppercase() }},editable) {edit(draft.copy(settings=settings.copy(format=it)))}
            WebhookMenu(stringResource(R.string.webhook_auth),settings.auth,listOf(WebhookAuth.none to stringResource(R.string.webhook_auth_none),WebhookAuth.apiKeyQuery to stringResource(R.string.webhook_auth_query),WebhookAuth.bearer to stringResource(R.string.webhook_auth_bearer),WebhookAuth.basic to stringResource(R.string.webhook_auth_basic),WebhookAuth.customHeader to stringResource(R.string.webhook_auth_header)),editable) {
                edit(draft.copy(settings=settings.copy(auth=it),credentials=WebhookCredentials(key=if(it==WebhookAuth.customHeader) "X-API-Key" else "api_key")))
            }
            if(settings.auth in setOf(WebhookAuth.apiKeyQuery,WebhookAuth.customHeader)) WebhookInput(if(settings.auth==WebhookAuth.apiKeyQuery) stringResource(R.string.webhook_parameter) else stringResource(R.string.webhook_header),credentials.key,{edit(draft.copy(credentials=credentials.copy(key=it.take(256))))},editable,"webhook-auth-key")
            if(settings.auth==WebhookAuth.basic) WebhookInput(stringResource(R.string.webhook_username),credentials.username,{edit(draft.copy(credentials=credentials.copy(username=it.take(1024))))},editable,"webhook-username")
            if(settings.auth!=WebhookAuth.none) key(settings.auth) {WebhookInput(if(settings.auth==WebhookAuth.basic) stringResource(R.string.webhook_password) else stringResource(R.string.webhook_auth_value),credentials.value,{edit(draft.copy(credentials=credentials.copy(value=it.take(8192))))},editable,"webhook-auth-value",secret=true)}
            if(settings.auth==WebhookAuth.apiKeyQuery) Text(stringResource(R.string.webhook_query_help),style=MaterialTheme.typography.bodySmall)
            WebhookMenu(stringResource(R.string.webhook_mode),settings.mode,listOf(WebhookMode.realtime to stringResource(R.string.webhook_mode_realtime),WebhookMode.batchCount to stringResource(R.string.webhook_mode_count),WebhookMode.batchTime to stringResource(R.string.webhook_mode_time),WebhookMode.manual to stringResource(R.string.webhook_mode_manual)),editable) {edit(draft.copy(settings=settings.copy(mode=it)))}
            if(settings.mode==WebhookMode.batchCount) WebhookMenu(stringResource(R.string.webhook_batch_size),settings.batchCount,listOf(5,10,25,50,100).map {it to pluralStringResource(R.plurals.webhook_points,it,number.format(it))},editable) {edit(draft.copy(settings=settings.copy(batchCount=it)))}
            if(settings.mode==WebhookMode.batchTime) WebhookMenu(stringResource(R.string.webhook_batch_interval),settings.batchMinutes,listOf(1,5,15,30,60).map {it to if(it==60) stringResource(R.string.webhook_hour) else stringResource(R.string.webhook_minutes,number.format(it))},editable) {edit(draft.copy(settings=settings.copy(batchMinutes=it)))}
            Text(when(settings.mode) {WebhookMode.realtime->stringResource(R.string.webhook_realtime_help);WebhookMode.batchCount->stringResource(R.string.webhook_count_help);WebhookMode.batchTime->stringResource(R.string.webhook_time_help);WebhookMode.manual->stringResource(R.string.webhook_manual_help)},style=MaterialTheme.typography.bodySmall)
            validation?.let {Text(webhookValidationText(it),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("webhook-validation"))}
            if(conflict) {Text(stringResource(R.string.webhook_conflict),color=MaterialTheme.colorScheme.error);OutlinedButton(onClick={editor.reload(config);validation=null}) {Text(stringResource(R.string.webhook_reload))}}
            Button(enabled=editable && dirty && !conflict,onClick={save()},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.webhook_save))}
            if(dirty) Text(stringResource(R.string.webhook_unsaved),style=MaterialTheme.typography.bodySmall)
            if(config.settings.enabled) OutlinedButton(onClick={focus.clearFocus();dialog="disable"},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.webhook_disable_action))}
            HorizontalDivider()
            Text(stringResource(R.string.webhook_status),style=MaterialTheme.typography.titleLarge)
            Text(queue?.policy?.lastSuccess?.let {stringResource(R.string.webhook_last_success,date.format(it))} ?: stringResource(R.string.webhook_no_success))
            Text(stringResource(R.string.webhook_queue_summary,webhookPointCount(queue?.queuedPoints ?: 0,waiting=true),webhookRequestCount(queue?.batches ?: 0)))
            ui.nextAttempt?.let {Text(stringResource(R.string.webhook_next_attempt,date.format(it)),style=MaterialTheme.typography.bodySmall)}
            queue?.policy?.lastStatus?.let {Text(if(it==0) stringResource(R.string.webhook_interrupted) else stringResource(R.string.webhook_response,it),style=MaterialTheme.typography.bodySmall)}
            queue?.head?.let {head->
                Text(stringResource(R.string.webhook_oldest,stringResource(R.string.webhook_queue_summary,webhookPointCount(head.pointCount),webhookVisitCount(head.visitCount))),style=MaterialTheme.typography.bodySmall)
                if(settings.mode==WebhookMode.manual && head.kind=="points") Text(stringResource(R.string.webhook_paused_manual),style=MaterialTheme.typography.bodySmall)
                if(head.stopped) OutlinedButton(enabled=!ui.busy && !dirty,onClick={guarded {actions.retry(head.id)}}) {Text(stringResource(R.string.webhook_retry_oldest))}
                TextButton(enabled=!ui.busy,onClick={discardId=head.id;dialog="discard"}) {Text(stringResource(R.string.webhook_discard_oldest))}
            }
            if(ui.backgroundPaused) OutlinedButton(enabled=!ui.busy && !dirty,onClick={guarded(actions.resume)}) {Text(stringResource(R.string.webhook_retry_background))}
            if((queue?.queuedPoints ?: 0)>0) OutlinedButton(enabled=config.settings.enabled && !ui.busy && !dirty,onClick={guarded {dialog="flush"}}) {Text(stringResource(R.string.webhook_flush_action))}
            OutlinedButton(enabled=!ui.busy && !dirty && config.settings.endpoint.isNotBlank(),onClick={guarded(actions.test)},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.webhook_test))}
            Text(stringResource(R.string.webhook_test_help),style=MaterialTheme.typography.bodySmall)
            Button(enabled=config.settings.enabled && !ui.busy && !dirty,onClick={guarded {dialog="history"}},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.webhook_history_action))}
            Text(stringResource(R.string.webhook_history_help),style=MaterialTheme.typography.bodySmall)
            if(dirty) Text(stringResource(R.string.webhook_dirty_help),style=MaterialTheme.typography.bodySmall)
        }
        if(ui.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.webhook_updating))}
        ui.failure?.let {Text(webhookFailureText(it),color=MaterialTheme.colorScheme.error)}
        ui.feedback?.let {Text(webhookFeedbackText(it),style=MaterialTheme.typography.bodySmall)}
        if(!ui.loaded) TextButton(enabled=!ui.busy,onClick=actions.refresh) {Text(stringResource(R.string.webhook_retry_loading))}
        TextButton(enabled=!ui.busy,onClick={focus.clearFocus();dialog="purchase"}) {Text(stringResource(R.string.webhook_purchases))}
        TextButton(onClick={focus.clearFocus();dialog="reset"}) {Text(stringResource(R.string.webhook_reset_action),color=MaterialTheme.colorScheme.error)}
    }
    when(dialog) {
        "purchase"->WorkspaceAlertDialog(onDismissRequest={dialog=null},title={Text(stringResource(R.string.webhook_purchase_title))},text=purchasePanel,confirmButton={TextButton(onClick={dialog=null}) {Text(stringResource(R.string.webhook_close))}})
        "leave-busy"->ConfirmWebhook(stringResource(R.string.webhook_leave_busy_title),stringResource(R.string.webhook_leave_busy_body),stringResource(R.string.webhook_leave_action),{dialog=null;back()},{dialog=null})
        "leave"->ConfirmWebhook(stringResource(R.string.webhook_leave_title),stringResource(R.string.webhook_leave_body),stringResource(R.string.webhook_discard_edits),{editor.reload(config ?: WebhookConfiguration());dialog=null;back()},{dialog=null})
        "enable"->ConfirmWebhook(stringResource(R.string.webhook_enable_title),stringResource(R.string.webhook_enable_body),stringResource(R.string.webhook_enable_save),{dialog=null;submit(WebhookQueueTransition.requireEmpty)},{dialog=null})
        "disable-save"->ConfirmWebhook(stringResource(R.string.webhook_disable_save_title),stringResource(R.string.webhook_disable_save_body),stringResource(R.string.webhook_disable_save),{dialog=null;submit(WebhookQueueTransition.discard)},{dialog=null})
        "disable"->ConfirmWebhook(stringResource(R.string.webhook_disable_title),stringResource(R.string.webhook_disable_body),stringResource(R.string.webhook_disable_action),{dialog=null;actions.disable()},{dialog=null})
        "queue"->WorkspaceAlertDialog(onDismissRequest={dialog=null},title={Text(stringResource(R.string.webhook_queue_title))},text={Text(if(settings.format!=config?.settings?.format) stringResource(R.string.webhook_queue_format) else stringResource(R.string.webhook_queue_body))},confirmButton={Column {
            if(settings.format==config?.settings?.format) TextButton(onClick={dialog=null;submit(WebhookQueueTransition.retain)}) {Text(stringResource(R.string.webhook_queue_keep))}
            TextButton(onClick={dialog=null;submit(WebhookQueueTransition.discard)}) {Text(stringResource(R.string.webhook_queue_discard))}
        }},dismissButton={TextButton(onClick={dialog=null}) {Text(stringResource(R.string.webhook_cancel))}})
        "history"->ConfirmWebhook(stringResource(R.string.webhook_history_title),stringResource(R.string.webhook_history_body),stringResource(R.string.webhook_history_confirm),{dialog=null;actions.sendHistory()},{dialog=null})
        "flush"->ConfirmWebhook(stringResource(R.string.webhook_flush_title),stringResource(R.string.webhook_flush_body),stringResource(R.string.webhook_flush_confirm),{dialog=null;actions.flush()},{dialog=null})
        "discard"->ConfirmWebhook(stringResource(R.string.webhook_discard_title),stringResource(R.string.webhook_discard_body),stringResource(R.string.webhook_discard_confirm),{val id=discardId;dialog=null;if(id!=null) actions.discard(id)},{dialog=null})
        "reset"->ConfirmWebhook(stringResource(R.string.webhook_reset_title),stringResource(R.string.webhook_reset_body),stringResource(R.string.webhook_reset_confirm),{dialog=null;editor.resetDraft();actions.reset()},{dialog=null})
    }
}

@Composable private fun ConfirmWebhook(title:String,message:String,confirm:String,action:()->Unit,cancel:()->Unit) {
    WorkspaceAlertDialog(onDismissRequest=cancel,title={Text(title)},text={Text(message)},confirmButton={TextButton(onClick=action) {Text(confirm)}},dismissButton={TextButton(onClick=cancel) {Text(stringResource(R.string.webhook_cancel))}})
}
@Composable private fun WebhookInput(label:String,value:String,change:(String)->Unit,enabled:Boolean,tag:String,secret:Boolean=false,keyboard:KeyboardType=KeyboardType.Text) {
    var revealed by remember {mutableStateOf(false)}
    val focus=LocalFocusManager.current
    OutlinedTextField(value,change,label={Text(label)},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth().testTag(tag),
        visualTransformation=if(secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.None,autoCorrectEnabled=false,keyboardType=if(secret && keyboard!=KeyboardType.Uri) KeyboardType.Password else keyboard,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={focus.clearFocus()}),
        trailingIcon=if(secret) {{IconButton(onClick={revealed=!revealed}) {Icon(if(revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,contentDescription=stringResource(if(revealed) R.string.webhook_hide else R.string.webhook_show,label))}}} else null)
}
@Composable private fun <T> WebhookMenu(label:String,value:T,choices:List<Pair<T,String>>,enabled:Boolean,change:(T)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth()) {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {OutlinedButton(enabled=enabled,onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {Text(choices.firstOrNull {it.first==value}?.second.orEmpty())}
            WorkspaceDropdownMenu(expanded,{expanded=false}) {choices.forEach {(choice,text)->DropdownMenuItem(text={Text(text)},onClick={expanded=false;change(choice)})}}
        }
    }
}
