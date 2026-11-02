package com.bontecou.whereday

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*

@Composable internal fun VisitMonitoringScreen(graph:AppGraph,back:()->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    var work by remember {mutableStateOf<Job?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf(false)}
    var access by remember {mutableStateOf(recordingAccess(context))}
    val mode by VisitMonitoringRuntime.state.collectAsStateWithLifecycle()
    val row by graph.history.dao.observeVisitMonitoring().collectAsStateWithLifecycle(initialValue=null)
    val clear by graph.journalClear.state.collectAsStateWithLifecycle()
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {access=recordingAccess(context)}
    fun foreground()=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && context.visitActivity()?.hasWindowFocus()==true && !clear.blocked
    fun run(action:suspend ()->Unit) {
        if(busy || !foreground()) return
        work=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            busy=true;error=false
            try {action()}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {error=true}
            finally {busy=false}
        }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_RESUME) access=recordingAccess(context)
            if(event==Lifecycle.Event.ON_STOP) work?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);work?.cancel()}
    }
    LaunchedEffect(clear.blocked) {if(clear.blocked) work?.cancel()}
    VisitMonitoringContent(mode,row?.requested==true,row?.interruption!=null,busy,clear.blocked,access,error,back,
        start={val activity=context.visitActivity();if(activity!=null) run {graph.visitMonitoringCommands.startVisible(activity,clear.generation)}},
        stop={val previous=work;previous?.cancel();scope.launch {previous?.join();run {graph.visitMonitoringCommands.stop()}}},
        location={if(foreground()) try {request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))} catch(_:Exception) {error=true}},
        notifications={if(foreground()) try {
            if(android.os.Build.VERSION.SDK_INT>=33) request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
        } catch(_:Exception) {error=true}},
        settings={if(foreground()) try {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))} catch(_:Exception) {error=true}})
}

@Composable internal fun VisitMonitoringContent(mode:VisitMonitoringMode,requested:Boolean,interrupted:Boolean,busy:Boolean,
    blocked:Boolean,access:RecordingAccessState,error:Boolean,back:()->Unit,start:()->Unit,stop:()->Unit,
    location:()->Unit,notifications:()->Unit,settings:()->Unit) {
    BackHandler(onBack=back)
    val running=mode==VisitMonitoringMode.balanced || mode==VisitMonitoringMode.route
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().testTag("visits-only-screen").verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            TextButton(onClick=back) {Text(stringResource(R.string.common_back))}
            Text(stringResource(R.string.visits_title),style=MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.visits_explanation))
            Text(stringResource(when {
                busy || mode==VisitMonitoringMode.preparing->R.string.visits_preparing
                requested && mode==VisitMonitoringMode.balanced->R.string.visits_active
                requested && mode==VisitMonitoringMode.route->R.string.visits_route
                interrupted || requested->R.string.visits_interrupted
                else->R.string.visits_off
            }),Modifier.testTag("visits-status"),style=MaterialTheme.typography.titleMedium)
            if(busy || mode==VisitMonitoringMode.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(!running && mode!=VisitMonitoringMode.preparing) Button(enabled=!busy && !blocked && access.location!=LocationPrecision.none,
                onClick=start,modifier=Modifier.testTag("visits-start")) {Text(stringResource(R.string.visits_start))}
            if(requested || running || mode==VisitMonitoringMode.preparing) OutlinedButton(enabled=!blocked,onClick=stop,
                modifier=Modifier.testTag("visits-stop")) {Text(stringResource(R.string.visits_stop))}
            Text(stringResource(R.string.visits_handoff))
            if(access.location!=LocationPrecision.precise) {
                if(access.location==LocationPrecision.approximate) Text(stringResource(R.string.visits_approximate))
                OutlinedButton(enabled=!busy && !blocked,onClick=location) {Text(stringResource(R.string.visits_location))}
            }
            if(!access.notifications) {
                Text(stringResource(if(android.os.Build.VERSION.SDK_INT>=33) R.string.visits_notification_warning else R.string.visits_notification_warning_legacy))
                OutlinedButton(enabled=!busy && !blocked,onClick=notifications) {Text(stringResource(R.string.visits_notifications))}
            }
            TextButton(enabled=!busy && !blocked,onClick=settings) {Text(stringResource(R.string.visits_settings))}
            Text(stringResource(R.string.visits_recovery))
            if(error) Text(stringResource(R.string.visits_error),color=MaterialTheme.colorScheme.error)
        }
    }
}

private tailrec fun Context.visitActivity():Activity?=when(this) {
    is Activity->this
    is ContextWrapper->if(baseContext===this) null else baseContext.visitActivity()
    else->null
}
