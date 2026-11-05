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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph.from(this) }
    private var roadSession:RoadDisplayController.Session?=null
    private val journal by lazy { ViewModelProvider(this,object: ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T: ViewModel> create(modelClass: Class<T>): T = JournalViewModel(graph) as T
    })[JournalViewModel::class.java] }
    private val reviewWindowFocused=MutableStateFlow(false)
    private var locationAccess by mutableStateOf(UsageAuthorization.unknown)
    private var launcherAction by mutableStateOf<LauncherAction?>(null)
    private var visitLink by mutableStateOf<VisitLinkRequest?>(null)
    private var access by mutableStateOf<RecordingAccessState?>(null)
    /** The plan carousel opened because tracking was started without a subscription or Lifetime. */
    private var trackingPaywall by mutableStateOf(false)
    private var permissionReview by mutableStateOf<PermissionReview?>(null)
    private var reviewSerial=0L
    private var foregroundEpoch=0L
    private var pendingFocusStart:Job?=null
    private var permissionHandoff:Job?=null
    private var pendingLocation:PendingPermission?=null
    private var pendingNotifications:PendingPermission?=null
    private var locationDeclined=false
    private var preciseDeclined=false
    private var notificationsDeclined=false
    private var accessFailure:PermissionAccessFailure?=null
    private var locationAttempt:UsageFunnel.PermissionAttempt?=null
    private var notificationAttempt:UsageFunnel.PermissionAttempt?=null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val pending=pendingLocation;pendingLocation=null
        refreshAccess()
        val allowed=access?.location?.let {it!=LocationPrecision.none}==true
        graph.usageFunnel.permissionFinished(locationAttempt,if(allowed) locationAuthorization() else UsageAuthorization.denied)
        locationAttempt=null
        if(pending!=null && permissionResultCurrent(pending)) {
            locationDeclined=!allowed
            if(pending.precise) preciseDeclined=access?.location!=LocationPrecision.precise
            refreshAccess()
            if(pending.start && allowed) beginRecording(pending)
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        val pending=pendingNotifications;pendingNotifications=null
        refreshAccess()
        graph.usageFunnel.permissionFinished(notificationAttempt,if(access?.notifications==true) UsageAuthorization.authorized else UsageAuthorization.denied)
        notificationAttempt=null
        if(pending!=null && permissionResultCurrent(pending)) notificationsDeclined=access?.notifications!=true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home-screen widgets follow recording state while the journal is open.
        lifecycleScope.launch {RecordingRuntime.active.collect {WheredayWidgets.refresh(this@MainActivity)}}
        enableEdgeToEdge()
        refreshAccess()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {graph.journalClear.awaitReady()} catch(_:JournalClearPending) {return@repeatOnLifecycle}
                graph.reviewMilestones.appUsed()
                combine(graph.reviewMilestones.state,reviewWindowFocused,graph.preferences.settings.map {it.onboarded}.distinctUntilChanged(),graph.journalClear.state.map {!it.blocked}.distinctUntilChanged()) {milestone,focused,onboarded,ready->
                    milestone.eligible && focused && onboarded && ready
                }.distinctUntilChanged().collectLatest {eligible->
                    if(eligible) {kotlinx.coroutines.delay(1000);graph.reviewMilestones.maybeRequest(PlayReviewHost(this@MainActivity))}
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                graph.journalClear.state.map {!it.blocked}.distinctUntilChanged().collectLatest {ready->
                    if(!ready) {if(graph.journalClear.state.value.phase!=JournalClearPhase.checking) visitLink=null;pendingFocusStart?.cancel();permissionHandoff?.cancel();pendingLocation=null;pendingNotifications=null;permissionReview=null;locationAttempt=null;notificationAttempt=null;graph.automaticPhotos.background();return@collectLatest}
                    graph.purchases.resumed();graph.automationController.reload();graph.usageAnalytics.foreground();graph.webhookController.foreground();graph.automaticExportController.foreground();graph.automaticPhotos.foreground()
                }
            }
        }
        LauncherShortcuts.publish(this)
        if(savedInstanceState==null) graph.activityLog.record(AppEvent.appOpened)
        launcherAction=LauncherAction.restore(savedInstanceState?.getString("launcher-action"))
        visitLink=VisitLinkRequest.restore(savedInstanceState?.getStringArrayList("visit-link"))
        if(savedInstanceState==null) handleIntent(intent)
        lifecycleScope.launch {
            graph.journalClear.state.map {!it.blocked}.distinctUntilChanged().collectLatest {ready->
                if(!ready) return@collectLatest
                graph.reconcileLocationServices()
                try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.history.reconcileVisits() } }
                catch(canceled:kotlinx.coroutines.CancellationException) {throw canceled}
                catch(_:Exception) {
                    graph.activityLog.record(AppEvent.visitReconciliationFailed)
                    android.widget.Toast.makeText(this@MainActivity,getString(R.string.recording_reconciliation_failed),android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        setContent {
            val linkWindowFocused by reviewWindowFocused.collectAsStateWithLifecycle()
            WheredayApp(graph, journal, onRecord = { if (RecordingRuntime.active.value) stopRecording() else if(trackingLocked()) trackingPaywall=true else requestLocation(true) },
                onRequestLocation = { requestLocation(false,true) }, onRequestNotifications = { requestNotifications(true) },
                launcherAction=launcherAction,onLauncherActionHandled={launcherAction=null},
                onStartRecording={if(!RecordingRuntime.active.value) {if(trackingLocked()) trackingPaywall=true else requestLocation(true)}},onStopRecording={stopRecording()},onboardingAuthorization=locationAccess,
                trackingPaywall=trackingPaywall,onTrackingPaywallDismiss={trackingPaywall=false},
                onOnboardingStart={graph.recordingCommands.startVisible(this@MainActivity)},
                access=access,accessReview=permissionReview,onAccessOptions={openAccessOptions(it)},
                onAccessDismiss={id->if(permissionReview?.id==id) permissionReview=null},
                onAccessRequest={id->consumeReview(id) {ticket->
                    when(ticket.kind) {
                        PermissionAccessKind.notifications->launchNotifications(ticket.onboarding)
                        else->launchLocation(ticket.start,ticket.onboarding,ticket.kind==PermissionAccessKind.precise)
                    }
                }},onAccessSettings={id->consumeReview(id) {openPermissionSettings()}},
                visitLink=visitLink,onVisitLinkHandled={id->if(visitLink?.id==id) visitLink=null},windowFocused=linkWindowFocused)
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        reviewWindowFocused.value=hasFocus
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("launcher-action",launcherAction?.id)
        visitLink?.let {request->
            val clear=graph.journalClear.state.value
            val saved=if(request.generation==null && !clear.blocked) request.copy(generation=clear.generation.orEmpty()) else request
            outState.putStringArrayList("visit-link",saved.saved())
        }
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume();refreshAccess();(roadSession ?: graph.roadDisplay.createSession().also {roadSession=it}).foreground()
        // A preserved window can already have focus without another focus-change callback.
        window.decorView.post {
            if(!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                reviewWindowFocused.value=window.decorView.hasWindowFocus()
        }
    }
    override fun onStop() {roadSession?.background();foregroundEpoch++;permissionReview=null;permissionHandoff?.cancel();WheredayWidgets.refresh(this);super.onStop()}
    override fun onPause() {roadSession?.background();graph.automaticPhotos.background();super.onPause()}
    override fun onDestroy() {roadSession?.close();roadSession=null;super.onDestroy()}
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) {
        LauncherAction.fromAction(intent?.action)?.let {
            launcherAction=it
            // Recreation restores only pending UI; keep the original Intent identity.
            return
        }
        val uri=intent?.data
        if(uri?.scheme in setOf("isome","whereday") && uri?.host in setOf("check-in","log-visit","visit")) {
            launcherAction=null
            val clear=graph.journalClear.state.value
            visitLink=if(clear.blocked && clear.phase!=JournalClearPhase.checking) null
                else VisitLinkRequest.receive(uri.toString(),if(clear.phase==JournalClearPhase.checking) null else clear.generation.orEmpty())
            return
        }
        if (intent?.data?.scheme in setOf("isome","whereday") && intent?.data?.host == "stop") {
            stopRecording()
        }
    }
    private fun locationAuthorization():UsageAuthorization=when {
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED->UsageAuthorization.always
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED->UsageAuthorization.foreground
        else->UsageAuthorization.unknown // Android does not distinguish a first request from every prior denial here.
    }
    private fun refreshAccess() {
        locationAccess=locationAuthorization()
        val snapshot=recordingAccess(this)
        if(snapshot.location!=LocationPrecision.none) locationDeclined=false
        if(snapshot.location==LocationPrecision.precise) preciseDeclined=false
        if(snapshot.notifications) notificationsDeclined=false
        access=snapshot.copy(locationDeclined=locationDeclined,failure=accessFailure)
    }
    private fun interactive()=!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !graph.journalClear.state.value.blocked
    private fun permissionResultCurrent(ticket:PendingPermission)=interactive() && ticket.current(graph.journalClear.state.value.generation,foregroundEpoch,graph.journalClear.state.value.blocked)
    private fun pendingPermission()=pendingLocation!=null || pendingNotifications!=null || permissionHandoff?.isActive==true
    private fun requestLocation(start:Boolean,onboarding:Boolean=false) {
        if(!interactive() || pendingPermission()) return
        refreshAccess()
        if(access?.location!=LocationPrecision.none) {if(start) beginRecording();return}
        if(shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            permissionReview=PermissionReview(++reviewSerial,graph.journalClear.state.value.generation,foregroundEpoch,PermissionAccessKind.location,true,start,onboarding)
        } else launchLocation(start,onboarding,false)
    }
    private fun launchLocation(start:Boolean,onboarding:Boolean,precise:Boolean) {
        if(!interactive() || pendingPermission()) return
        refreshAccess();accessFailure=null
        if(access?.location==LocationPrecision.precise || (!precise && access?.location!=LocationPrecision.none)) {if(start) beginRecording();return}
        pendingLocation=PendingPermission(graph.journalClear.state.value.generation,foregroundEpoch,start,precise)
        locationAttempt=if(onboarding) graph.usageFunnel.permission(UsageRequestKind.foreground,locationAuthorization()) else null
        try {locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
        catch(_:Exception) {pendingLocation=null;locationAttempt=null;accessFailure=PermissionAccessFailure.request;refreshAccess()}
    }
    private fun openAccessOptions(kind:PermissionAccessKind) {
        if(!interactive() || pendingPermission()) return
        refreshAccess()
        val allowed=when(kind) {
            PermissionAccessKind.location->!locationDeclined || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            PermissionAccessKind.precise->!preciseDeclined || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            PermissionAccessKind.notifications->Build.VERSION.SDK_INT>=33 && (!notificationsDeclined || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        }
        permissionReview=PermissionReview(++reviewSerial,graph.journalClear.state.value.generation,foregroundEpoch,kind,allowed)
    }
    private suspend fun focusedFor(generation:String?,foreground:Long):Boolean=withTimeoutOrNull(15_000) {
        combine(lifecycle.currentStateFlow,reviewWindowFocused,graph.journalClear.state) {state,focused,clear->
            when {
                clear.blocked || clear.generation!=generation || foregroundEpoch!=foreground || isFinishing || isDestroyed->false
                state.isAtLeast(Lifecycle.State.RESUMED) && focused && hasWindowFocus()->true
                else->null
            }
        }.filterNotNull().first()
    }==true
    private fun consumeReview(id:Long,action:(PermissionReview)->Unit) {
        val ticket=permissionReview?.takeIf {it.id==id} ?: return
        permissionReview=null
        if(!interactive() || ticket.generation!=graph.journalClear.state.value.generation || ticket.foreground!=foregroundEpoch) return
        // Removing Compose state does not synchronously dismiss its Android Dialog window.
        // Launch a system Activity only once the explanatory window has relinquished focus.
        lifecycleScope.launch(start=CoroutineStart.LAZY) {
            try {
                if(focusedFor(ticket.generation,ticket.foreground) && ticket.id==reviewSerial && permissionReview==null) {
                    permissionHandoff=null
                    action(ticket)
                }
            } finally {if(permissionHandoff===currentCoroutineContext()[Job]) permissionHandoff=null}
        }.also {permissionHandoff=it;it.start()}
    }
    private fun openPermissionSettings() {
        accessFailure=null
        try {startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))}
        catch(_:Exception) {accessFailure=PermissionAccessFailure.settings;refreshAccess()}
    }
    /** Known to need a plan. While access is still being checked the start proceeds and [beginRecording] decides with the cached lease. */
    private fun trackingLocked()=graph.purchases.state.value.let {it.trackingLocked && it.access!=com.bontecou.whereday.billing.AccessState.checking}
    private fun stopRecording() {
        pendingFocusStart?.cancel();permissionHandoff?.cancel();permissionReview=null
        pendingLocation=pendingLocation?.copy(start=false)
        lifecycleScope.launch {
        try {graph.recordingCommands.stop()}
        catch(error:kotlinx.coroutines.CancellationException) {throw error}
        catch(_:Exception) {graph.activityLog.record(AppEvent.recordingStopFailed);android.widget.Toast.makeText(this@MainActivity,getString(R.string.recording_stop_failed),android.widget.Toast.LENGTH_LONG).show()}
    }}
    private fun beginRecording(ticket:PendingPermission=PendingPermission(graph.journalClear.state.value.generation,foregroundEpoch,start=true)) {
        if(graph.journalClear.state.value.blocked || pendingFocusStart?.isActive==true) return
        lifecycleScope.launch(start=CoroutineStart.LAZY) {
            try {
                if(!focusedFor(ticket.generation,ticket.foreground) || !permissionResultCurrent(ticket) || !hasWindowFocus()) return@launch
                // The same check as RecordingCommands: this direct start must not bypass the plan requirement.
                try {graph.purchases.requireAccess(com.bontecou.whereday.billing.Capability.recording)}
                catch(_:com.bontecou.whereday.billing.VerifiedAccessRequired) {trackingPaywall=true;return@launch}
                RecordingService.startForGeneration(this@MainActivity,ticket.generation);requestNotifications()
            } catch(error:CancellationException) {throw error}
            catch(_:Exception) {graph.activityLog.record(AppEvent.recordingStartFailed);android.widget.Toast.makeText(this@MainActivity,getString(R.string.recording_permission_start_failed),android.widget.Toast.LENGTH_LONG).show()}
            finally {if(pendingFocusStart===currentCoroutineContext()[Job]) pendingFocusStart=null}
        }.also {pendingFocusStart=it;it.start()}
    }
    private fun requestNotifications(onboarding:Boolean=false) {
        if(!interactive() || pendingPermission()) return
        refreshAccess()
        if(access?.notifications==true) return
        // A prior refusal is not an invitation to ask again on every recording start.
        if(!onboarding && (notificationsDeclined || (Build.VERSION.SDK_INT>=33 && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)))) return
        if(Build.VERSION.SDK_INT>=33) {
            if(shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                permissionReview=PermissionReview(++reviewSerial,graph.journalClear.state.value.generation,foregroundEpoch,PermissionAccessKind.notifications,true,onboarding=onboarding)
            } else launchNotifications(onboarding)
        } else openAccessOptions(PermissionAccessKind.notifications)
    }
    private fun launchNotifications(onboarding:Boolean) {
        if(!interactive() || pendingPermission() || Build.VERSION.SDK_INT<33) return
        refreshAccess();accessFailure=null
        if(access?.notifications==true) return
        pendingNotifications=PendingPermission(graph.journalClear.state.value.generation,foregroundEpoch)
        notificationAttempt=if(onboarding) graph.usageFunnel.permission(UsageRequestKind.notifications,UsageAuthorization.unknown) else null
        try {notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}
        catch(_:Exception) {pendingNotifications=null;notificationAttempt=null;accessFailure=PermissionAccessFailure.request;refreshAccess()}
    }
}
