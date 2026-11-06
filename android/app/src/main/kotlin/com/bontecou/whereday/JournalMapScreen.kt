package com.bontecou.whereday

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.MapLayerSettings
import com.bontecou.whereday.data.RecordingRow
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Insets from the shell (status bar and floating tab bar) that the full-bleed map's chrome keeps clear of. */
internal val LocalMapChromePadding=compositionLocalOf {PaddingValues()}
/** Set while a map sheet covers the tab bar's place (day sheet open, visit sheet, replay). */
internal val LocalTabBarHidden=compositionLocalOf<MutableState<Boolean>?> {null}

/** The map-first home (M1–M8): a full-bleed map of the selected range with floating header, rail, recording pill and the day sheet. */
@Composable internal fun JournalMapScreen(graph:AppGraph,model:JournalViewModel,active:Boolean,recording:RecordingRow?,metric:Boolean,onRecord:()->Unit,showOutliers:Boolean,onOutliers:(Boolean)->Unit,
    access:RecordingAccessState?=null,onAccessOptions:(PermissionAccessKind)->Unit={},onVisitOpened:((Visit)->Unit)?=null,visitInspectionActive:Boolean=false,onOpenTimeline:()->Unit={}) {
    val colors=wd
    val zone=remember(model) {model.mapZone()}
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val scope=rememberCoroutineScope()
    val selected by model.mapSelection.collectAsStateWithLifecycle()
    val state by model.mapWindow.collectAsStateWithLifecycle()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val layers=settings?.map ?: MapLayerSettings()
    fun setLayers(value:MapLayerSettings)=scope.launch {graph.preferences.update {it.copy(map=value)}}
    val window=(state as? WindowState.Ready)?.window
    val activeRecording=rememberActiveRecordingMap(graph,active,showOutliers,visible=window!=null,persisted=layers.liveSession,onPersisted={setLayers(layers.copy(liveSession=it))})
    val controller=remember {JournalMapController()}
    val follow=rememberMapFollow()
    var editorMode by rememberSaveable {mutableStateOf<String?>(null)}
    var editorSeed by rememberSaveable(stateSaver=visitSeedSaver) {mutableStateOf<VisitLinkSeed?>(null)}
    var visitSaved by remember {mutableStateOf(false)}
    var custom by rememberSaveable {mutableStateOf(false)}
    var layersOpen by rememberSaveable {mutableStateOf(false)}
    var searching by rememberSaveable {mutableStateOf(false)}
    var replayShowing by remember {mutableStateOf(false)}
    var detent by rememberSaveable {mutableStateOf(DaySheetDetent.peek)}
    var quickVisitId by rememberSaveable {mutableStateOf<String?>(null)}
    // Without a shared journal inspector (a standalone map), Details opens the editor here, as the old map did.
    var detailVisit by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<Visit?>(null)}
    // "Not here" opens the place picker (search or drop a pin) to correct the visit's place, as iOS's PlaceSelectionView does.
    var correctingVisit by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<Visit?>(null)}
    var correctionFailed by remember {mutableStateOf(false)}
    var selectedDay by rememberSaveable(selected.copy(visitFocus=null).toString()) {mutableStateOf<String?>(null)}
    var hereDismissed by rememberSaveable(stateSaver=coordinateListSaver) {mutableStateOf<List<Coordinate>>(emptyList())}
    var now by remember {mutableStateOf(Instant.now())}
    LaunchedEffect(Unit) {while(true) {kotlinx.coroutines.delay(30_000);now=Instant.now()}}

    val range=window?.range ?: selected.range(now,zone)
    val firstDay=range.from.atZone(zone).toLocalDate()
    val lastDay=minOf(range.until.minusMillis(1),now).atZone(zone).toLocalDate().coerceAtLeast(firstDay)
    // Presets decide: Today, Yesterday, an outing or a focused visit are one day; 7D, 30D and All are ranges; This month and a custom range are ranges when they span days.
    val multiDay=when(selected.preset) {"7 days","30 days","All"->true;"This month","Custom"->lastDay>firstDay;else->false}
    val today=LocalDate.now(zone)
    val story=remember(window,firstDay,now.epochSecond/60) {window?.takeIf {!multiDay}?.let {DayStory.build(firstDay,zone,it.visits,it.points,now,it.summary.distanceMeters)}}
    // One per-day scan per selection; Room invalidation (not the live clock tick) refreshes it.
    val totals by remember(selected.copy(visitFocus=null),multiDay) {if(multiDay) graph.history.observeDayTotals({selected.range(Instant.now(),zone)},zone) else flowOf(null)}.collectAsStateWithLifecycle(initialValue=null)
    val summary=remember(window,totals,multiDay) {if(!multiDay || window==null) null else MapRangeAggregation.summarize(range,zone,now,window.visits,totals?.distances.orEmpty(),totals?.counts.orEmpty())}
    val activeDay=selectedDay?.let(LocalDate::parse) ?: summary?.latestActiveDay
    val dayPaths=remember(window,multiDay) {if(multiDay && window!=null) MapRangeAggregation.dayPaths(window.points,zone) else emptyMap()}
    val quickVisit=quickVisitId?.let {id->window?.visits?.firstOrNull {it.id==id}}
    val candidates=rememberVisitCandidates(graph,quickVisit)

    val tabBarHidden=LocalTabBarHidden.current
    val sheetCoversTabs=detent!=DaySheetDetent.peek || quickVisit!=null
    DisposableEffect(tabBarHidden,sheetCoversTabs) {tabBarHidden?.value=sheetCoversTabs;onDispose {tabBarHidden?.value=false}}
    androidx.activity.compose.BackHandler(enabled=quickVisit!=null || detent!=DaySheetDetent.peek) {
        if(quickVisit!=null) {quickVisitId=null;model.mapSelection.value=model.mapSelection.value.copy(visitFocus=null)} else detent=DaySheetDetent.peek
    }

    val shell=LocalMapChromePadding.current
    val density=LocalDensity.current
    val top=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom=shell.calculateBottomPadding().coerceAtLeast(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    var headerHeight by remember {mutableStateOf(112.dp)}
    var bottomChrome by remember {mutableStateOf(130.dp)}
    val headerBottom=top+8.dp+headerHeight
    val screenHeight=LocalConfiguration.current.screenHeightDp.dp
    val sheetCover=when {quickVisit!=null->320.dp;detent==DaySheetDetent.half->screenHeight*0.6f;detent==DaySheetDetent.full->screenHeight*0.6f;else->bottom+bottomChrome}
    // The scale bar and tile credit sit just above the bottom chrome; fitting keeps the route above them too.
    val mapPadding=PaddingValues(start=12.dp,top=headerBottom+8.dp,end=76.dp,bottom=sheetCover+34.dp)
    val photoBrowsing=rememberPhotoBrowserRange(selected,range)
    val photos=matchedPhotos(graph,photoBrowsing.range,settings?.showPhotoMarkers==true)
    val photoLayer=photoMapLayer(graph,photoBrowsing.range,photos.count!=null,onBrowserRange=photoBrowsing.hold,validationFailed=photos.error!=null)
    val homeMode=if(multiDay) MapHomeMode.Range(dayPaths,activeDay,summary?.places.orEmpty()) else MapHomeMode.Day(now,firstDay==today && selected.outingRange==null || selected.preset=="Today")
    val focusCandidates=if(quickVisit!=null) candidates.map {it.coordinate} else emptyList()
    val replayRequest=ReplayRequest.Range(range,showOutliers)
    val replayAvailable=window!=null && (if(showOutliers) window.summary.count else window.summary.count-window.summary.outlierCount)>=2

    Box(Modifier.fillMaxSize().background(colors.surface).semantics {testTagsAsResourceId=true}) {
        JournalMap(window?.points.orEmpty(),window?.visits.orEmpty(),Modifier.fillMaxSize(),endpoints=window?.endpoints ?: RouteEndpoints(),showOutliers=showOutliers,onOutliers=onOutliers,
            onVisitSelected={visit->
                // With the shared inspector already open, another visit queues there (it waits for an explicit close).
                if(visitInspectionActive && onVisitOpened!=null) {onVisitOpened(visit);return@JournalMap}
                if(multiDay) {selectedDay=visit.arrivedAt.atZone(zone).toLocalDate().toString()}
                // M5: focus the visit on this map and open its quick view.
                model.mapSelection.value=model.mapSelection.value.copy(visitFocus=VisitMapFocus(visit.id,visit.coordinate))
                quickVisitId=visit.id;detent=DaySheetDetent.peek
            },
            photoLayer=photoLayer,follow=follow,followMetric=metric,activeRecording=activeRecording,roadController=graph.roadDisplay,
            roadVisible=!visitInspectionActive && editorMode==null && !custom && !replayShowing && !multiDay,
            visitFocus=selected.visitFocus?.let {target->window?.visits?.find {it.id==target.id}?.let {target.copy(coordinate=it.coordinate)}},
            onVisitFocusCleared={model.mapSelection.value=model.mapSelection.value.copy(visitFocus=null)},inspectionScope=selected.copy(visitFocus=null),
            pointMarkerSource=rememberHistoryPointMarkerSource(graph,replayRequest),
            home=MapHomeOptions(controller,layers,mapPadding,homeMode,focusCandidates=focusCandidates,overlayTop=headerBottom+8.dp,overlayBottom=sheetCover,showsFocusLabel=quickVisit==null))

        // Header card and range chips.
        Column(Modifier.align(Alignment.TopCenter).padding(top=top+8.dp,start=12.dp,end=12.dp).widthIn(max=560.dp)
            .onHeight {headerHeight=with(density) {it.toDp()}},verticalArrangement=Arrangement.spacedBy(6.dp)) {
            MapHomeHeader(headerKicker(range,multiDay,summary?.days?.firstOrNull()?.day ?: firstDay,summary?.days?.lastOrNull()?.day ?: lastDay,locale,zone,now),headerTitle(selected,firstDay,lastDay,today,locale),{searching=true})
            MapRangeChips(selected.preset,{preset->quickVisitId=null;model.mapSelection.value=MapSelection(preset)},{custom=true})
            if(state is WindowState.Loading) Row(Modifier.wdFloating(CircleShape,colors,4.dp).padding(horizontal=12.dp,vertical=8.dp).testTag("map-loading"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp),color=colors.accent,trackColor=colors.dialTrack,strokeWidth=2.dp)
                Text(stringResource(R.string.map_loading_history),style=WdType.caption2,color=colors.textMuted)
            }
            (state as? WindowState.Failed)?.let {failed->
                WdNotice(failed.message,icon=Icons.Outlined.ErrorOutline,warning=true) {WdButton(stringResource(R.string.map_retry_history),model::retry,icon=Icons.Outlined.Refresh)}
            }
        }

        // Right rail: the live mini dial opens Replay; Layers, Fit (long-press: live session) and My location beneath.
        Column(Modifier.align(Alignment.TopEnd).padding(top=headerBottom+8.dp,end=12.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            MapRail(dial={
                RouteReplayHost(graph,replayRequest,metric,replayAvailable,onShowingChange={replayShowing=it},owner="${selected.copy(visitFocus=null)}:outliers=$showOutliers") {open,enabled->
                    MapMiniDial(story?.let {dayDialSegments(it)} ?: emptyList(),story?.let {dayNowFraction(it,now)},stringResource(R.string.map_home_replay_day),enabled,open)
                }
            },onLayers={layersOpen=true},onFit={controller.fit()},onFitLive=if(activeRecording.snapshot?.count?.let {it>0}==true) ({controller.fitActive()}) else null,
                following=follow.enabled,onFollow={if(selected.visitFocus!=null && !follow.enabled) model.mapSelection.value=selected.copy(visitFocus=null);follow.toggle()})
            MapFollowSlip(follow,metric)
        }

        // Bottom chrome: Are you here?, the recording pill with +, and the day peek or range card.
        if(quickVisit==null && detent==DaySheetDetent.peek) Column(Modifier.align(Alignment.BottomCenter).padding(start=12.dp,end=12.dp,bottom=bottom+2.dp).widthIn(max=560.dp)
            .onHeight {bottomChrome=with(density) {it.toDp()}},verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(layers.visitSuggestions && !multiDay && firstDay==today) {
                val here=rememberHereSuggestion(graph,window,follow,settings?.networkGeocoding==true,hereDismissed,now)
                here?.let {suggestion->
                    MapHereCard(suggestion.minutes,suggestion.name,suggestion.detail(metric,locale),
                        onSomewhereElse={editorSeed=null;editorMode=ManualVisitMode.current.name;visitSaved=false},
                        onSave={editorSeed=VisitLinkSeed(ManualVisitMode.current,suggestion.name,suggestion.coordinate,now.minus(Duration.ofMinutes(suggestion.minutes ?: 0)),now);editorMode=ManualVisitMode.current.name;visitSaved=false},
                        onDismiss={hereDismissed=hereDismissed+suggestion.location})
                }
            }
            access?.let {RecordingAccessPill(it,onAccessOptions)}
            recording?.interruption?.let {Text(recordingInterruptionText(it),Modifier.wdFloating(RoundedCornerShape(14.dp),colors,4.dp).padding(12.dp).testTag("recording-interruption"),style=MaterialTheme.typography.bodySmall,color=colors.inkRust)}
            if(visitSaved) Row(Modifier.wdFloating(CircleShape,colors,4.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.CheckCircle,null,Modifier.size(16.dp),tint=colors.success)
                Text(stringResource(R.string.map_visit_saved),style=MaterialTheme.typography.bodySmall,color=colors.textPrimary)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MapRecordingPill(active,recording,activeRecording.snapshot?.count ?: window?.takeIf {active && firstDay==today}?.summary?.count,onRecord,Modifier.weight(1f))
                MapAddButton(!visitInspectionActive,onSaveHere={editorSeed=null;editorMode=ManualVisitMode.current.name;visitSaved=false},
                    onPastStop={editorSeed=null;editorMode=ManualVisitMode.past.name;visitSaved=false},
                    onDropPin={editorSeed=VisitLinkSeed(ManualVisitMode.past,"",controller.center(),now.minus(Duration.ofHours(1)),now);editorMode=ManualVisitMode.past.name;visitSaved=false})
            }
            if(summary!=null) MapRangeCard(summary,activeDay,metric,{selectedDay=it.toString()},{detent=DaySheetDetent.half})
            else MapDayPeek(story,metric,now,{detent=DaySheetDetent.half})
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val available=maxHeight
            if(quickVisit!=null) MapVisitSheet(graph,quickVisit,candidates,metric,
                onClose={quickVisitId=null;model.mapSelection.value=model.mapSelection.value.copy(visitFocus=null)},
                onNotHere={correctionFailed=false;correctingVisit=quickVisit},onDetails={quickVisitId=null;if(onVisitOpened!=null) onVisitOpened(quickVisit) else detailVisit=quickVisit},
                modifier=Modifier.align(Alignment.BottomCenter).widthIn(max=640.dp))
            else if(detent!=DaySheetDetent.peek) MapDetentSheet(detent,{detent=it},available,headerBottom,Modifier.align(Alignment.BottomCenter).widthIn(max=640.dp)) {
                val large=LocalDensity.current.fontScale>=1.3f
                if(summary!=null) MapRangeContent(summary,activeDay,metric) {selectedDay=it.toString()}
                else if(story!=null) MapDayContent(story,metric,now,{stay->
                    model.mapSelection.value=model.mapSelection.value.copy(visitFocus=VisitMapFocus(stay.visit.id,stay.visit.coordinate))
                    quickVisitId=stay.visit.id;detent=DaySheetDetent.peek
                },{detent=DaySheetDetent.peek;onOpenTimeline()},large)
                else WdLoading()
                access?.let {RecordingAccessNotice(it,onAccessOptions,contentPadding=PaddingValues(top=8.dp))}
            }
        }
    }

    correctingVisit?.let {visit->key(visit.id) {
        BlockVisitLinks()
        PlaceLocationPicker(visit.coordinate,{correctingVisit=null}) {selection->
            correctingVisit=null
            scope.launch {
                try {
                    graph.history.saveVisit(correctedVisit(visit,selection,Instant.now()),visit)
                    quickVisitId=null;model.mapSelection.value=model.mapSelection.value.copy(visitFocus=null)
                } catch(e:CancellationException) {throw e} catch(_:Exception) {correctionFailed=true}
            }
        }
    }}
    if(correctionFailed) WorkspaceAlertDialog(onDismissRequest={correctionFailed=false},text={Text(stringResource(R.string.visit_error_save))},
        confirmButton={TextButton(onClick={correctionFailed=false}) {Text(stringResource(R.string.visit_link_ok))}})
    detailVisit?.let {visit->key(visit.id) {
        VisitEditor(graph,visit,{detailVisit=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,visit,reuse,remember,overlap)},{graph.history.deleteVisit(visit)})
    }}
    editorMode?.let {mode->
        VisitEditor(graph,null,{editorMode=null;editorSeed=null},{value,reuse,remember,overlap->
            graph.history.saveVisit(value,null,reuse,remember,overlap);visitSaved=true
        },null,ManualVisitMode.valueOf(mode),seed=editorSeed)
    }
    if(custom) {
        val draft=remember(selected) {model.mapRangeDraft()}
        val arrivals by remember(graph) {graph.history.observeVisitArrivals()}.collectAsStateWithLifecycle(emptyList())
        val zone=model.mapZone()
        val recordedDays=remember(arrivals,zone) {arrivals.mapTo(HashSet()) {it.atZone(zone).toLocalDate()}}
        MapRangeSheet(draft,recordedDays,model::mapNow,zone,{custom=false}) {selection->model.mapSelection.value=selection;custom=false}
    }
    if(layersOpen) MapLayersSheet(graph,layers,::setLayers,showOutliers,onOutliers,photoLayer,active,window?.endpoints ?: RouteEndpoints()) {layersOpen=false}
    if(searching) MapSearchDialog(graph,{searching=false}) {result->
        searching=false
        when(result) {
            is MapSearchResult.Place->controller.focus(listOf(result.place.coordinate))
            is MapSearchResult.Stop->{model.showVisitOnMap(result.visit);quickVisitId=result.visit.id}
        }
    }
}

private fun Modifier.onHeight(block:(Int)->Unit)=this.onSizeChanged {block(it.height)}

@Composable private fun headerTitle(selection:MapSelection,first:LocalDate,last:LocalDate,today:LocalDate,locale:Locale):String = when {
    selection.preset=="Today"->stringResource(R.string.map_date_today)
    selection.preset=="Yesterday"->stringResource(R.string.map_date_yesterday)
    selection.preset=="7 days"->stringResource(R.string.map_home_title_week)
    selection.preset=="30 days"->stringResource(R.string.map_home_title_month)
    selection.preset=="All"->stringResource(R.string.map_home_title_all)
    selection.preset=="Outing"->stringResource(R.string.timeline_outing)
    first==last && first==today->stringResource(R.string.map_date_today)
    first==last && first==today.minusDays(1)->stringResource(R.string.map_date_yesterday)
    first==last->DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"EEEEMMMMd"),locale).format(first)
    else->listOf(first,last).joinToString(" – ") {DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,if(showsYear(first,last,today)) "MMMdyyyy" else "MMMd"),locale).format(it)}
}

/** Dates name their year when a range crosses years or lies outside this one, so "Oct 2 – Nov 5" never hides 400 days. */
internal fun showsYear(first:LocalDate,last:LocalDate,today:LocalDate)=first.year!=last.year || last.year!=today.year

@Composable private fun headerKicker(range:HistoryRange,multiDay:Boolean,first:LocalDate,last:LocalDate,locale:Locale,zone:ZoneId,now:Instant):String {
    val short=DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,if(showsYear(first,last,now.atZone(zone).toLocalDate())) "MMMdyyyy" else "MMMd"),locale)
    if(multiDay) {
        val days=(java.time.temporal.ChronoUnit.DAYS.between(first,last)+1).toInt()
        // All starts at the beginning of history; it reads from the first recorded day.
        return (short.format(first)+" – "+short.format(last)+" · "+pluralStringResource(R.plurals.map_home_days_count,days,days)).uppercase(locale)
    }
    return listOf(DateTimeFormatter.ofPattern("EEE",locale).format(first),short.format(first),stringResource(R.string.map_home_day_of_year,first.dayOfYear)).joinToString(" · ").uppercase(locale)
}

/** M7's suggestion: a fresh fix (≤5 min, ≤100 m) matched to a saved place inside its radius, otherwise one nearby business. */
internal data class HereSuggestion(val name:String,val coordinate:Coordinate,val distanceMeters:Double,val saved:Boolean,val minutes:Long?,val location:Coordinate) {
    @Composable fun detail(metric:Boolean,locale:Locale)=listOf(stringResource(R.string.map_home_away_distance,dayDistance(distanceMeters,metric,locale)),
        stringResource(if(saved) R.string.map_home_source_saved else R.string.map_home_source_nearby)).joinToString(" · ")
}

@Composable private fun rememberHereSuggestion(graph:AppGraph,window:HistoryWindow?,follow:MapFollowUi,network:Boolean,dismissed:List<Coordinate>,now:Instant):HereSuggestion? {
    val places by graph.history.dao.observePlaces().collectAsStateWithLifecycle(emptyList())
    // The newest usable fix: the follow fix when shown, otherwise today's latest recorded point.
    val latest=window?.points?.filterNot {it.outlier}?.maxByOrNull {it.time}?.takeIf {Duration.between(it.time,now)<=Duration.ofMinutes(5) && it.accuracy in 0.0..100.0}
    val fix=follow.fix?.let {CurrentVisitLocation(it.coordinate,it.accuracyMeters,it.elapsedMillis)}
        ?: latest?.let {CurrentVisitLocation(it.coordinate,it.accuracy,SystemClock.elapsedRealtime()-Duration.between(it.time,now).toMillis().coerceAtLeast(0))}
    val usable=fix?.takeIf {it.isUsable(SystemClock.elapsedRealtime())}
    // Dismissing hides suggestions within 500 m of where it was dismissed (iOS CurrentPlacePromptSuppression).
    val suppressed=usable!=null && dismissed.any {it.distanceTo(usable.coordinate)<=500}
    val ongoing=window?.visits?.lastOrNull {it.departedAt==null}
    val confirmedHere=ongoing!=null && ongoing.confirmation!=Confirmation.unconfirmed && usable!=null && ongoing.coordinate.distanceTo(usable.coordinate)<=150
    val dwell=usable?.let {current->
        val recent=window?.points.orEmpty().filterNot {it.outlier}.sortedByDescending {it.time}.takeWhile {it.coordinate.distanceTo(current.coordinate)<=100}
        recent.lastOrNull()?.let {Duration.between(it.time,now).toMinutes()}?.takeIf {it>0}
    }
    val key=usable?.let {"%.4f,%.4f".format(Locale.ROOT,it.coordinate.latitude,it.coordinate.longitude)}
    var business by remember(key,network) {mutableStateOf<NearbyPlaceSuggestion?>(null)}
    val saved=usable?.savedPlaceSuggestion(places.map {it.value},SystemClock.elapsedRealtime())
    LaunchedEffect(key,network,saved==null,suppressed) {
        val target=usable ?: return@LaunchedEffect
        if(saved!=null || !network || suppressed) return@LaunchedEffect
        business=try {graph.placeLookup.nearby(target.coordinate,500.0,1).firstOrNull()} catch(e:CancellationException) {throw e} catch(_:Exception) {null}
    }
    if(usable==null || suppressed || confirmedHere) return null
    return saved?.let {HereSuggestion(it.name,it.coordinate,it.coordinate.distanceTo(usable.coordinate),true,dwell,usable.coordinate)}
        ?: business?.let {HereSuggestion(it.name,it.coordinate,it.distanceMeters,false,dwell,usable.coordinate)}
}

private val coordinateListSaver=listSaver<List<Coordinate>,Double>(save={list->list.flatMap {listOf(it.latitude,it.longitude)}},restore={values->values.chunked(2).map {Coordinate(it[0],it[1])}})
private val visitSeedSaver=listSaver<VisitLinkSeed?,String>(
    save={seed->if(seed==null) emptyList() else listOf(seed.mode.name,seed.name,seed.coordinate?.latitude?.toString().orEmpty(),seed.coordinate?.longitude?.toString().orEmpty(),seed.arrival.toString(),seed.departure.toString())},
    restore={values->if(values.size<6) null else VisitLinkSeed(ManualVisitMode.valueOf(values[0]),values[1],
        values[2].toDoubleOrNull()?.let {lat->values[3].toDoubleOrNull()?.let {lon->Coordinate(lat,lon)}},Instant.parse(values[4]),Instant.parse(values[5]))})

internal sealed interface MapSearchResult {
    data class Place(val place:SavedPlace):MapSearchResult
    data class Stop(val visit:Visit):MapSearchResult
}

/** Search saved places and visits; choosing one focuses the map on it. */
@Composable private fun MapSearchDialog(graph:AppGraph,dismiss:()->Unit,choose:(MapSearchResult)->Unit) {
    val colors=wd
    var query by rememberSaveable {mutableStateOf("")}
    val places by graph.history.dao.observePlaces().collectAsStateWithLifecycle(emptyList())
    val visits by graph.history.dao.observeVisits().collectAsStateWithLifecycle(emptyList())
    BlockVisitLinks()
    val key=query.trim().lowercase()
    val placeResults=places.map {it.value}.filter {key.isEmpty() || key in it.name.lowercase() || key in it.address.orEmpty().lowercase()}.take(20)
    val visitResults=if(key.isEmpty()) visits.map {it.value}.take(10) else visits.map {it.value}.filter {key in it.name.orEmpty().lowercase() || key in it.address.orEmpty().lowercase()}.take(30)
    WorkspaceAlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.map_home_search))},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query,{query=it},singleLine=true,colors=wdTextFieldColors(),leadingIcon={Icon(Icons.Outlined.Search,null)},
                placeholder={Text(stringResource(R.string.map_home_search_hint))},modifier=Modifier.fillMaxWidth().testTag("map-search-field"))
            LazyColumn(Modifier.heightIn(max=380.dp)) {
                if(placeResults.isNotEmpty()) item {WdSectionHeader(stringResource(R.string.saved_title))}
                items(placeResults,key={"place-${it.id}"}) {place->
                    SearchRow(place.name,place.address,Icons.Outlined.Place) {choose(MapSearchResult.Place(place))}
                }
                if(visitResults.isNotEmpty()) item {WdSectionHeader(stringResource(R.string.map_visits))}
                items(visitResults,key={"visit-${it.id}"}) {visit->
                    SearchRow(visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit),timeText(visit.arrivedAt),Icons.Outlined.History) {choose(MapSearchResult.Stop(visit))}
                }
                if(placeResults.isEmpty() && visitResults.isEmpty()) item {Text(stringResource(R.string.map_home_search_empty),style=MaterialTheme.typography.bodyMedium,color=colors.textMuted)}
            }
        }
    },confirmButton={TextButton(onClick=dismiss) {Text(stringResource(R.string.map_visits_close))}})
}

@Composable private fun SearchRow(title:String,detail:String?,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button,onClick=onClick).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,Modifier.size(18.dp),tint=wd.textMuted)
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium),color=wd.textPrimary,maxLines=1)
            detail?.let {Text(it,style=WdType.caption2,color=wd.textMuted,maxLines=1)}
        }
    }
}

/** The visit moved to the place picked for it (iOS correctVisit(with:)), with the same name and address rules as the
 *  visit editor's picker (VisitDraft.selectLocation): a picked place brings its own; a moved pin alone keeps them. */
internal fun correctedVisit(visit:Visit,selection:PlaceLocationSelection,now:Instant):Visit {
    val draft=VisitDraft(visit.id,visit.name.orEmpty(),visit.address.orEmpty(),visit.notes.orEmpty(),visit.coordinate,visit.arrivedAt,visit.departedAt,visit.placeSource ?: "userEntered",visit.placeDistanceMeters)
        .selectLocation(selection)
    return visit.correct(selection.coordinate,draft.name.trim().ifEmpty {null},draft.address.trim().ifEmpty {null},now)
        .copy(placeSource=draft.placeSource,placeDistanceMeters=draft.placeDistanceMeters)
}
