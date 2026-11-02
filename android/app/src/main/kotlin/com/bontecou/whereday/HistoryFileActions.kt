package com.bontecou.whereday

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
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
        WdButton(label,{if(enabled && picker.loaded && !picker.busy && !busy && pending==null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {sheet=true;notice=null;savedFiles=null}},Modifier.testTag("history-export-open"),
            enabled=enabled && picker.loaded && !picker.busy && !busy && pending==null,icon=Icons.Outlined.IosShare)
        if(busy) {ExportProgress();ExportBusyLine(stringResource(R.string.export_delivery_preparing));ExportLink(stringResource(R.string.export_delivery_cancel_preparation),{job?.cancel()},Modifier.testTag("history-export-cancel-preparation-inline"))}
        notice?.let {ExportCallout(exportDeliveryText(it,savedFiles),textModifier=Modifier.testTag("history-export-notice"),warning=it.isFailure)}
        picker.notice?.let {value ->
            val text=exportDeliveryText(value)
            ExportCallout(picker.request?.displayName?.let {stringResource(R.string.export_delivery_name_status,it,text)} ?: text,textModifier=Modifier.testTag("history-export-picker-notice"),warning=value.isFailure)
        }
        pending?.let {request ->
            val shape=RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.export_delivery_pending,request.displayName),Modifier.testTag("history-export-pending"),style=MaterialTheme.typography.titleSmall,color=wd.textPrimary)
                Text(stringResource(R.string.export_delivery_pending_help),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                if(request.destination!=null) WdButton(stringResource(R.string.export_delivery_resume),{pickerController.resume(request.id)},Modifier.testTag("history-export-resume"),emphasis=WdEmphasis.primary,enabled=!picker.busy)
                WdButton(stringResource(R.string.export_delivery_choose),{scope.launch {
                    try {requestId=request.id;saver.launch(files.pickerIntent(request.id));pickerController.refresh()}
                    catch(e:Exception) {if(e is CancellationException) throw e;notice=exportDeliveryNotice(e,ExportDeliveryNotice.unavailable)}
                }},enabled=!picker.busy)
                ExportLink(stringResource(R.string.export_delivery_cancel_pending),{pickerController.cancel(request.id)},Modifier.testTag("history-export-cancel-pending"),destructive=true)
            }
        }
        if(picker.request==null && picker.notice!=null) ExportLink(stringResource(R.string.export_delivery_discard),pickerController::discardInterrupted,Modifier.testTag("history-export-discard"))
    }
    if(sheet) WorkspaceModalBottomSheet(onDismissRequest={job?.cancel();sheet=false}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.export_redesign_eyebrow).uppercase(),style=WdType.eyebrow,color=wd.textMuted)
            Text(label,style=WdType.title2,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
            ExportBody(stringResource(R.string.export_delivery_access_help))
            WdButton(stringResource(R.string.export_delivery_share),{deliver("share")},Modifier.testTag("history-export-share"),emphasis=WdEmphasis.primary,enabled=!busy,icon=Icons.Outlined.IosShare)
            WdButton(stringResource(R.string.export_delivery_save_as),{deliver("save")},Modifier.testTag("history-export-save"),enabled=!busy,icon=Icons.Outlined.SaveAlt)
            WdButton(stringResource(R.string.export_delivery_folder),{deliver("folder")},Modifier.testTag("history-export-folder"),enabled=!busy && settings?.exportFolder!=null,icon=Icons.Outlined.Folder)
            if(settings?.exportFolder==null) Text(stringResource(R.string.export_delivery_folder_help),style=WdType.caption2,color=wd.textMuted)
            notice?.let {ExportCallout(exportDeliveryText(it,savedFiles),textModifier=Modifier.testTag("history-export-sheet-notice"),warning=true)}
            if(busy) {ExportProgress();ExportLink(stringResource(R.string.export_delivery_cancel_preparation),{job?.cancel()},Modifier.testTag("history-export-cancel-preparation"))}
            ExportLink(stringResource(R.string.export_delivery_done),{sheet=false},Modifier.align(Alignment.CenterHorizontally),enabled=!busy)
        }
    }
}
