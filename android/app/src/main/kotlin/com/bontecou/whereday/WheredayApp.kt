package com.bontecou.whereday

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.LocalActivity
import com.bontecou.whereday.data.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WheredayApp(graph: AppGraph, journal: JournalViewModel, onRecord: () -> Unit, onRequestLocation: () -> Unit, onRequestNotifications: () -> Unit,onOnboardingStart:suspend()->Unit,
    launcherAction:LauncherAction?=null,onLauncherActionHandled:()->Unit={},onStartRecording:()->Unit={},onStopRecording:()->Unit={},onboardingAuthorization:UsageAuthorization=UsageAuthorization.unknown,
    access:RecordingAccessState?=null,accessReview:PermissionReview?=null,onAccessOptions:(PermissionAccessKind)->Unit={},
    onAccessDismiss:(Long)->Unit={},onAccessRequest:(Long)->Unit={},onAccessSettings:(Long)->Unit={},visitLink:VisitLinkRequest?=null,onVisitLinkHandled:(String)->Unit={},windowFocused:Boolean=true) {
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = null)
    val clear by graph.journalClear.state.collectAsStateWithLifecycle()
    // Return to Settings for this clear; a fresh activity after completion starts normally.
    val entryGeneration=rememberSaveable {clear.generation ?: ""}
    val clearingAtEntry=rememberSaveable {clear.phase==JournalClearPhase.clearing}
    val returnToSettings=clear.phase==JournalClearPhase.complete && ((clear.generation ?: "")!=entryGeneration || clearingAtEntry)
    LaunchedEffect(clear.generation) {journal.journalCleared(clear.generation)}
    val current = settings
    val dark = when (current?.appearance) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view=LocalView.current
    val activity=LocalActivity.current
    SideEffect {activity?.window?.let {window ->
        WindowInsetsControllerCompat(window,view).apply {
            isAppearanceLightStatusBars=!dark
            isAppearanceLightNavigationBars=!dark
        }
    }}
    val presentations=remember {VisitLinkPresentations()}
    CompositionLocalProvider(LocalVisitLinkPresentations provides presentations) { WheredayTheme(dark) {
        if(clear.blocked) JournalWorkspaceFrame(false,primary={JournalClearBarrier(clear,graph.journalClear::clear)},companion={})
        JournalReadyState(clear) {
            if(current==null) JournalWorkspaceFrame(false,primary={Box(Modifier.fillMaxSize().background(wd.surface),contentAlignment=androidx.compose.ui.Alignment.Center) {WdLoading()}},companion={})
            else ReadyJournal(graph,journal,current,onRecord,onRequestLocation,onRequestNotifications,onOnboardingStart,launcherAction,onLauncherActionHandled,onStartRecording,onStopRecording,onboardingAuthorization,if(returnToSettings) 3 else 0,access,onAccessOptions,visitLink,onVisitLinkHandled,windowFocused)
        }
        if(!clear.blocked) WorkspaceRootDialogScope {PermissionAccessReview(accessReview?.takeIf {it.generation==clear.generation},onAccessDismiss,onAccessRequest,onAccessSettings)}
    } }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReadyJournal(graph:AppGraph,journal:JournalViewModel,current:AppSettings,onRecord:()->Unit,
    onRequestLocation:()->Unit,onRequestNotifications:()->Unit,onOnboardingStart:suspend()->Unit,
    launcherAction:LauncherAction?,onLauncherActionHandled:()->Unit,onStartRecording:()->Unit,onStopRecording:()->Unit,
    onboardingAuthorization:UsageAuthorization,initialTab:Int,access:RecordingAccessState?,onAccessOptions:(PermissionAccessKind)->Unit,visitLink:VisitLinkRequest?,onVisitLinkHandled:(String)->Unit,windowFocused:Boolean) {
        val scope = rememberCoroutineScope()
        if (!current.onboarded) {
            JournalWorkspaceFrame(false,primary={Onboarding(onRequestLocation,onRequestNotifications,graph.usageAnalytics,onboardingAuthorization,access,onAccessOptions) {decision->
                val start=decision.start
                val permit=decision.permit
                val authorization=decision.authorization
                completeOnboarding(start,onOnboardingStart,{graph.preferences.update {latest->latest.copy(onboarded=true)}}) {
                    graph.usageAnalytics.track(permit) {UsageEvent(UsageEventName.onboardingCompleted,step=UsageStep.ready,authorization=authorization,
                        tracking=if(start) UsageTrackingIntent.startImmediately else if(authorization in setOf(UsageAuthorization.foreground,UsageAuthorization.always)) UsageTrackingIntent.later else UsageTrackingIntent.unavailable)}
                }
            }},companion={})
            return
        }
        val places by graph.history.dao.observePlaces().collectAsStateWithLifecycle(emptyList())
        val count by graph.history.dao.pointCount().collectAsStateWithLifecycle(0L)
        val recording by graph.history.dao.observeRecording().collectAsStateWithLifecycle(null)
        val active by RecordingRuntime.active.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(initialTab) }
        // One journal-level owner survives changes to the primary/companion arrangement.
        // Keep the original snapshot so a background edit cannot replace its optimistic-lock baseline.
        var inspectedVisit by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<com.bontecou.whereday.domain.Visit?>(null)}
        var queuedVisitId by rememberSaveable {mutableStateOf<String?>(null)}
        val tabState=rememberSaveableStateHolder()
        LaunchedEffect(launcherAction) {
            when(launcherAction) {
                LauncherAction.timeline->{tab=1;onLauncherActionHandled()}
                LauncherAction.export->{tab=2;onLauncherActionHandled()}
                else->Unit
            }
        }
        val titles = listOf(R.string.shell_map,R.string.shell_timeline,R.string.shell_export,R.string.shell_settings).map {androidx.compose.ui.res.stringResource(it)}
        val icons = listOf(Icons.Outlined.Map, Icons.Outlined.Schedule, Icons.Outlined.Inventory2, Icons.Outlined.Settings)
        val selectedIcons = listOf(Icons.Filled.Map, Icons.Filled.Schedule, Icons.Filled.Inventory2, Icons.Filled.Settings)
        val navigationIds=listOf("map","timeline","export","settings")
        var paired by remember {mutableStateOf(false)}
        val workspaceContext=LocalContext.current
        val workspaceMessages=remember {SnackbarHostState()}
        fun openVisit(visit:com.bontecou.whereday.domain.Visit) {
            if(paired) try {journal.showVisitOnMap(visit)} catch(_:Exception) {
                scope.launch {workspaceMessages.showSnackbar(workspaceContext.getString(R.string.visit_error_map))}
            }
            inspectedVisit=visit
        }
        fun focusVisit(visit:com.bontecou.whereday.domain.Visit) {
            val existing=inspectedVisit
            if(existing==null) openVisit(visit)
            else if(existing.id!=visit.id) {
                queuedVisitId=visit.id
                scope.launch {workspaceMessages.showSnackbar(workspaceContext.getString(R.string.workspace_visit_wait))}
            }
        }
        if(queuedVisitId!=null) BlockVisitLinks()
        LaunchedEffect(inspectedVisit?.id,queuedVisitId) {
            val id=queuedVisitId
            if(inspectedVisit==null && id!=null) {
                val saved=try {graph.history.dao.visit(id)?.value} catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled} catch(_:Exception) {null}
                if(inspectedVisit==null && queuedVisitId==id) {
                    if(saved!=null) openVisit(saved) else scope.launch {workspaceMessages.showSnackbar(workspaceContext.getString(R.string.visit_link_missing))}
                    queuedVisitId=null
                }
            }
        }
        val inspector=rememberWorkspaceContent {
            inspectedVisit?.let {visit->key(visit.id) {
                VisitEditor(graph,visit,{inspectedVisit=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,visit,reuse,remember,overlap)},{graph.history.deleteVisit(visit)},
                    onShowOnMap={stored->journal.showVisitOnMap(stored);inspectedVisit=null;if(!paired) tab=0},inline=LocalInlineVisitEditor.current,inlineBackLabel=if(tab==0) R.string.shell_timeline else R.string.common_back)
            }}
        }
        val mapPage=rememberWorkspaceContent {
            tabState.SaveableStateProvider(0) {
                JournalMapScreen(graph,journal,active,recording,current.metric,onRecord,current.showOutliers,{value->scope.launch {graph.preferences.update {it.copy(showOutliers=value)}}},access,onAccessOptions,onVisitOpened=::focusVisit,visitInspectionActive=inspectedVisit!=null)
            }
        }
        val timelinePage=rememberWorkspaceContent {
            tabState.SaveableStateProvider(1) {
                TimelineScreen(graph,journal,current.metric,showOnMap={session->journal.showOutingOnMap(session);if(!paired) tab=0},onVisitOpened=::focusVisit,visitInspectionActive=inspectedVisit!=null)
            }
        }
        JournalWorkspaceFrame(allowCompanion=tab in 0..1,modifier=Modifier.background(MaterialTheme.colorScheme.background),inspectionActive=inspectedVisit!=null,inspectionInPrimary=tab==1,
            modal={LauncherRecordingConfirmation(launcherAction,active,onLauncherActionHandled) {start ->
            onLauncherActionHandled()
            tab=0
            if(start) onStartRecording() else onStopRecording()
        }
            VisitLinkHost(graph,visitLink,onVisitLinkHandled,windowFocused=windowFocused,showVisitOnMap={stored->journal.showVisitOnMap(stored);tab=0}) {tab=0}},
            inspection={inline->CompositionLocalProvider(LocalInlineVisitEditor provides inline) {Box(Modifier.fillMaxSize()) {inspector();if(inline && tab==1) SnackbarHost(workspaceMessages,Modifier.align(androidx.compose.ui.Alignment.BottomCenter))}}},primary={hasCompanion->
            SideEffect {paired=hasCompanion}
            if(!(hasCompanion && tab==1 && inspectedVisit!=null)) {
                val wdTabs=titles.indices.map {WdTab(navigationIds[it],titles[it],icons[it],selectedIcons[it])}
                // The floating bar sits below each page so lists end above it, as on iOS.
                Scaffold(containerColor=wd.surface,contentWindowInsets=WindowInsets.statusBars,
                    snackbarHost={SnackbarHost(workspaceMessages) {WdSnackbar(it)}},
                    bottomBar={WdFloatingTabBar(wdTabs,tab,{tab=it},Modifier.semantics {testTagsAsResourceId=true})}) {padding->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                        when(tab) {
                            0->mapPage()
                            1->timelinePage()
                            else->tabState.SaveableStateProvider(tab) {when(tab) {
                                2->ExportScreen(graph)
                                3->SettingsScreen(graph,current,places.map {it.value},count)
                            }}
                        }
                    }
                }
            }
        },companion={
            if(!(tab==0 && inspectedVisit!=null)) Box(Modifier.fillMaxSize().background(wd.surface).statusBarsPadding()) {if(tab==0) timelinePage() else mapPage()}
        })

}
