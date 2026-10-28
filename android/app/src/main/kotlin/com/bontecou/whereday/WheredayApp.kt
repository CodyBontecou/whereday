package com.bontecou.whereday

import androidx.compose.foundation.isSystemInDarkTheme
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
    launcherAction:LauncherAction?=null,onLauncherActionHandled:()->Unit={},onStartRecording:()->Unit={},onStopRecording:()->Unit={},onboardingAuthorization:UsageAuthorization=UsageAuthorization.unknown) {
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = null)
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
    val colors = if (dark) darkColorScheme(primary = Color(0xFFA8D8B8), secondary = Color(0xFFBBCBBF))
        else lightColorScheme(primary = Color(0xFF285D42), secondary = Color(0xFF4E6355))
    MaterialTheme(colorScheme = colors) {
        if (current == null) { Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(32.dp)) { CircularProgressIndicator() } }; return@MaterialTheme }
        val scope = rememberCoroutineScope()
        if (!current.onboarded) {
            Onboarding(onRequestLocation,onRequestNotifications,graph.usageAnalytics,onboardingAuthorization) {decision->
                val start=decision.start
                val permit=decision.permit
                val authorization=decision.authorization
                completeOnboarding(start,onOnboardingStart,{graph.preferences.update {latest->latest.copy(onboarded=true)}}) {
                    graph.usageAnalytics.track(permit) {UsageEvent(UsageEventName.onboardingCompleted,step=UsageStep.ready,authorization=authorization,
                        tracking=if(start) UsageTrackingIntent.startImmediately else if(authorization in setOf(UsageAuthorization.foreground,UsageAuthorization.always)) UsageTrackingIntent.later else UsageTrackingIntent.unavailable)}
                }
            }
            return@MaterialTheme
        }
        val places by graph.history.dao.observePlaces().collectAsStateWithLifecycle(emptyList())
        val count by graph.history.dao.pointCount().collectAsStateWithLifecycle(0L)
        val recording by graph.history.dao.observeRecording().collectAsStateWithLifecycle(null)
        val active by RecordingRuntime.active.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val tabState=rememberSaveableStateHolder()
        LaunchedEffect(launcherAction) {
            when(launcherAction) {
                LauncherAction.timeline->{tab=1;onLauncherActionHandled()}
                LauncherAction.export->{tab=2;onLauncherActionHandled()}
                else->Unit
            }
        }
        LauncherRecordingConfirmation(launcherAction,active,onLauncherActionHandled) {start ->
            onLauncherActionHandled()
            tab=0
            if(start) onStartRecording() else onStopRecording()
        }
        val titles = listOf("Map", "Timeline", "Export", "Settings")
        val icons = listOf(Icons.Outlined.Map, Icons.Outlined.Timeline, Icons.Outlined.IosShare, Icons.Outlined.Settings)
        Scaffold(topBar = { TopAppBar(title = { Text(if (tab == 0) "Whereday" else titles[tab]) }) },
            bottomBar = { NavigationBar { titles.forEachIndexed { i, title -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], null) }, label = { Text(title) }) } } }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                tabState.SaveableStateProvider(tab) { when (tab) {
                    0 -> JournalMapScreen(graph,journal, active, recording, current.metric, onRecord,current.showOutliers) {value->scope.launch {graph.preferences.update {it.copy(showOutliers=value)}}}
                    1 -> TimelineScreen(graph, journal, current.metric) { session -> journal.showOutingOnMap(session); tab=0 }
                    2 -> ExportScreen(graph)
                    3 -> SettingsScreen(graph, current, places.map { it.value }, count)
                } }
            }
        }
    }
}


/** Public shortcut Intents never start location capture without an explicit foreground choice. */
@Composable internal fun LauncherRecordingConfirmation(action:LauncherAction?,active:Boolean,dismiss:()->Unit,confirm:(Boolean)->Unit) {
    if(action!=LauncherAction.start && action!=LauncherAction.stop) return
    val start=action==LauncherAction.start
    val already=if(start) active else !active
    AlertDialog(onDismissRequest=dismiss,
        title={Text(if(already) {if(start) "Recording is already active" else "Recording is stopped"}
            else if(start) "Start recording?" else "Stop recording?")},
        text={Text(if(already) "Open the map to check your journal."
            else if(start) "Whereday will save your location while recording, including with the screen off. You can stop at any time."
            else "End the current recording and keep its saved history.")},
        confirmButton={TextButton(onClick={if(already) dismiss() else confirm(start)}) {
            Text(if(already) "Done" else if(start) "Start recording" else "Stop recording")
        }},
        dismissButton={if(!already) TextButton(onClick=dismiss) {Text("Cancel")}})
}
