package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.ExportFileRequest
import com.bontecou.whereday.platform.PreparedExport
import com.bontecou.whereday.platform.ExportPickerPhase
import com.bontecou.whereday.platform.ExportDeliveryNotice
import com.bontecou.whereday.platform.ExportDeliveryException
import com.bontecou.whereday.platform.exportDeliveryNotice
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import com.bontecou.whereday.data.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import java.time.Instant
import com.bontecou.whereday.domain.InferenceRules
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

private enum class ExportUiOperation {preview,delivery}
private data class ExportPreviewRequest(val format:ExportFormat,val options:ExportOptions,val outings:Boolean,val inference:InferenceRules?,val locale:String)
private data class ExportPreviewResult(val request:ExportPreviewRequest,val text:String)

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ExportScreen(graph: AppGraph,renderPreview:suspend (ExportFormat,ExportOptions,Boolean,InferenceRules?)->String={f,o,outings,inference->graph.exports.preview(f,o,outings,inference)}) {
    val importing by graph.historyImports.state.collectAsStateWithLifecycle()
    if(importing.phase!=com.bontecou.whereday.platform.HistoryImportPhase.idle) {HistoryImportScreen(graph.historyImports);return}
    val count by graph.history.dao.pointCount().collectAsStateWithLifecycle(0L)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val purchase by graph.purchases.state.collectAsStateWithLifecycle()
    val checkout by graph.purchases.checkoutInFlight.collectAsStateWithLifecycle()
    val loadedSettings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val settings=loadedSettings ?: AppSettings()
    val saved by graph.exportSettings.state.collectAsStateWithLifecycle()
    val selection=saved.selection
    val format=selection.format
    val options=selection.options
    val kind=options.kind
    val filename=selection.filename
    val splitDays=selection.splitDays
    val extraFormats=selection.additionalFormats
    val ready=saved.ready && !saved.saving && saved.error==null && loadedSettings!=null
    var advanced by rememberSaveable { mutableStateOf(false) }
    val picker by graph.exportPicker.state.collectAsStateWithLifecycle()
    val pending=picker.request?.takeIf {it.active}
    var pickerRequest by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(Unit) {graph.exportPicker.refresh()}
    var progress by remember { mutableStateOf<ExportCounts?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var preview by remember { mutableStateOf<ExportPreviewResult?>(null) }
    var failedPreview by remember {mutableStateOf<ExportPreviewRequest?>(null)}
    var notice by remember { mutableStateOf<ExportDeliveryNotice?>(null) }
    var savedFiles by remember {mutableStateOf<Int?>(null)}
    val shareTitle=stringResource(R.string.export_delivery_share_title)
    val number=NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    var operation by remember {mutableStateOf<ExportUiOperation?>(null)}
    val busy=operation!=null
    val canEdit=saved.ready && saved.failure==null && loadedSettings!=null && !busy
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val active=remember {AtomicBoolean(true)}
    val request=ExportPreviewRequest(format,options,selection.outings,settings.inferenceRules.takeIf {settings.inferredEnabled},com.bontecou.whereday.platform.AndroidHistoryLocale.capture(context).toLanguageTag())
    val latestRequest=rememberUpdatedState(request)
    LaunchedEffect(request) {preview=null;failedPreview=null;if(operation==ExportUiOperation.preview) exportJob?.cancel()}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {exportJob?.cancel();preview=null;failedPreview=null}}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);exportJob?.cancel();lifecycle.removeObserver(observer)}
    }
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri=result.data?.data.takeIf {result.resultCode==android.app.Activity.RESULT_OK}
        val id=pickerRequest
        pickerRequest=null
        graph.exportPicker.result(id,uri,result.data?.flags ?: 0)
    }
    fun deliver(destination:String) {
        if(operation!=null || !ready || !picker.loaded || picker.busy || pending!=null || !active.get() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || latestRequest.value!=request) return
        operation=ExportUiOperation.delivery;notice=null;savedFiles=null;progress=null
        exportJob=scope.launch {
            var prepared:PreparedExport?=null
            var keepPrepared=false
            try {
                val task=currentCoroutineContext().job
                prepared=graph.exports.prepare(ExportFileRequest(extraFormats+format,options.copy(kind=kind),filename,splitDays,outings=selection.outings,inference=request.inference)) {counts->if(task.isActive) progress=counts}
                when(destination) {
                    "share" -> context.startActivity(android.content.Intent.createChooser(graph.exports.share(prepared),shareTitle))
                    "folder" -> {
                        val folder=settings.exportFolder ?: throw ExportDeliveryException(ExportDeliveryNotice.folderRequired)
                        val saved=graph.exports.saveToFolder(prepared,android.net.Uri.parse(folder))
                        savedFiles=saved.size;notice=ExportDeliveryNotice.saved
                    }
                    else -> {
                        if(prepared.files.size!=1) throw ExportDeliveryException(ExportDeliveryNotice.multipleFiles)
                        val request=graph.exports.beginPicker(prepared)
                        keepPrepared=true;pickerRequest=request.id
                        graph.exportPicker.refresh()
                        saver.launch(graph.exports.pickerIntent(request.id))
                    }
                }
            } catch(e:Exception) {if(e is CancellationException) throw e;notice=exportDeliveryNotice(e)}
            finally {
                // Keep shared files for receivers; subsequent preparation cleans cache entries older than a day.
                if(destination!="share" && prepared!=null) withContext(NonCancellable) {
                    val retained=keepPrepared || runCatching {graph.exports.pickerState()?.let {it.id==prepared.id && it.active}==true}.getOrDefault(true)
                    if(!retained) graph.exports.discard(prepared)
                }
                operation=null;progress=null
            }
        }
    }
    fun showPreview() {
        if(operation!=null || !ready || !active.get() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || latestRequest.value!=request) return
        operation=ExportUiOperation.preview;notice=null;failedPreview=null;preview=null
        exportJob=scope.launch {
            try {
                val text=renderPreview(request.format,request.options,request.outings,request.inference)
                currentCoroutineContext().ensureActive()
                if(active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && latestRequest.value==request) preview=ExportPreviewResult(request,text)
            } catch(e:Exception) {if(e is CancellationException) throw e;if(latestRequest.value==request) failedPreview=request}
            finally {operation=null}
        }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) graph.historyImports.select(com.bontecou.whereday.platform.AndroidHistoryImportInput(context,uri),locale=com.bontecou.whereday.platform.AndroidHistoryLocale.capture(context))
    }
    LaunchedEffect(Unit) {graph.usageFunnel.paywall(com.bontecou.whereday.platform.UsagePaywall.export)}
    val deliverable=!busy && ready && picker.loaded && !picker.busy && pending==null
    val stacked=LocalDensity.current.fontScale>=1.3f
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("export-screen")) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=20.dp)) {
            WdPageHeader(stringResource(R.string.export_title),eyebrow=stringResource(R.string.export_redesign_eyebrow))
            Text(pluralStringResource(R.plurals.export_stored_points,count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),number.format(count)),Modifier.padding(horizontal=24.dp).padding(top=6.dp).testTag("export-history-count"),style=WdType.caption,color=wd.textMuted)
            if(count==0L) ExportCallout(stringResource(R.string.export_redesign_empty_body),Modifier.exportInset().padding(top=16.dp),title=stringResource(R.string.export_redesign_empty_title))
            Box(Modifier.exportInset().padding(top=16.dp)) {
                LifetimeExportAccess(purchase,checkout,buy={
                    val activity=generateSequence(context) {(it as? android.content.ContextWrapper)?.baseContext}.filterIsInstance<android.app.Activity>().firstOrNull()
                    if(activity!=null) graph.purchases.purchase(activity,com.bontecou.whereday.platform.UsagePaywall.export)
                },restore={graph.purchases.restore(com.bontecou.whereday.platform.UsagePaywall.export)})
            }
            saved.failure?.let {ExportCallout(exportSettingsText(it),Modifier.exportInset().padding(top=12.dp),warning=true) {
                WdButton(stringResource(if(saved.ready) R.string.export_settings_retry else R.string.export_settings_reset),{if(saved.ready) graph.exportSettings.retry() else graph.exportSettings.reset()})
            }}
            if(saved.saving) ExportBusyLine(stringResource(R.string.export_settings_saving),Modifier.padding(horizontal=24.dp).padding(top=12.dp))
            WdSectionHeader(stringResource(R.string.export_redesign_section_recipe))
            val kinds=mapOf("visits" to stringResource(R.string.export_data_visits),"points" to stringResource(R.string.export_data_points),"all" to stringResource(R.string.export_data_all),"outings" to stringResource(R.string.export_data_outings))
            ExportCard {
                ExportChoiceRow(stringResource(R.string.export_format),format.name,ExportFormat.entries.map { it.name },enabled=canEdit) { graph.exportSettings.update(selection.copy(format=ExportFormat.valueOf(it))) }
                ExportChoiceRow(stringResource(R.string.export_data),if(selection.outings) "outings" else kind.name,DataKind.entries.map {it.name}+"outings",enabled=canEdit,divider=false,labelFor={kinds.getValue(it)}) {
                    graph.exportSettings.update(if(it=="outings") selection.copy(outings=true) else selection.copy(outings=false,options=options.copy(kind=DataKind.valueOf(it))))
                }
            }
            if(selection.outings) WdSectionFooter(stringResource(R.string.export_outings_help))
            WdSectionHeader(stringResource(R.string.export_redesign_section_preview))
            ExportCard {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    WdButton(stringResource(if(operation==ExportUiOperation.preview) R.string.export_preview_loading else R.string.export_preview),{showPreview()},Modifier.testTag("export-preview-start"),enabled=!busy && ready,icon=Icons.Outlined.Visibility)
                    if(operation==ExportUiOperation.preview) {ExportProgress();ExportLink(stringResource(R.string.export_delivery_cancel_preparation),{exportJob?.cancel()},Modifier.testTag("export-cancel-operation"))}
                    if(failedPreview==request) Text(stringResource(R.string.export_preview_failed),Modifier.testTag("export-preview-error"),style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)
                    preview?.takeIf {it.request==request}?.let {result->
                        val shape=RoundedCornerShape(10.dp)
                        Box(Modifier.fillMaxWidth().heightIn(max=360.dp).clip(shape).background(wd.surface).border(1.dp,wd.border,shape)) {
                            SelectionContainer {Text(result.text,Modifier.verticalScroll(rememberScrollState()).padding(12.dp).testTag("export-preview-result"),style=WdType.caption2,color=wd.textPrimary)}
                        }
                    }
                }
            }
            WdSectionFooter(stringResource(if(selection.outings) R.string.export_preview_outings_help else R.string.export_preview_history_help))
            WdSectionHeader(stringResource(R.string.export_redesign_section_send))
            ExportCard {
                WdNavRow(stringResource(R.string.export_delivery_export_folder),icon=Icons.Outlined.Folder,divider=false,enabled=deliverable && settings.exportFolder!=null) {deliver("folder")}
            }
            WdSectionFooter(stringResource(R.string.export_delivery_folder_policy))
            notice?.let {ExportCallout(exportDeliveryText(it,savedFiles),Modifier.exportInset().padding(top=12.dp),warning=it.isFailure)}
            picker.notice?.let {ExportCallout(exportDeliveryText(it,savedFiles),Modifier.exportInset().padding(top=12.dp),warning=it.isFailure)}
            if(pending!=null) {
                ExportCallout(stringResource(R.string.export_delivery_pending_help),Modifier.exportInset().padding(top=12.dp),title=stringResource(R.string.export_delivery_pending,pending.displayName)) {
                    if(pending.destination!=null) WdButton(stringResource(R.string.export_delivery_resume),{graph.exportPicker.resume(pending.id)},enabled=!busy && !picker.busy,emphasis=WdEmphasis.primary)
                    WdButton(stringResource(R.string.export_delivery_choose),{scope.launch {
                        try {pickerRequest=pending.id;saver.launch(graph.exports.pickerIntent(pending.id));graph.exportPicker.refresh()}
                        catch(e:Exception) {if(e is CancellationException) throw e;notice=exportDeliveryNotice(e,ExportDeliveryNotice.unavailable)}
                    }},enabled=!busy && !picker.busy)
                    ExportLink(stringResource(R.string.export_delivery_cancel_pending),{graph.exportPicker.cancel(pending.id)},destructive=true)
                }
            } else if(picker.notice!=null && picker.request==null) ExportLink(stringResource(R.string.export_delivery_discard),{graph.exportPicker.discardInterrupted()},Modifier.exportInset())
            WdSectionHeader(stringResource(R.string.export_redesign_section_import))
            ExportCard {
                WdNavRow(stringResource(R.string.export_import),icon=Icons.Outlined.FileUpload,divider=false,enabled=!busy) {importer.launch(arrayOf("application/json","text/*","application/octet-stream"))}
            }
            WdSectionFooter(stringResource(R.string.export_import_help))
            WdSectionHeader(stringResource(R.string.export_redesign_section_options))
            ExportCard {
                WdRow(Modifier.testTag("export-advanced"),divider=false,onClick={advanced=!advanced}) {
                    Icon(Icons.Outlined.Tune,null,Modifier.size(20.dp),tint=wd.textMuted)
                    Text(stringResource(if(advanced) R.string.export_filters_hide else R.string.export_filters_show),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                    Icon(if(advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,null,Modifier.size(18.dp),tint=wd.textMuted)
                }
            }
            if(advanced) {
                ExportFilters(options,{graph.exportSettings.update(selection.copy(options=it))},kind,outings=selection.outings,enabled=canEdit,settingsReady=loadedSettings!=null && saved.ready)
                val presets=mapOf("readable" to FilenamePlanner.readablePattern,"compact" to FilenamePlanner.compactPattern,"folders" to FilenamePlanner.datedFoldersPattern)
                val presetLabels=mapOf("readable" to stringResource(R.string.export_filename_readable),"compact" to stringResource(R.string.export_filename_compact),"folders" to stringResource(R.string.export_filename_folders),"custom" to stringResource(R.string.export_filename_custom))
                WdSectionHeader(stringResource(R.string.export_redesign_section_files))
                ExportCard {
                    ExportChoiceRow(stringResource(R.string.export_filename_preset),presets.entries.firstOrNull {it.value==filename}?.key ?: "custom",presets.keys.toList(),enabled=canEdit,labelFor={presetLabels.getValue(it)}) {graph.exportSettings.update(selection.copy(filename=presets.getValue(it)))}
                    OutlinedTextField(value=filename,onValueChange={if(it.length<=4096) graph.exportSettings.update(selection.copy(filename=it))},enabled=canEdit,label={Text(stringResource(R.string.export_filename_template))},
                        textStyle=WdType.monoBody,colors=wdTextFieldColors(),shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp).testTag("export-filename"))
                    WdDivider(Modifier.padding(start=16.dp))
                    ExportToggleRow(stringResource(if(selection.outings) R.string.export_split_outing else R.string.export_split_day),splitDays,enabled=canEdit,modifier=Modifier.testTag("export-split"),divider=false) {graph.exportSettings.update(selection.copy(splitDays=it))}
                }
                WdSectionFooter(stringResource(R.string.export_filename_help))
                WdSectionHeader(stringResource(R.string.export_additional_formats))
                ExportCard {
                    val extras=ExportFormat.entries.filter {it!=format}
                    extras.forEach {extra -> ExportToggleRow(extra.name,extra in extraFormats,enabled=canEdit,modifier=Modifier.testTag("export-extra-${extra.name}"),divider=extra!=extras.last()) {enabled ->graph.exportSettings.update(selection.copy(additionalFormats=if(enabled) extraFormats+extra else extraFormats-extra))}}
                }
            }
        }
        // Pinned above the floating tab bar like iOS's export footer. It scrolls itself when large text outgrows its cap.
        Column(Modifier.fillMaxWidth().background(wd.surface).heightIn(max=320.dp)) {
            WdDivider()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=12.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(operation==ExportUiOperation.delivery || picker.busy) ExportProgress()
                progress?.let {Text(stringResource(R.string.export_delivery_progress,number.format(it.visits),number.format(it.points)),style=WdType.caption,color=wd.textMuted)}
                if(operation==ExportUiOperation.delivery && exportJob?.isActive==true) ExportLink(stringResource(R.string.export_delivery_cancel_preparation),{exportJob?.cancel()},Modifier.testTag("export-cancel-operation"))
                val save:@Composable (Modifier)->Unit={WdButton(stringResource(R.string.export_delivery_save_export_as),{deliver("save")},it,enabled=deliverable && !splitDays && (extraFormats+format).size==1)}
                val share:@Composable (Modifier)->Unit={WdButton(stringResource(R.string.export_delivery_share_export),{deliver("share")},it,emphasis=WdEmphasis.primary,enabled=deliverable,busy=operation==ExportUiOperation.delivery,icon=Icons.Outlined.IosShare)}
                if(stacked) {share(Modifier);save(Modifier)}
                else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {save(Modifier.weight(1f));share(Modifier.weight(1.4f))}
            }
        }
    }
}
