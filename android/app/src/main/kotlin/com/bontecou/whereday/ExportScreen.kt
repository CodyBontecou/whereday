package com.bontecou.whereday

import androidx.compose.foundation.layout.*
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
import com.bontecou.whereday.data.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import java.time.Instant
import com.bontecou.whereday.billing.AccessState

@Composable fun ExportScreen(graph: AppGraph) {
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
    var advanced by remember { mutableStateOf(false) }
    val picker by graph.exportPicker.state.collectAsStateWithLifecycle()
    val pending=picker.request?.takeIf {it.active}
    var pickerRequest by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(Unit) {graph.exportPicker.refresh()}
    var progress by remember { mutableStateOf<ExportCounts?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri=result.data?.data.takeIf {result.resultCode==android.app.Activity.RESULT_OK}
        val id=pickerRequest
        pickerRequest=null
        graph.exportPicker.result(id,uri,result.data?.flags ?: 0)
    }
    fun deliver(destination:String) {
        exportJob=scope.launch {
            busy=true;message=null;progress=null
            var prepared:PreparedExport?=null
            var keepPrepared=false
            try {
                prepared=graph.exports.prepare(ExportFileRequest(extraFormats+format,options.copy(kind=kind),filename,splitDays,outings=selection.outings,inference=settings.inferenceRules.takeIf {settings.inferredEnabled})) { counts -> scope.launch {progress=counts} }
                when(destination) {
                    "share" -> context.startActivity(android.content.Intent.createChooser(graph.exports.share(prepared),"Share history export"))
                    "folder" -> {
                        val folder=settings.exportFolder ?: error("Choose an export folder in Settings first.")
                        val saved=graph.exports.saveToFolder(prepared,android.net.Uri.parse(folder))
                        message="${saved.size} export files saved and verified."
                    }
                    else -> {
                        require(prepared.files.size==1) {"Use Share or the export folder for multiple files."}
                        val request=graph.exports.beginPicker(prepared)
                        keepPrepared=true;pickerRequest=request.id
                        graph.exportPicker.refresh()
                        saver.launch(graph.exports.pickerIntent(request.id))
                    }
                }
            } catch(e:Exception) {if(e is CancellationException) throw e;message=e.message ?: "Export could not be prepared."}
            finally {
                // Keep shared files for receivers; subsequent preparation cleans cache entries older than a day.
                if(destination!="share" && prepared!=null) withContext(NonCancellable) {
                    val retained=keepPrepared || runCatching {graph.exports.pickerState()?.let {it.id==prepared.id && it.active}==true}.getOrDefault(true)
                    if(!retained) graph.exports.discard(prepared)
                }
                busy=false;progress=null
            }
        }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) graph.historyImports.select(com.bontecou.whereday.platform.AndroidHistoryImportInput(context,uri))
    }
    LaunchedEffect(Unit) {graph.usageFunnel.paywall(com.bontecou.whereday.platform.UsagePaywall.export)}
    Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("export-screen").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your history, in your format", style = MaterialTheme.typography.headlineSmall)
        Text("$count route points stored on this phone.",Modifier.testTag("export-history-count"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Lifetime export access",style=MaterialTheme.typography.titleMedium)
                Text("One individual purchase through Google Play. Recording, imports and export previews are free.")
                purchase.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                if(purchase.access==AccessState.active) Text("Purchase verified")
                else purchase.product?.let { product ->
                    Button(enabled=!purchase.busy && !checkout && purchase.access!=AccessState.pending,onClick={
                        val activity=generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                            .filterIsInstance<android.app.Activity>().firstOrNull()
                        if(activity!=null) graph.purchases.purchase(activity,com.bontecou.whereday.platform.UsagePaywall.export)
                    }) { Text("Buy lifetime access · ${product.formattedPrice}") }
                }
                OutlinedButton(enabled=!purchase.busy && !checkout,onClick={ graph.purchases.restore(com.bontecou.whereday.platform.UsagePaywall.export) }) { Text("Restore purchases") }
                if(purchase.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        saved.error?.let { Text(it);TextButton(onClick={if(saved.ready) graph.exportSettings.retry() else graph.exportSettings.reset()}) {Text(if(saved.ready) "Retry saving export settings" else "Reset export settings")} }
        if(saved.saving) Text("Saving export settings…",style=MaterialTheme.typography.bodySmall)
        Choice("Format",format.name,ExportFormat.entries.map { it.name }) { graph.exportSettings.update(selection.copy(format=ExportFormat.valueOf(it))) }
        Choice("Data",if(selection.outings) "outings" else kind.name,DataKind.entries.map {it.name}+"outings") {
            graph.exportSettings.update(if(it=="outings") selection.copy(outings=true) else selection.copy(outings=false,options=options.copy(kind=DataKind.valueOf(it))))
        }
        Button(enabled=!busy && ready,onClick={ exportJob=scope.launch {
            busy=true
            try { preview=graph.exports.preview(format,options.copy(kind=kind),selection.outings,settings.inferenceRules.takeIf {settings.inferredEnabled}) }
            catch(e:Exception) { if(e is CancellationException) throw e; message="Preview could not be rendered." }
            finally { busy=false }
        } }) { Text("Preview export") }
        Text(if(selection.outings) "Preview includes up to 100 outings, 2,500 visits and 2,500 route points, and shows the first 100,000 characters. Complete exports include every selected outing. Preview is free."
            else "Preview includes up to 2,500 matching visits and 2,500 points, and shows the first 100,000 characters. Complete file exports include all matching history. Preview is free.",style=MaterialTheme.typography.bodySmall)
        if(selection.outings) Text("Outings are selected by their start date and time. Their complete routes are exported. Inferred outings follow your Settings choices.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!busy,onClick={ importer.launch(arrayOf("application/json","text/*","application/octet-stream")) }) { Text("Import history file") }
        Text("Import JSON, CSV or Markdown history. Photos and device-specific identifiers must be relinked on this phone.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled=!busy && ready && picker.loaded && !picker.busy && pending==null,onClick={deliver("share")}) {Text("Share export")}
            OutlinedButton(enabled=!busy && ready && picker.loaded && !picker.busy && pending==null && !splitDays && (extraFormats+format).size==1,onClick={deliver("save")}) {Text("Save export as…")}
        }
        message?.let {Text(it)}
        picker.message?.let {Text(it)}
        if(pending!=null) {
            Text("Pending save: ${pending.displayName}",style=MaterialTheme.typography.titleMedium)
            Text("This file was already prepared. Resume it or choose a destination; changing filters here does not change the pending file.",style=MaterialTheme.typography.bodySmall)
            if(pending.destination!=null) OutlinedButton(enabled=!busy && !picker.busy,onClick={graph.exportPicker.resume(pending.id)}) {Text("Resume saved export")}
            OutlinedButton(enabled=!busy && !picker.busy,onClick={scope.launch {
                try {pickerRequest=pending.id;saver.launch(graph.exports.pickerIntent(pending.id));graph.exportPicker.refresh()}
                catch(e:Exception) {if(e is CancellationException) throw e;message=e.message ?: "Saved export is unavailable. Cancel it and prepare another export."}
            }}) {Text("Choose export destination")}
            TextButton(onClick={graph.exportPicker.cancel(pending.id)}) {Text("Cancel pending export")}
        } else if(picker.message!=null && picker.request==null) TextButton(onClick={graph.exportPicker.discardInterrupted()}) {Text("Discard interrupted export")}
        OutlinedButton(enabled=!busy && ready && picker.loaded && !picker.busy && pending==null && settings.exportFolder!=null,onClick={deliver("folder")}) {Text("Save to export folder")}
        Text("File exports require verified lifetime access. Folder exports keep both files when a name already exists. Choose a folder in Settings.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "Hide filters and fields" else "Filters and fields")}
        if(advanced) {
            ExportFilters(options,{graph.exportSettings.update(selection.copy(options=it))},kind,outings=selection.outings)
            val presets=mapOf("Readable" to FilenamePlanner.readablePattern,"Compact" to FilenamePlanner.compactPattern,"Dated folders" to FilenamePlanner.datedFoldersPattern)
            Choice("Filename preset",presets.entries.firstOrNull {it.value==filename}?.key ?: "Custom",presets.keys.toList()) {graph.exportSettings.update(selection.copy(filename=presets.getValue(it)))}
            OutlinedTextField(value=filename,onValueChange={if(it.length<=4096) graph.exportSettings.update(selection.copy(filename=it))},label={Text("Filename template")},modifier=Modifier.fillMaxWidth())
            Text("Tokens include {date}, {format}, {type}, {year}, {month}, {dayNumber} and {datetime}. Use / for subfolders when saving to a folder.",style=MaterialTheme.typography.bodySmall)
            ExportToggle(if(selection.outings) "One file per outing" else "One file per day",splitDays) {graph.exportSettings.update(selection.copy(splitDays=it))}
            Text("Additional formats",style=MaterialTheme.typography.titleMedium)
            ExportFormat.entries.filter {it!=format}.forEach {extra -> ExportToggle(extra.name,extra in extraFormats) {enabled ->graph.exportSettings.update(selection.copy(additionalFormats=if(enabled) extraFormats+extra else extraFormats-extra))}}
        }
        if(busy || picker.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        progress?.let {Text("Prepared ${it.visits} visits and ${it.points} points",style=MaterialTheme.typography.bodySmall)}
        if(busy && exportJob?.isActive==true) TextButton(onClick={exportJob?.cancel()}) {Text("Cancel operation")}
        preview?.let { text -> HorizontalDivider(); SelectionContainer { Text(text,style=MaterialTheme.typography.bodySmall) } }
    }
}
