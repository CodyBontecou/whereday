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
            else ReadyJournal(graph,journal,current,onRecord,onRequestLocation,onRequestNotifications,onOnboardingStart,launcherAction,onLauncherActionHandled,onStartRecording,onStopRecording,onboardingAuthorization,if(returnToSettings) MainTabs.afterClear else MainTabs.Destination(MainTabs.launch),access,onAccessOptions,visitLink,onVisitLinkHandled,windowFocused,
                trackingPaywall,onTrackingPaywallDismiss)
        }
        if(!clear.blocked) WorkspaceRootDialogScope {PermissionAccessReview(accessReview?.takeIf {it.generation==clear.generation},onAccessDismiss,onAccessRequest,onAccessSettings)}
    } }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReadyJournal(graph:AppGraph,journal:JournalViewModel,current:AppSettings,onRecord:()->Unit,
    onRequestLocation:()->Unit,onRequestNotifications:()->Unit,onOnboardingStart:suspend()->Unit,
    launcherAction:LauncherAction?,onLauncherActionHandled:()->Unit,onStartRecording:()->Unit,onStopRecording:()->Unit,
    onboardingAuthorization:UsageAuthorization,initialDestination:MainTabs.Destination,access:RecordingAccessState?,onAccessOptions:(PermissionAccessKind)->Unit,visitLink:VisitLinkRequest?,onVisitLinkHandled:(String)->Unit,windowFocused:Boolean,
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
        // Starts on the bar's form of the destination; the rail turns Settings-behind-Archive into its own tab.
        val start=MainTabs.resolve(initialDestination,usesRail=false)
        var tab by rememberSaveable { mutableIntStateOf(MainTabs.index(start.tab)) }
        // On the phone tab bar Settings opens behind Archive (iOS's gear on the Archive page).
        var archiveSettings by rememberSaveable {mutableStateOf(start.settings)}
        var usesRail by remember {mutableStateOf(false)}
        // The day's timeline opens over the map (from the day sheet or the launcher), as Today's timeline did on iOS 2.0.
        var timelineOpen by rememberSaveable {mutableStateOf(false)}
        val onMapTab=MainTabs.at(tab)==MainTab.map
        val timelineShown=onMapTab && timelineOpen
        val mapShown=onMapTab && !timelineOpen
        fun go(destination:MainTabs.Destination) {
            val resolved=MainTabs.resolve(destination,usesRail)
            tab=MainTabs.index(resolved.tab);timelineOpen=resolved.timeline;archiveSettings=resolved.settings
        }
        // Atlas opens the Month recap as a page above the tabs.
        var recapOpen by rememberSaveable {mutableStateOf(false)}
        // One journal-level owner survives changes to the primary/companion arrangement.
        // Keep the original snapshot so a background edit cannot replace its optimistic-lock baseline.
        var inspectedVisit by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<com.bontecou.whereday.domain.Visit?>(null)}
        var queuedVisitId by rememberSaveable {mutableStateOf<String?>(null)}
        val tabState=rememberSaveableStateHolder()
        // The map's day, visit and replay sheets take the floating tab bar's place while open.
        val tabBarHidden=remember {mutableStateOf(false)}
        LaunchedEffect(launcherAction) {
            launcherAction?.let(MainTabs::destination)?.let {go(it);onLauncherActionHandled()}
        }
        val wdTabs=MainTabs.rail.map {WdTab(it.id,androidx.compose.ui.res.stringResource(it.title),it.icon,it.selectedIcon)}
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
                    onShowOnMap={stored->journal.showVisitOnMap(stored);inspectedVisit=null;if(!paired) go(MainTabs.visitLink)},inline=LocalInlineVisitEditor.current,inlineBackLabel=if(mapShown) R.string.shell_timeline else R.string.common_back)
            }}
        }
        val mapPage=rememberWorkspaceContent {
            tabState.SaveableStateProvider(0) {
                JournalMapScreen(graph,journal,active,recording,current.metric,onRecord,current.showOutliers,{value->scope.launch {graph.preferences.update {it.copy(showOutliers=value)}}},access,onAccessOptions,onVisitOpened=::focusVisit,visitInspectionActive=inspectedVisit!=null,
                    onOpenTimeline={timelineOpen=true})
            }
        }
        val timelinePage=rememberWorkspaceContent {
            tabState.SaveableStateProvider(1) {
                TimelineScreen(graph,journal,current.metric,showOnMap={session->journal.showOutingOnMap(session);if(!paired) timelineOpen=false},onVisitOpened=::focusVisit,visitInspectionActive=inspectedVisit!=null,
                    today=TodayChrome(active,recording,onRecord,access,onAccessOptions,onOpenMap={timelineOpen=false},onSettings={go(MainTabs.Destination(MainTab.settings))},
                        onImport={go(MainTabs.Destination(MainTab.archive))},onMoments=null,logStopInHeader=true),
                    onClose=if(timelineShown) ({timelineOpen=false}) else null)
            }
        }
        // Settings moves between Archive's gear and the rail without changing its saved-state owner.
        val settingsPage=rememberWorkspaceContent {
            tabState.SaveableStateProvider("settings-page") {
                SettingsScreen(graph,current,places.map {it.value},count,
                    onClose=if(LocalJournalRail.current) null else ({go(MainTabs.Destination(MainTab.archive))}))
            }
        }
        JournalWorkspaceFrame(allowCompanion=onMapTab,modifier=Modifier.background(MaterialTheme.colorScheme.background),inspectionActive=inspectedVisit!=null,inspectionInPrimary=timelineShown,
            modal={LauncherRecordingConfirmation(launcherAction,active,onLauncherActionHandled) {start ->
            onLauncherActionHandled()
            go(MainTabs.visitLink)
            if(start) onStartRecording() else onStopRecording()
        }
            VisitLinkHost(graph,visitLink,onVisitLinkHandled,windowFocused=windowFocused,showVisitOnMap={stored->journal.showVisitOnMap(stored);go(MainTabs.visitLink)}) {go(MainTabs.visitLink)}},
            inspection={inline->CompositionLocalProvider(LocalInlineVisitEditor provides inline) {Box(Modifier.fillMaxSize()) {inspector();if(inline && timelineShown) SnackbarHost(workspaceMessages,Modifier.align(androidx.compose.ui.Alignment.BottomCenter))}}},primary={hasCompanion->
            SideEffect {paired=hasCompanion}
            if(!(hasCompanion && timelineShown && inspectedVisit!=null)) BoxWithConstraints(Modifier.fillMaxSize()) {
                val railed=maxWidth>=MainTabs.railMinWidthDp.dp
                SideEffect {usesRail=railed}
                LaunchedEffect(railed) {
                    val resolved=MainTabs.resolve(MainTabs.Destination(MainTabs.at(tab),settings=archiveSettings),railed)
                    tab=MainTabs.index(resolved.tab);archiveSettings=resolved.settings
                }
                val selectTab:(Int)->Unit={index->tab=index;archiveSettings=false;recapOpen=false;if(MainTabs.at(index)==MainTab.map) timelineOpen=false}
                BackHandler(enabled=recapOpen) {recapOpen=false}
                Row(Modifier.fillMaxSize()) {
                    // On the rail, Atlas's Month recap is the rail's FAB, as in the Material 3 layout.
                    if(railed) WdNavigationRail(wdTabs,tab,selectTab,Modifier.semantics {testTagsAsResourceId=true},ruleBefore=MainTabs.bar.size,
                        header=if(MainTabs.at(tab)==MainTab.atlas) {{WdExtendedFab(androidx.compose.ui.res.stringResource(R.string.recap_title),Icons.AutoMirrored.Outlined.Article,{recapOpen=true},
                            Modifier.testTag("rail-month-recap"),expanded=false,primary=true)}} else null)
                    // The floating bar sits below each page so lists end above it, as on iOS.
                    Scaffold(Modifier.weight(1f),containerColor=wd.surface,contentWindowInsets=WindowInsets.statusBars,
                        snackbarHost={SnackbarHost(workspaceMessages) {WdSnackbar(it)}},
                        bottomBar={if(!railed && !(mapShown && tabBarHidden.value)) WdFloatingTabBar(wdTabs.take(MainTabs.bar.size),tab.coerceAtMost(MainTabs.bar.size-1),selectTab,Modifier.semantics {testTagsAsResourceId=true})}) {padding->
                        // The map runs full-bleed under the status bar and the floating tab bar; its chrome keeps clear of both.
                        if(mapShown) CompositionLocalProvider(LocalMapChromePadding provides padding,LocalTabBarHidden provides tabBarHidden) {Box(Modifier.fillMaxSize()) {mapPage()}}
                        else Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {CompositionLocalProvider(LocalJournalRail provides railed) {
                            if(recapOpen) MonthRecapScreen(graph,current.metric) {recapOpen=false}
                            else if(MainTabs.at(tab)==MainTab.settings || MainTabs.at(tab)==MainTab.archive && archiveSettings) settingsPage()
                            else when(MainTabs.at(tab)) {
                                MainTab.map->timelinePage()
                                else->tabState.SaveableStateProvider(tab) {when(MainTabs.at(tab)) {
                                    MainTab.atlas->AtlasScreen(graph,onMonthRecap=if(railed) null else {{recapOpen=true}})
                                    MainTab.places->SavedPlacesScreen(graph,places.map {it.value},null)
                                    MainTab.archive->ExportScreen(graph,onSettings=if(railed) null else ({archiveSettings=true}))
                                    MainTab.settings,MainTab.map->Unit
                                }}
                            }
                        }}
                    }
                }
            }
        },companion={
            if(!(mapShown && inspectedVisit!=null)) Box(Modifier.fillMaxSize().background(wd.surface).statusBarsPadding()) {if(mapShown) timelinePage() else mapPage()}
        })

}

/** True inside the navigation-rail layout, where Today's "Log a stop" lives on the rail instead of a floating button. */
internal val LocalJournalRail=staticCompositionLocalOf {false}
