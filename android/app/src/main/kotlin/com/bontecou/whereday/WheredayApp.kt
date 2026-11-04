package com.bontecou.whereday

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.outlined.Article
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
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
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
    onAccessDismiss:(Long)->Unit={},onAccessRequest:(Long)->Unit={},onAccessSettings:(Long)->Unit={},visitLink:VisitLinkRequest?=null,onVisitLinkHandled:(String)->Unit={},windowFocused:Boolean=true,
    trackingPaywall:Boolean=false,onTrackingPaywallDismiss:()->Unit={}) {
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
            else ReadyJournal(graph,journal,current,onRecord,onRequestLocation,onRequestNotifications,onOnboardingStart,launcherAction,onLauncherActionHandled,onStartRecording,onStopRecording,onboardingAuthorization,if(returnToSettings) 3 else 0,access,onAccessOptions,visitLink,onVisitLinkHandled,windowFocused,
                trackingPaywall,onTrackingPaywallDismiss)
        }
        if(!clear.blocked) WorkspaceRootDialogScope {PermissionAccessReview(accessReview?.takeIf {it.generation==clear.generation},onAccessDismiss,onAccessRequest,onAccessSettings)}
    } }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReadyJournal(graph:AppGraph,journal:JournalViewModel,current:AppSettings,onRecord:()->Unit,
    onRequestLocation:()->Unit,onRequestNotifications:()->Unit,onOnboardingStart:suspend()->Unit,
    launcherAction:LauncherAction?,onLauncherActionHandled:()->Unit,onStartRecording:()->Unit,onStopRecording:()->Unit,
    onboardingAuthorization:UsageAuthorization,initialTab:Int,access:RecordingAccessState?,onAccessOptions:(PermissionAccessKind)->Unit,visitLink:VisitLinkRequest?,onVisitLinkHandled:(String)->Unit,windowFocused:Boolean,
    trackingPaywall:Boolean,onTrackingPaywallDismiss:()->Unit) {
        val scope = rememberCoroutineScope()
        val purchase by graph.purchases.state.collectAsStateWithLifecycle()
        val checkout by graph.purchases.checkoutInFlight.collectAsStateWithLifecycle()
        val activity=LocalActivity.current
        fun buy(plan:com.bontecou.whereday.billing.StorePlan,entry:UsagePaywall) {if(activity!=null) graph.purchases.purchase(activity,plan,entry)}
        // Onboarding's "start now" waits behind the plans when tracking needs one, then starts once access is verified.
        var startAfterAccess by rememberSaveable {mutableStateOf(false)}
        LaunchedEffect(purchase.access,current.onboarded) {
            if(current.onboarded && purchase.access==com.bontecou.whereday.billing.AccessState.active) {
                if(!current.accessGateCleared) graph.preferences.update {it.copy(accessGateCleared=true)}
                if(startAfterAccess) {startAfterAccess=false;onStartRecording()}
            }
        }
        if (!current.onboarded) {
            JournalWorkspaceFrame(false,primary={Onboarding(onRequestLocation,onRequestNotifications,graph.usageAnalytics,onboardingAuthorization,access,onAccessOptions) {decision->
                val start=decision.start
                val permit=decision.permit
                val authorization=decision.authorization
                val deferred=start && purchase.trackingLocked
                if(deferred) startAfterAccess=true
                completeOnboarding(start && !deferred,onOnboardingStart,{graph.preferences.update {latest->latest.copy(onboarded=true)}}) {
                    graph.usageAnalytics.track(permit) {UsageEvent(UsageEventName.onboardingCompleted,step=UsageStep.ready,authorization=authorization,
                        tracking=if(start) UsageTrackingIntent.startImmediately else if(authorization in setOf(UsageAuthorization.foreground,UsageAuthorization.always)) UsageTrackingIntent.later else UsageTrackingIntent.unavailable)}
                }
            }},companion={})
            return
        }
        // New installs choose a plan once, right after onboarding, as on iOS. A plan that later lapses never brings this back:
        // history stays open and only starting tracking asks again.
        if(purchase.enforced && !current.accessGateCleared && purchase.access!=com.bontecou.whereday.billing.AccessState.active) {
            JournalWorkspaceFrame(false,primary={Paywall(purchase,checkout,{buy(it,UsagePaywall.onboarding)},{graph.purchases.restore(UsagePaywall.onboarding)},{},
                entry=PaywallEntry.gate)},companion={})
            return
        }
        if(trackingPaywall) PaywallDialog(purchase,checkout,{buy(it,UsagePaywall.settings)},{graph.purchases.restore(UsagePaywall.settings)},onTrackingPaywallDismiss)
        val places by graph.history.dao.observePlaces().collectAsStateWithLifecycle(emptyList())
        val count by graph.history.dao.pointCount().collectAsStateWithLifecycle(0L)
        val recording by graph.history.dao.observeRecording().collectAsStateWithLifecycle(null)
        val active by RecordingRuntime.active.collectAsStateWithLifecycle()
        // Today, Atlas, Places and Archive are the tabs; Settings and the map (Trace) are pushed pages above them.
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var page by rememberSaveable { mutableStateOf(if(initialTab==3) JournalPage.settings else null) }
        var logStopRequests by rememberSaveable { mutableIntStateOf(0) }
        // Beside Today the companion shows Trace; a focused visit or outing switches it to the full journal map.
        var companionMap by rememberSaveable { mutableStateOf(false) }
        // One journal-level owner survives changes to the primary/companion arrangement.
        // Keep the original snapshot so a background edit cannot replace its optimistic-lock baseline.
        var inspectedVisit by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<com.bontecou.whereday.domain.Visit?>(null)}
        var queuedVisitId by rememberSaveable {mutableStateOf<String?>(null)}
        val tabState=rememberSaveableStateHolder()
        LaunchedEffect(launcherAction) {
            when(launcherAction) {
                LauncherAction.timeline->{tab=0;page=null;onLauncherActionHandled()}
                LauncherAction.export->{tab=3;page=null;onLauncherActionHandled()}
                else->Unit
            }
        }
        val titles = listOf(R.string.shell_today,R.string.shell_atlas,R.string.shell_places,R.string.shell_archive).map {androidx.compose.ui.res.stringResource(it)}
        val icons = listOf(Icons.Outlined.Schedule, Icons.Outlined.GridView, Icons.Outlined.Place, Icons.Outlined.Inventory2)
        val selectedIcons = listOf(Icons.Filled.Schedule, Icons.Filled.GridView, Icons.Filled.Place, Icons.Filled.Inventory2)
        // Today and Archive keep the ids of the Timeline and Export destinations they replace.
        val navigationIds=listOf("timeline","atlas","places","export")
        var paired by remember {mutableStateOf(false)}
        val workspaceContext=LocalContext.current
        val workspaceMessages=remember {SnackbarHostState()}
        fun openVisit(visit:com.bontecou.whereday.domain.Visit) {
            if(paired) try {journal.showVisitOnMap(visit);companionMap=true} catch(_:Exception) {
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
                    onShowOnMap={stored->journal.showVisitOnMap(stored);inspectedVisit=null;if(!paired) page=JournalPage.journalMap else companionMap=true},inline=LocalInlineVisitEditor.current,inlineBackLabel=R.string.shell_today)
            }}
        }
        val mapPage=rememberWorkspaceContent {
            tabState.SaveableStateProvider("map") {
                JournalMapScreen(graph,journal,active,recording,current.metric,onRecord,current.showOutliers,{value->scope.launch {graph.preferences.update {it.copy(showOutliers=value)}}},access,onAccessOptions,onVisitOpened=::focusVisit,visitInspectionActive=inspectedVisit!=null)
            }
        }
        val todayPage=rememberWorkspaceContent {
            tabState.SaveableStateProvider(0) {
                TimelineScreen(graph,journal,current.metric,showOnMap={session->journal.showOutingOnMap(session);if(!paired) page=JournalPage.journalMap else companionMap=true},onVisitOpened=::focusVisit,visitInspectionActive=inspectedVisit!=null,
                    today=TodayChrome(active,recording,onRecord,access,onAccessOptions,onOpenMap={if(!paired) page=JournalPage.map else companionMap=false},onSettings={page=JournalPage.settings},
                        onImport={tab=3},onMoments={page=JournalPage.photos},onListening={page=JournalPage.listening},showLogStopFab=!LocalJournalRail.current,logStopRequests=logStopRequests))
            }
        }
        val tracePage:@Composable (String,(()->Unit)?,(()->Unit)?)->Unit={label,back,open->
            tabState.SaveableStateProvider("trace") {TraceScreen(graph,journal,current.metric,back,label,open)}
        }
        val folds=rememberJournalWindowFolds()
        // Tabletop: a separating horizontal hinge (half-open) puts the map above the fold and Today's controls below it.
        val tabletop=folds?.any {it.axis==WorkspaceFoldAxis.horizontal && (it.separating || it.halfOpened)}==true
        BackHandler(enabled=page!=null) {page=null}
        JournalWorkspaceFrame(allowCompanion=tab==0 && page==null,modifier=Modifier.background(MaterialTheme.colorScheme.background),folds=folds,inspectionActive=inspectedVisit!=null,inspectionInPrimary=!tabletop,companionFirst=tabletop,
            modal={LauncherRecordingConfirmation(launcherAction,active,onLauncherActionHandled) {start ->
            onLauncherActionHandled()
            tab=0;page=null
            if(start) onStartRecording() else onStopRecording()
        }
            VisitLinkHost(graph,visitLink,onVisitLinkHandled,windowFocused=windowFocused,showVisitOnMap={stored->journal.showVisitOnMap(stored);tab=0;if(!paired) page=JournalPage.journalMap else companionMap=true}) {tab=0;page=null}},
            inspection={inline->CompositionLocalProvider(LocalInlineVisitEditor provides inline) {Box(Modifier.fillMaxSize()) {inspector();if(inline) SnackbarHost(workspaceMessages,Modifier.align(androidx.compose.ui.Alignment.BottomCenter))}}},primary={hasCompanion->
            SideEffect {paired=hasCompanion}
            if(!(hasCompanion && inspectedVisit!=null && !tabletop)) {
                val wdTabs=titles.indices.map {WdTab(navigationIds[it],titles[it],icons[it],selectedIcons[it])}
                BoxWithConstraints(Modifier.fillMaxSize().background(wd.surface)) {
                    // Medium and expanded windows (and either side of an open foldable) use the rail; compact phones the bar.
                    // A short pane (below a tabletop hinge) keeps the bar: a rail would not fit its destinations.
                    val rail=(hasCompanion || maxWidth>=600.dp) && maxHeight>=480.dp
                    val wideRail=maxWidth>=1000.dp
                    val selectTab:(Int)->Unit={tab=it;page=null}
                    val content:@Composable ()->Unit={
                        when(page) {
                            JournalPage.settings->tabState.SaveableStateProvider("settings") {
                                Column(Modifier.fillMaxSize()) {
                                    SettingsScreen(graph,current,places.map {it.value},count,onBack={page=null})
                                }
                            }
                            JournalPage.map->tracePage(titles[0],{page=null},{page=JournalPage.journalMap})
                            JournalPage.journalMap->Column(Modifier.fillMaxSize()) {
                                WdBackChip(titles[0],{page=null},Modifier.padding(start=16.dp,top=8.dp,bottom=4.dp).testTag("map-back"))
                                Box(Modifier.weight(1f)) {mapPage()}
                            }
                            JournalPage.recap->MonthRecapScreen(graph,current.metric) {page=null}
                            JournalPage.listening->ListeningScreen(current,active,{page=null},onRecord,{page=JournalPage.settings},{page=JournalPage.settings},
                                {runCatching {workspaceContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${workspaceContext.packageName}")))}},
                                {value->graph.settingsActions.update {it.copy(networkGeocoding=value)}})
                            JournalPage.photos->tabState.SaveableStateProvider("photos") {PhotosScreen(graph,{page=null},titles[0])}
                            null->when(tab) {
                                0->todayPage()
                                else->tabState.SaveableStateProvider(tab) {when(tab) {
                                    1->AtlasScreen(graph,onMonthRecap=if(LocalJournalRail.current) null else {{page=JournalPage.recap}})
                                    2->SavedPlacesScreen(graph,places.map {it.value},back=null)
                                    3->ExportScreen(graph,onSettings={page=JournalPage.settings})
                                }}
                            }
                        }
                    }
                    if(rail) Row(Modifier.fillMaxSize()) {
                        WdNavigationRail(wdTabs,tab,selectTab,Modifier.semantics {testTagsAsResourceId=true},wide=wideRail,
                            header=if(tab==0) {{WdExtendedFab(androidx.compose.ui.res.stringResource(R.string.today_log_stop),Icons.Outlined.AddLocationAlt,{tab=0;page=null;logStopRequests++},
                                Modifier.testTag("rail-log-stop"),expanded=false)}} else if(tab==1) {{WdExtendedFab(androidx.compose.ui.res.stringResource(R.string.recap_title),Icons.AutoMirrored.Outlined.Article,{page=JournalPage.recap},
                                Modifier.testTag("rail-month-recap"),expanded=false,primary=true)}} else null,
                            footer={WdIconButton(Icons.Outlined.Settings,androidx.compose.ui.res.stringResource(R.string.shell_settings),{page=JournalPage.settings},Modifier.testTag("navigation-settings"),tonal=page==JournalPage.settings)})
                        Scaffold(Modifier.weight(1f),containerColor=wd.surface,contentWindowInsets=WindowInsets.statusBars,snackbarHost={SnackbarHost(workspaceMessages) {WdSnackbar(it)}}) {padding->
                            Box(Modifier.padding(padding).consumeWindowInsets(padding).navigationBarsPadding().fillMaxSize()) {CompositionLocalProvider(LocalJournalRail provides true) {content()}}
                        }
                    } else Scaffold(containerColor=wd.surface,contentWindowInsets=WindowInsets.statusBars,
                        snackbarHost={SnackbarHost(workspaceMessages) {WdSnackbar(it)}},
                        bottomBar={if(page==null) WdNavigationBar(wdTabs,tab,selectTab,Modifier.semantics {testTagsAsResourceId=true})}) {padding->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {content()}
                    }
                }
            }
        },companion={
            // List and detail: Today beside its map (tablet, unfolded inner display, or tabletop with the map above the hinge).
            if(!(inspectedVisit!=null && tabletop)) Box(Modifier.fillMaxSize().background(wd.surface).statusBarsPadding()) {
                Box(Modifier.fillMaxSize().padding(top=8.dp,end=16.dp,bottom=16.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).background(wd.containerLow)) {
                    if(companionMap) Column(Modifier.fillMaxSize()) {
                        WdBackChip(androidx.compose.ui.res.stringResource(R.string.trace_title),{companionMap=false},Modifier.padding(start=16.dp,top=8.dp,bottom=4.dp).testTag("companion-trace"))
                        Box(Modifier.weight(1f)) {mapPage()}
                    } else tracePage(titles[0],null) {companionMap=true}
                }
            }
        })

}

internal enum class JournalPage {settings,map,journalMap,photos,recap,listening}

/** True inside the navigation-rail layout, where Today's "Log a stop" lives on the rail instead of a floating button. */
internal val LocalJournalRail=staticCompositionLocalOf {false}
