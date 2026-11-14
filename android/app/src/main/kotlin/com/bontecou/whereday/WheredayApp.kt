package com.bontecou.whereday

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
    onAccessDismiss:(Long)->Unit={},onAccessRequest:(Long)->Unit={},onAccessSettings:(Long)->Unit={},visitLink:VisitLinkRequest?=null,onVisitLinkHandled:(String)->Unit={},windowFocused:Boolean=true,initialJournal:Int?=null) {
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
            if(current==null) JournalWorkspaceFrame(false,primary={Surface(Modifier.fillMaxSize()) {Box(Modifier.padding(32.dp)) {CircularProgressIndicator()}}},companion={})
            else ReadyJournal(graph,journal,current,onRecord,onRequestLocation,onRequestNotifications,onOnboardingStart,launcherAction,onLauncherActionHandled,onStartRecording,onStopRecording,onboardingAuthorization,if(returnToSettings) 3 else 0,access,onAccessOptions,visitLink,onVisitLinkHandled,windowFocused,initialJournal)
        }
        if(!clear.blocked) WorkspaceRootDialogScope {PermissionAccessReview(accessReview?.takeIf {it.generation==clear.generation},onAccessDismiss,onAccessRequest,onAccessSettings)}
    } }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReadyJournal(graph:AppGraph,journal:JournalViewModel,current:AppSettings,onRecord:()->Unit,
    onRequestLocation:()->Unit,onRequestNotifications:()->Unit,onOnboardingStart:suspend()->Unit,
    launcherAction:LauncherAction?,onLauncherActionHandled:()->Unit,onStartRecording:()->Unit,onStopRecording:()->Unit,
    onboardingAuthorization:UsageAuthorization,initialTab:Int,access:RecordingAccessState?,onAccessOptions:(PermissionAccessKind)->Unit,visitLink:VisitLinkRequest?,onVisitLinkHandled:(String)->Unit,windowFocused:Boolean,initialJournal:Int?) {
        val scope = rememberCoroutineScope()
        // The onboarding import shortcut lands on Archive, where the import's progress shows.
        var openArchiveAfterOnboarding by rememberSaveable { mutableStateOf(false) }
        if (!current.onboarded) {
            val context=androidx.compose.ui.platform.LocalContext.current
            val photos=rememberOnboardingPhotos(graph)
            JournalWorkspaceFrame(false,primary={Onboarding(onRequestLocation,onRequestNotifications,graph.usageAnalytics,onboardingAuthorization,access,onAccessOptions,photos,
                onImportSelected={uri->graph.historyImports.select(AndroidHistoryImportInput(context,uri),locale=AndroidHistoryLocale.capture(context));openArchiveAfterOnboarding=true}) {decision->
                val start=decision.start
                val permit=decision.permit
                val authorization=decision.authorization
                // The chosen preset sets distance and auto-stop before any first recording starts.
                suspend fun applyDetail()=decision.detail?.let {detail->graph.preferences.update {latest->latest.copy(distanceFilter=detail.distanceFilter,stopAfterHours=detail.stopAfterHours)}}
                completeOnboarding(start,{applyDetail();onOnboardingStart()},{applyDetail();graph.preferences.update {latest->latest.copy(onboarded=true)}}) {
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
        // Today, Atlas, Places and Archive are peers; Trace (map) and Timeline open over Today, as on iOS.
        // Settings lives behind Archive's gear, or on the rail in an expanded window.
        var section by rememberSaveable { mutableIntStateOf(if(initialTab==3 || openArchiveAfterOnboarding) ShellSection.archive.ordinal else ShellSection.today.ordinal) }
        var settingsOpen by rememberSaveable { mutableStateOf(initialTab==3) }
        // null while Today is showing; 0 for Trace (map primary), 1 for Timeline primary.
        // Debuggable QA launches may open straight into the journal, like iOS DEBUG `--default-tab`.
        var journalTab by rememberSaveable { mutableStateOf(if(initialTab==3) null else initialJournal?.takeIf {it in 0..1}) }
        var momentsOpen by rememberSaveable { mutableStateOf(false) }
        var loggingStop by rememberSaveable { mutableStateOf(false) }
        // Saving Home from Today: the latest recorded point, if any, as a starting pin.
        var savingHome by rememberSaveable { mutableStateOf(false) }
        var homeLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
        var homeLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
        // One journal-level owner survives changes to the primary/companion arrangement.
        // Keep the original snapshot so a background edit cannot replace its optimistic-lock baseline.
        var inspectedVisit by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<com.bontecou.whereday.domain.Visit?>(null)}
        var queuedVisitId by rememberSaveable {mutableStateOf<String?>(null)}
        val tabState=rememberSaveableStateHolder()
        fun openJournal(tab:Int) {journalTab=tab;section=ShellSection.today.ordinal}
        LaunchedEffect(launcherAction) {
            when(launcherAction) {
                LauncherAction.timeline->{openJournal(1);onLauncherActionHandled()}
                LauncherAction.export->{journalTab=null;settingsOpen=false;section=ShellSection.archive.ordinal;onLauncherActionHandled()}
                else->Unit
            }
        }
        val journalTitles = listOf(R.string.shell_map,R.string.shell_timeline).map {androidx.compose.ui.res.stringResource(it)}
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
                    onShowOnMap={stored->journal.showVisitOnMap(stored);inspectedVisit=null;if(!paired || journalTab==null) openJournal(0)},inline=LocalInlineVisitEditor.current,inlineBackLabel=if(journalTab==0) R.string.shell_timeline else R.string.common_back)
            }}
        }
        val mapPage=rememberWorkspaceContent {
            tabState.SaveableStateProvider("map") {
                JournalMapScreen(graph,journal,active,recording,current.metric,onRecord,current.showOutliers,{value->scope.launch {graph.preferences.update {it.copy(showOutliers=value)}}},access,onAccessOptions,onVisitOpened=::focusVisit,visitInspectionActive=inspectedVisit!=null)
            }
        }
        val timelinePage=rememberWorkspaceContent {
            tabState.SaveableStateProvider("timeline") {
                TimelineScreen(graph,journal,current.metric,showOnMap={target->journal.showOutingOnMap(target);if(!paired) journalTab=0},onVisitOpened=::focusVisit,visitInspectionActive=inspectedVisit!=null)
            }
        }
        val todayPage=rememberWorkspaceContent {
            tabState.SaveableStateProvider("today") {
                if(momentsOpen) PhotosScreen(graph) {momentsOpen=false}
                else TodayScreen(graph,current.metric,access,onReplay={openJournal(0)},onPickDay={openJournal(1)},onOpenVisit=::focusVisit,
                    onImportStarted={journalTab=null;settingsOpen=false;section=ShellSection.archive.ordinal},onAccessOptions=onAccessOptions,
                    onAccess={listening->when(listening) {
                        TodayListening.off->onAccessOptions(PermissionAccessKind.location)
                        TodayListening.limited->onAccessOptions(PermissionAccessKind.precise)
                        else->{settingsOpen=true;section=ShellSection.archive.ordinal}
                    }},onMoments={momentsOpen=true},onLogStop={loggingStop=true},onRecord=onRecord,
                    onSaveHome={at->homeLatitude=at?.latitude;homeLongitude=at?.longitude;savingHome=true})
            }
        }
        val journalOpen=journalTab!=null
        // Back leaves the journal for Today, and any other section for Today, before leaving the app.
        androidx.activity.compose.BackHandler(enabled=journalOpen && inspectedVisit==null) {journalTab=null}
        androidx.activity.compose.BackHandler(enabled=!journalOpen && settingsOpen) {settingsOpen=false}
        androidx.activity.compose.BackHandler(enabled=!journalOpen && !settingsOpen && section!=ShellSection.today.ordinal) {section=ShellSection.today.ordinal}
        if(savingHome) {
            val homeName=androidx.compose.ui.res.stringResource(R.string.place_kind_home)
            val at=homeLatitude?.let {lat->homeLongitude?.let {lon->com.bontecou.whereday.domain.Coordinate(lat,lon)}}
            PlaceEditor(null,{savingHome=false},{graph.savedPlaces.save(it,null)},null,PlaceEditorPreset(homeName,at,com.bontecou.whereday.domain.SavedPlaceKind.home))
        }
        if(loggingStop) VisitEditor(graph,null,{loggingStop=false},{value,reuse,remember,overlap->graph.history.saveVisit(value,null,reuse,remember,overlap);loggingStop=false},null)
        val jt=journalTab ?: 0
        JournalWorkspaceFrame(allowCompanion=journalOpen,modifier=Modifier.background(MaterialTheme.colorScheme.background),inspectionActive=inspectedVisit!=null,inspectionInPrimary=journalOpen && jt==1,
            modal={LauncherRecordingConfirmation(launcherAction,active,onLauncherActionHandled) {start ->
            onLauncherActionHandled()
            openJournal(0)
            if(start) onStartRecording() else onStopRecording()
        }
            VisitLinkHost(graph,visitLink,onVisitLinkHandled,windowFocused=windowFocused,showVisitOnMap={stored->journal.showVisitOnMap(stored);openJournal(0)}) {openJournal(0)}},
            inspection={inline->CompositionLocalProvider(LocalInlineVisitEditor provides inline) {Box(Modifier.fillMaxSize()) {inspector();if(inline && jt==1) SnackbarHost(workspaceMessages,Modifier.align(androidx.compose.ui.Alignment.BottomCenter))}}},primary={hasCompanion->
            SideEffect {paired=hasCompanion}
            if(journalOpen) {
                if(!(hasCompanion && jt==1 && inspectedVisit!=null)) Scaffold(topBar={JournalChrome(jt,journalTitles,{journalTab=null}) {journalTab=it}},
                    snackbarHost={SnackbarHost(workspaceMessages)},containerColor=MaterialTheme.colorScheme.background) {padding->
                    Box(Modifier.padding(padding).fillMaxSize()) {if(jt==0) mapPage() else timelinePage()}
                }
            } else ShellSections(section,settingsOpen,{section=it.ordinal;settingsOpen=it==ShellSection.settings;if(it==ShellSection.settings) section=ShellSection.archive.ordinal},workspaceMessages) {
                when(ShellSection.entries[section]) {
                    ShellSection.today->todayPage()
                    ShellSection.atlas->tabState.SaveableStateProvider("atlas") {AtlasScreen(graph,current.metric)}
                    ShellSection.places->tabState.SaveableStateProvider("places") {SavedPlacesScreen(graph,places.map {it.value},current.metric,::focusVisit) {section=ShellSection.today.ordinal}}
                    else->if(settingsOpen) tabState.SaveableStateProvider("settings") {SettingsScreen(graph,current,places.map {it.value},count)}
                        else tabState.SaveableStateProvider("archive") {ArchiveScreen(graph) {settingsOpen=true}}
                }
            }
        },companion={
            if(journalOpen && !(jt==0 && inspectedVisit!=null)) Scaffold(topBar={TopAppBar(title={Text(journalTitles[if(jt==0) 1 else 0],style=WheredayType.title2)})},containerColor=MaterialTheme.colorScheme.background) {padding->
                Box(Modifier.padding(padding).fillMaxSize()) {if(jt==0) timelinePage() else mapPage()}
            }
        })

}
