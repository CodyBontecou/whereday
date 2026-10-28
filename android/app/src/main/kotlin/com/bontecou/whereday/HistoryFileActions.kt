package com.bontecou.whereday

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*

/** Shared delivery actions for specialized history files; picker results remain application-owned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HistoryFileActions(graph:AppGraph,label:String,enabled:Boolean,prepare:suspend ()->PreparedExport) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val picker by graph.exportPicker.state.collectAsStateWithLifecycle()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val pending=picker.request?.takeIf {it.active}
    var sheet by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var requestId by rememberSaveable {mutableStateOf<String?>(null)}
    var job by remember {mutableStateOf<Job?>(null)}
    LaunchedEffect(Unit) {graph.exportPicker.refresh()}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->
        val id=requestId;requestId=null
        graph.exportPicker.result(id,result.data?.data.takeIf {result.resultCode==Activity.RESULT_OK},result.data?.flags ?: 0)
    }
    fun deliver(destination:String) {
        job=scope.launch {
            busy=true;message=null
            var prepared:PreparedExport?=null;var retained=false
            try {
                prepared=prepare()
                when(destination) {
                    "share" -> {val intent=graph.exports.share(prepared);retained=true;sheet=false;context.startActivity(Intent.createChooser(intent,label))}
                    "folder" -> {
                        val folder=settings?.exportFolder ?: error("Choose an export folder in Settings first.")
                        graph.exports.saveToFolder(prepared,Uri.parse(folder));message="Export saved and verified.";sheet=false
                    }
                    else -> {
                        val request=graph.exports.beginPicker(prepared);retained=true;requestId=request.id
                        graph.exportPicker.refresh();sheet=false;saver.launch(graph.exports.pickerIntent(request.id))
                    }
                }
            } catch(e:Exception) {if(e is CancellationException) throw e;message=e.message ?: "Export could not be prepared."}
            finally {
                if(prepared!=null && !retained) withContext(NonCancellable) {runCatching {graph.exports.discard(prepared)}}
                busy=false
            }
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=enabled && picker.loaded && !picker.busy && !busy && pending==null,onClick={sheet=true;message=null}) {Text(label)}
        if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Preparing complete export…");TextButton(onClick={job?.cancel()}) {Text("Cancel preparation")}}
        message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        picker.message?.let {value ->Text(picker.request?.displayName?.let {"$it · $value"} ?: value,style=MaterialTheme.typography.bodySmall)}
        pending?.let {request ->
            Text("Pending save: ${request.displayName}",style=MaterialTheme.typography.titleSmall)
            if(request.destination!=null) OutlinedButton(enabled=!picker.busy,onClick={graph.exportPicker.resume(request.id)}) {Text("Resume saved export")}
            OutlinedButton(enabled=!picker.busy,onClick={scope.launch {
                try {requestId=request.id;saver.launch(graph.exports.pickerIntent(request.id));graph.exportPicker.refresh()}
                catch(e:Exception) {if(e is CancellationException) throw e;message=e.message ?: "Saved export is unavailable."}
            }}) {Text("Choose export destination")}
            TextButton(onClick={graph.exportPicker.cancel(request.id)}) {Text("Cancel pending export")}
        }
        if(picker.request==null && picker.message!=null) TextButton(onClick=graph.exportPicker::discardInterrupted) {Text("Discard interrupted export")}
    }
    if(sheet) ModalBottomSheet(onDismissRequest={job?.cancel();sheet=false}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(label,style=MaterialTheme.typography.headlineSmall)
            Text("Complete file export requires verified lifetime access. Choose where to share or save this file.")
            OutlinedButton(enabled=!busy,onClick={deliver("share")}) {Text("Share file")}
            OutlinedButton(enabled=!busy,onClick={deliver("save")}) {Text("Save file as…")}
            OutlinedButton(enabled=!busy && settings?.exportFolder!=null,onClick={deliver("folder")}) {Text("Save file to export folder")}
            if(settings?.exportFolder==null) Text("Choose an export folder in Settings to use folder delivery.",style=MaterialTheme.typography.bodySmall)
            message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={job?.cancel()}) {Text("Cancel preparation")}}
            TextButton(enabled=!busy,onClick={sheet=false}) {Text("Done")}
        }
    }
}
