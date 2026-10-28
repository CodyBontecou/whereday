package com.bontecou.whereday

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph.from(this) }
    private val journal by lazy { ViewModelProvider(this,object: ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T: ViewModel> create(modelClass: Class<T>): T = JournalViewModel(graph) as T
    })[JournalViewModel::class.java] }
    private val reviewWindowFocused=MutableStateFlow(false)
    private var locationAccess by mutableStateOf(UsageAuthorization.unknown)
    private var launcherAction by mutableStateOf<LauncherAction?>(null)
    private var startAfterPermission = false
    private var locationAttempt:UsageFunnel.PermissionAttempt?=null
    private var notificationAttempt:UsageFunnel.PermissionAttempt?=null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationAccess=locationAuthorization()
        graph.usageFunnel.permissionFinished(locationAttempt,if(result.values.any {it}) locationAuthorization() else UsageAuthorization.denied)
        locationAttempt=null
        if (startAfterPermission && result.values.any { it }) beginRecording()
        startAfterPermission = false
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        graph.usageFunnel.permissionFinished(notificationAttempt,if(granted) UsageAuthorization.authorized else UsageAuthorization.denied)
        notificationAttempt=null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                graph.reviewMilestones.appUsed()
                combine(graph.reviewMilestones.state,reviewWindowFocused,graph.preferences.settings.map {it.onboarded}.distinctUntilChanged()) {milestone,focused,onboarded->
                    milestone.eligible && focused && onboarded
                }.distinctUntilChanged().collect {eligible->
                    if(eligible) {kotlinx.coroutines.delay(1000);graph.reviewMilestones.maybeRequest(PlayReviewHost(this@MainActivity))}
                }
            }
        }
        LauncherShortcuts.publish(this)
        if(savedInstanceState==null) graph.activityLog.record(AppEvent.appOpened)
        launcherAction=LauncherAction.restore(savedInstanceState?.getString("launcher-action"))
        if(savedInstanceState==null) handleIntent(intent)
        lifecycleScope.launch {
            if (!RecordingRuntime.active.value && !graph.recordingCommands.preparing) graph.history.markInterrupted("Recording was interrupted. Start again when you are ready.")
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.history.reconcileVisits() } }
            catch(canceled:kotlinx.coroutines.CancellationException) {throw canceled}
            catch(_:Exception) {
                graph.activityLog.record(AppEvent.visitReconciliationFailed)
                android.widget.Toast.makeText(this@MainActivity,"Visit history could not be updated. Try reopening the app.",android.widget.Toast.LENGTH_LONG).show()
            }
        }
        setContent {
            WheredayApp(graph, journal, onRecord = { if (RecordingRuntime.active.value) stopRecording() else requestLocation(true) },
                onRequestLocation = { requestLocation(false,true) }, onRequestNotifications = { requestNotifications(true) },
                launcherAction=launcherAction,onLauncherActionHandled={launcherAction=null},
                onStartRecording={if(!RecordingRuntime.active.value) requestLocation(true)},onStopRecording={stopRecording()},onboardingAuthorization=locationAccess,
                onOnboardingStart={graph.recordingCommands.startVisible(this@MainActivity)})
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        reviewWindowFocused.value=hasFocus
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("launcher-action",launcherAction?.id)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume();locationAccess=locationAuthorization(); graph.purchases.resumed();graph.automationController.reload();graph.usageAnalytics.foreground();graph.webhookController.foreground();graph.automaticExportController.foreground();graph.automaticPhotos.foreground() }
    override fun onPause() {graph.automaticPhotos.background();super.onPause()}
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) {
        LauncherAction.fromAction(intent?.action)?.let {
            launcherAction=it
            // Recreation restores only pending UI; keep the original Intent identity.
            return
        }
        if (intent?.data?.scheme == "isome" && intent.data?.host == "stop") {
            stopRecording()
        }
    }
    private fun locationAuthorization():UsageAuthorization=when {
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED->UsageAuthorization.always
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED->UsageAuthorization.foreground
        else->UsageAuthorization.unknown // Android does not distinguish a first request from every prior denial here.
    }
    private fun requestLocation(start: Boolean,onboarding:Boolean=false) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (start) beginRecording()
        } else {
            startAfterPermission = start
            locationAttempt=if(onboarding) graph.usageFunnel.permission(UsageRequestKind.foreground,locationAuthorization()) else null
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    private fun stopRecording() {lifecycleScope.launch {
        try {graph.recordingCommands.stop()}
        catch(error:kotlinx.coroutines.CancellationException) {throw error}
        catch(_:Exception) {graph.activityLog.record(AppEvent.recordingStopFailed);android.widget.Toast.makeText(this@MainActivity,"Recording could not be stopped. Check its status and try again.",android.widget.Toast.LENGTH_LONG).show()}
    }}
    private fun beginRecording() {
        try {RecordingService.start(this);requestNotifications()}
        catch(_:Exception) {graph.activityLog.record(AppEvent.recordingStartFailed);android.widget.Toast.makeText(this,"Recording could not start. Check location access and try again.",android.widget.Toast.LENGTH_LONG).show()}
    }
    private fun requestNotifications(onboarding:Boolean=false) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAttempt=if(onboarding) graph.usageFunnel.permission(UsageRequestKind.notifications,UsageAuthorization.unknown) else null
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
