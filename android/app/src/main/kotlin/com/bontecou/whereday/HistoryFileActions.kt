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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*

/** Shared delivery actions for specialized history files; picker results remain application-owned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HistoryFileActions(graph:AppGraph,label:String,enabled:Boolean,files:ExportFiles=graph.exports,pickerController:ExportPickerController=graph.exportPicker,prepare:suspend ()->PreparedExport) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val picker by pickerController.state.collectAsStateWithLifecycle()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val pending=picker.request?.takeIf {it.active}
    var sheet by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var notice by remember {mutableStateOf<ExportDeliveryNotice?>(null)}
    var savedFiles by remember {mutableStateOf<Int?>(null)}
    var requestId by rememberSaveable {mutableStateOf<String?>(null)}
    var job by remember {mutableStateOf<Job?>(null)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {job?.cancel();sheet=false}}
        lifecycle.addObserver(observer)
        onDispose {job?.cancel();sheet=false;lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(pickerController) {pickerController.refresh()}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->
        val id=requestId;requestId=null
        pickerController.result(id,result.data?.data.takeIf {result.resultCode==Activity.RESULT_OK},result.data?.flags ?: 0)
    }
    fun deliver(destination:String) {
        if(!sheet || busy || !enabled || !picker.loaded || picker.busy || pending!=null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        busy=true;notice=null;savedFiles=null
        job=scope.launch {
            var prepared:PreparedExport?=null;var retained=false
            try {
                prepared=prepare()
                when(destination) {
                    "share" -> {val intent=files.share(prepared);retained=true;sheet=false;context.startActivity(Intent.createChooser(intent,label))}
                    "folder" -> {
                        val folder=settings?.exportFolder ?: throw ExportDeliveryException(ExportDeliveryNotice.folderRequired)
                        savedFiles=files.saveToFolder(prepared,Uri.parse(folder)).size;notice=ExportDeliveryNotice.saved;sheet=false
                    }
                    else -> {
                        val request=files.beginPicker(prepared);retained=true;requestId=request.id
                        pickerController.refresh();sheet=false;saver.launch(files.pickerIntent(request.id))
                    }
                }
            } catch(e:Exception) {if(e is CancellationException) throw e;notice=exportDeliveryNotice(e)}
            finally {
                if(prepared!=null && !retained) withContext(NonCancellable) {runCatching {files.discard(prepared)}}
                busy=false
            }
        }
    }
    Column(Modifier.semantics {testTagsAsResourceId=true},verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=enabled && picker.loaded && !picker.busy && !busy && pending==null,onClick={if(enabled && picker.loaded && !picker.busy && !busy && pending==null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {sheet=true;notice=null;savedFiles=null}},modifier=Modifier.testTag("history-export-open")) {Text(label)}
        if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.export_delivery_preparing));TextButton(onClick={job?.cancel()},modifier=Modifier.testTag("history-export-cancel-preparation-inline")) {Text(stringResource(R.string.export_delivery_cancel_preparation))}}
        notice?.let {Text(exportDeliveryText(it,savedFiles),Modifier.testTag("history-export-notice"),style=MaterialTheme.typography.bodySmall)}
        picker.notice?.let {value ->
            val text=exportDeliveryText(value)
            Text(picker.request?.displayName?.let {stringResource(R.string.export_delivery_name_status,it,text)} ?: text,Modifier.testTag("history-export-picker-notice"),style=MaterialTheme.typography.bodySmall)
        }
        pending?.let {request ->
            Text(stringResource(R.string.export_delivery_pending,request.displayName),Modifier.testTag("history-export-pending"),style=MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.export_delivery_pending_help),style=MaterialTheme.typography.bodySmall)
            if(request.destination!=null) OutlinedButton(enabled=!picker.busy,onClick={pickerController.resume(request.id)},modifier=Modifier.testTag("history-export-resume")) {Text(stringResource(R.string.export_delivery_resume))}
            OutlinedButton(enabled=!picker.busy,onClick={scope.launch {
                try {requestId=request.id;saver.launch(files.pickerIntent(request.id));pickerController.refresh()}
                catch(e:Exception) {if(e is CancellationException) throw e;notice=exportDeliveryNotice(e,ExportDeliveryNotice.unavailable)}
            }}) {Text(stringResource(R.string.export_delivery_choose))}
            TextButton(onClick={pickerController.cancel(request.id)},modifier=Modifier.testTag("history-export-cancel-pending")) {Text(stringResource(R.string.export_delivery_cancel_pending))}
        }
        if(picker.request==null && picker.notice!=null) TextButton(onClick=pickerController::discardInterrupted,modifier=Modifier.testTag("history-export-discard")) {Text(stringResource(R.string.export_delivery_discard))}
    }
    if(sheet) WorkspaceModalBottomSheet(onDismissRequest={job?.cancel();sheet=false}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(label,style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.export_delivery_access_help))
            OutlinedButton(enabled=!busy,onClick={deliver("share")},modifier=Modifier.testTag("history-export-share")) {Text(stringResource(R.string.export_delivery_share))}
            OutlinedButton(enabled=!busy,onClick={deliver("save")},modifier=Modifier.testTag("history-export-save")) {Text(stringResource(R.string.export_delivery_save_as))}
            OutlinedButton(enabled=!busy && settings?.exportFolder!=null,onClick={deliver("folder")},modifier=Modifier.testTag("history-export-folder")) {Text(stringResource(R.string.export_delivery_folder))}
            if(settings?.exportFolder==null) Text(stringResource(R.string.export_delivery_folder_help),style=MaterialTheme.typography.bodySmall)
            notice?.let {Text(exportDeliveryText(it,savedFiles),Modifier.testTag("history-export-sheet-notice"),color=MaterialTheme.colorScheme.error)}
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={job?.cancel()},modifier=Modifier.testTag("history-export-cancel-preparation")) {Text(stringResource(R.string.export_delivery_cancel_preparation))}}
            TextButton(enabled=!busy,onClick={sheet=false}) {Text(stringResource(R.string.export_delivery_done))}
        }
    }
}
