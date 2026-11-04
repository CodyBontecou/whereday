package com.bontecou.whereday

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.launch
import java.time.*

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable internal fun TimelineScreen(graph: AppGraph, model: JournalViewModel, metric: Boolean, showOnMap: (RecordingSession) -> Unit,onVisitOpened:((Visit)->Unit)?=null,visitInspectionActive:Boolean=false,today:TodayChrome?=null) {
    val day by model.timelineDay.collectAsStateWithLifecycle()
    val state by model.timelineWindow.collectAsStateWithLifecycle()
    val resources=LocalContext.current.resources
    val largeText=LocalDensity.current.fontScale>=1.3f
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val formattedDay=day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    val todayHint=stringResource(R.string.timeline_today_hint,formattedDay)
    var selectedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(stateSaver=VisitEditorVisitSaver) { mutableStateOf<Visit?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var openedVisitId by rememberSaveable { mutableStateOf<String?>(null) }
    if(selectedSession != null) {
        val header=(state as? WindowState.Ready)?.window?.inferred?.find { it.id==selectedSession }
        if(selectedSession!!.startsWith("inferred:") && header==null) {
            Column(Modifier.fillMaxSize().background(wd.surface).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                WdBackChip(stringResource(R.string.timeline_back),{ selectedSession=null })
                WdNotice(stringResource(R.string.timeline_inferred_missing),icon=Icons.Outlined.Info)
            }
        } else {
            val window=(state as? WindowState.Ready)?.window
            val chronological=(window?.sessions.orEmpty().map {it.id to it.startedAt}+window?.inferred.orEmpty().map {it.id to it.startedAt})
                .sortedWith(compareBy({it.second},{it.first}))
            val sequence=(chronological.indexOfFirst {it.first==selectedSession}+1).coerceAtLeast(1)
            OutingDetailScreen(graph,selectedSession!!,metric,{ selectedSession=null },showOnMap,header,sequence,onVisitOpened,visitInspectionActive)
        }
        return
    }
    val eyebrow=stringResource(R.string.timeline_redesign_eyebrow,day.format(DateTimeFormatter.ofPattern("EEE",locale)),
        day.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,if(day.year==LocalDate.now().year) "MMMd" else "MMMdyyyy"),locale)),day.dayOfYear)
    val isToday=day==LocalDate.now()
    var picking by rememberSaveable {mutableStateOf(false)}
    // The rail's "Log a stop" FAB lives outside this page; each new request opens the past-visit editor once.
    var handledLogStop by rememberSaveable {mutableIntStateOf(today?.logStopRequests ?: 0)}
    LaunchedEffect(today?.logStopRequests) {val requests=today?.logStopRequests ?: 0;if(requests!=handledLogStop) {handledLogStop=requests;adding=true}}
    // The Large text board moves "Log a stop" into the top bar; on the rail it is the rail's FAB instead.
    val railLogStop=today?.showLogStopFab==false
    val logStopInHeader=largeText && !railLogStop
    val locationOff=today?.access?.location==LocationPrecision.none
    Box(Modifier.fillMaxSize().background(wd.surface)) {
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("timeline-content"),contentPadding=PaddingValues(bottom=if(today!=null && !logStopInHeader && !railLogStop) 104.dp else 28.dp)) {
        // Today board header: date caption over the serif title, with Pick a day and Settings (Log a stop joins them at large text).
        item { WdPageHeader(if(isToday) stringResource(R.string.today_title) else day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)),eyebrow=eyebrow) {
            if((today==null || logStopInHeader) && !railLogStop) WdIconButton(Icons.Outlined.AddLocationAlt,stringResource(R.string.today_log_stop),{ adding=true },Modifier.testTag("today-log-stop-header"),tonal=true)
            WdIconButton(Icons.Outlined.CalendarMonth,stringResource(R.string.today_pick_day),{ picking=true },Modifier.testTag("today-pick-day"))
            if(today!=null) WdIconButton(Icons.Outlined.Settings,stringResource(R.string.shell_settings),today.onSettings,Modifier.testTag("today-settings"))
        } }
        if(!isToday) item { Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            WdIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,stringResource(R.string.timeline_previous),{ model.timelineDay.value=day.minusDays(1) },Modifier.testTag("timeline-previous"))
            // The chip names the day shown; tapping it returns to today.
            WdChip(formattedDay,false,{ model.timelineDay.value=LocalDate.now() },Modifier.weight(1f,fill=false).testTag("timeline-today").semantics {contentDescription=todayHint},icon=Icons.Outlined.Today)
            Spacer(Modifier.weight(1f))
            WdIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight,stringResource(R.string.timeline_next),{ model.timelineDay.value=day.plusDays(1) },Modifier.testTag("timeline-next"))
        } }
        if(today!=null && isToday) item {
            today.access?.let {RecordingAccessNotice(it,today.onAccessOptions,contentPadding=PaddingValues(start=16.dp,end=16.dp,top=4.dp,bottom=8.dp))}
        }
        when(val current=state) {
            WindowState.Loading -> item { WdLoading(Modifier.padding(top=24.dp)) }
            is WindowState.Failed -> item { WdNotice(current.message,Modifier.padding(horizontal=16.dp).padding(top=12.dp),icon=Icons.Outlined.ErrorOutline,warning=true,action={
                TextButton(onClick=model::retry,colors=ButtonDefaults.textButtonColors(contentColor=wd.onSecondaryContainer)) { Text(stringResource(R.string.map_retry_history),style=MaterialTheme.typography.labelLarge) }
            }) }
            is WindowState.Ready -> {
                val window=current.window
                val zone=java.time.ZoneId.systemDefault()
                val dayStart=day.atStartOfDay(zone).toInstant()
                val dayEnd=day.plusDays(1).atStartOfDay(zone).toInstant()
                val nowInstant=java.time.Instant.now()
                val inks=journalInkIndex(window.visits.sortedBy {it.arrivedAt}.map {it.name?.takeIf {n->n.isNotBlank()} ?: it.id})
                fun inkIndex(visit:Visit)=inks[visit.name?.takeIf {it.isNotBlank()} ?: visit.id] ?: 0
                val outings=window.sessions.size+window.inferred.size
                val visits=window.visits.size
                // Hero: the 24-hour dial (tap to replay on the map) beside the day's three figures.
                item("today-hero") {
                    val summaryText=stringResource(R.string.timeline_summary,resources.getQuantityString(R.plurals.timeline_outing_count,outings,outings),resources.getQuantityString(R.plurals.timeline_visit_count,visits,visits),distanceText(window.summary.distanceMeters,metric,locale))
                    val placeInks=wd.placeInks
                    fun inkOf(visit:Visit)=placeInks[inkIndex(visit)%placeInks.size]
                    fun fraction(at:java.time.Instant)=(java.time.Duration.between(dayStart,at).toMillis()/86_400_000f).coerceIn(0f,1f)
                    val segments=window.visits.map {visit->WdDialSegment(fraction(visit.arrivedAt),fraction(visit.departedAt ?: nowInstant),inkOf(visit))}
                    val lastFix=window.summary.lastTime
                    val kicker:String?;val center:String?;val hint:String?
                    when {
                        !isToday->{kicker=day.format(DateTimeFormatter.ofPattern("EEE",locale));center=day.format(DateTimeFormatter.ofPattern("d",locale));hint=stringResource(R.string.today_replay_hint)}
                        locationOff && lastFix!=null->{kicker=stringResource(R.string.today_dial_last_fix);center=lastFix.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));hint=stringResource(R.string.today_location_off_hint)}
                        today?.active==true && window.visits.isEmpty() && window.sessions.any {it.endedAt==null}->{kicker=stringResource(R.string.today_dial_listening);center=window.sessions.first {it.endedAt==null}.startedAt.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));hint=stringResource(R.string.today_replay_hint)}
                        else->{kicker=stringResource(R.string.timeline_redesign_now);center=nowInstant.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));hint=stringResource(R.string.today_replay_hint)}
                    }
                    val visitMillis=window.visits.sumOf {visit->
                        val start=maxOf(visit.arrivedAt,dayStart);val end=minOf(visit.departedAt ?: nowInstant,dayEnd)
                        java.time.Duration.between(start,end).toMillis().coerceAtLeast(0)
                    }
                    val firstSeen=listOfNotNull(window.summary.firstTime,window.visits.minOfOrNull {it.arrivedAt}).minOrNull()?.let {maxOf(it,dayStart)}
                    val lastSeen=if(isToday) minOf(nowInstant,dayEnd) else listOfNotNull(window.summary.lastTime,window.visits.maxOfOrNull {it.departedAt ?: dayEnd}).maxOrNull()?.let {minOf(it,dayEnd)}
                    val awayMinutes=if(firstSeen!=null && lastSeen!=null) ((java.time.Duration.between(firstSeen,lastSeen).toMillis()-visitMillis)/60_000).coerceAtLeast(0) else 0
                    val number=java.text.NumberFormat.getIntegerInstance(locale)
                    val stats=listOf(
                        todayDistance(window.summary.distanceMeters,metric,locale) to stringResource(R.string.today_stat_moved),
                        (if(awayMinutes<60) stringResource(R.string.timeline_redesign_minutes,awayMinutes.toInt()) else stringResource(R.string.timeline_redesign_hours,(awayMinutes/60).toInt(),"%02d".format(awayMinutes%60))) to stringResource(R.string.today_stat_away),
                        number.format((window.summary.count-window.summary.outlierCount).coerceAtLeast(0)) to stringResource(R.string.today_stat_points),
                    )
                    val heroShape=RoundedCornerShape(28.dp)
                    val dialSize=if(largeText) 140.dp else 168.dp
                    val dial:@Composable ()->Unit={
                        Box(Modifier.size(dialSize).clip(CircleShape).clickable(role=Role.Button,onClickLabel=stringResource(R.string.today_replay_label)) {model.showDayOnMap(day);today?.onOpenMap?.invoke()}
                            .semantics {contentDescription=summaryText}.testTag("today-dial"),contentAlignment=Alignment.Center) {
                            WdDayDial(segments,now=if(isToday && !locationOff) fraction(nowInstant) else null,size=dialSize,nowCaption=kicker,centerText=center,hint=hint)
                        }
                    }
                    val figures:@Composable (Boolean)->Unit={row->
                        val content:@Composable ()->Unit={stats.forEach {(value,label)->Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(value,style=WdType.figure,color=wd.textPrimary,maxLines=1)
                            Text(label.uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.1.em),color=wd.textMuted)
                        }}}
                        if(row) FlowRow(horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {content()}
                        else Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {content()}
                    }
                    if(largeText) Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=4.dp).clip(heroShape).background(wd.containerLow).padding(16.dp).testTag("timeline-summary"),verticalArrangement=Arrangement.spacedBy(18.dp)) {dial();figures(true)}
                    else Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=4.dp).clip(heroShape).background(wd.containerLow).padding(16.dp).testTag("timeline-summary"),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {dial();figures(false)}
                }
                // "You're at" card while a stay is open; otherwise today offers the recording control.
                val ongoing=window.visits.firstOrNull {it.departedAt==null}
                if(isToday && ongoing!=null) item("today-here") {
                    WdInkCard(Modifier.padding(horizontal=16.dp).padding(top=12.dp).testTag("today-here")) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(wd.liveDot))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.today_you_are_at,journalClock(ongoing.arrivedAt)).uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.12.em),color=wd.onInkCardMuted)
                            Text(ongoing.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit),style=WdType.title2.copy(fontSize=24.sp,fontWeight=FontWeight.Normal),color=wd.onInkCard)
                        }
                        Box(Modifier.heightIn(min=40.dp).clip(CircleShape).border(1.dp,wd.onInkCardMuted,CircleShape)
                            .clickable(role=Role.Button) { if(onVisitOpened!=null) { openedVisitId=ongoing.id; onVisitOpened(ongoing) } else editing=ongoing }.padding(horizontal=16.dp),contentAlignment=Alignment.Center) {
                            Text(stringResource(R.string.today_rename),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=wd.onInkCard)
                        }
                    }
                }
                if(isToday && today!=null && (ongoing==null || !today.active)) item("today-recording") {
                    Column(Modifier.padding(top=12.dp)) {
                        RecordingControls(today.active,today.recording,header=false,record=today.onRecord)
                        if(today.onListening!=null) TextButton(today.onListening,Modifier.padding(start=12.dp).testTag("today-listening")) {
                            Text(stringResource(R.string.listening_title),style=MaterialTheme.typography.labelLarge,color=wd.accent)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.accent)
                        }
                    }
                }
                val entries=(window.visits.map { TimelineEntry(it.id,it.arrivedAt,visit=it) }+window.sessions.map { TimelineEntry(it.id,it.startedAt,session=it) }+window.inferred.map { TimelineEntry(it.id,it.startedAt,inferred=it) }).sortedBy { it.time }
                if(entries.isEmpty() && isToday && today!=null) item("today-empty") {
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=12.dp).clip(RoundedCornerShape(28.dp)).background(wd.containerLow).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.today_empty_title),style=WdType.title2.copy(fontSize=24.sp,lineHeight=30.sp,fontWeight=FontWeight.Normal),color=wd.textPrimary,modifier=Modifier.semantics {heading()})
                        Text(stringResource(R.string.today_empty_body),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                        Row(Modifier.padding(top=8.dp).heightIn(min=40.dp).clip(CircleShape).background(wd.secondaryContainer).clickable(role=Role.Button,onClick=today.onImport).padding(start=12.dp,end=16.dp),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.FileDownload,null,Modifier.size(18.dp),tint=wd.onSecondaryContainer)
                            Text(stringResource(R.string.today_empty_import),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=wd.onSecondaryContainer)
                        }
                    }
                }
                // The day so far: stays with their ink dot and duration, joined by dotted moves.
                item("today-list-title") {
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=12.dp).clip(RoundedCornerShape(topStart=28.dp,topEnd=28.dp)).background(wd.containerLow).padding(top=12.dp)) {
                        Text(stringResource(when {!isToday->R.string.timeline_redesign_the_day;locationOff->R.string.today_list_title_approximate;else->R.string.today_list_title}).uppercase(),
                            style=WdType.eyebrow.copy(fontWeight=FontWeight.Medium,letterSpacing=0.127.em),color=wd.textMuted,modifier=Modifier.padding(start=20.dp,end=20.dp,top=4.dp,bottom=6.dp).semantics {heading()})
                        if(entries.isEmpty()) Text(stringResource(if(isToday && today!=null) R.string.today_list_empty else R.string.timeline_empty),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted,
                            modifier=Modifier.padding(horizontal=20.dp).padding(top=8.dp,bottom=12.dp).testTag("timeline-empty"))
                    }
                }
                items(entries,key={ if(it.visit!=null) "visit:${it.id}" else "session:${it.id}" }) { entry ->
                    val rowModifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).background(wd.containerLow)
                    val tick=wd.dialTick
                    val visit=entry.visit
                    if(visit!=null) {
                        val live=visit.departedAt==null
                        val inspected=visitInspectionActive && openedVisitId==visit.id
                        Row(rowModifier.background(if(inspected) wd.secondaryContainer else Color.Transparent)
                            .clickable { if(onVisitOpened!=null) { openedVisitId=visit.id; onVisitOpened(visit) } else editing=visit }
                            .heightIn(min=52.dp).padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(journalClock(visit.arrivedAt),style=WdType.caption,color=wd.textMuted,modifier=Modifier.width(JournalClockWidth))
                            Box(Modifier.width(14.dp),contentAlignment=Alignment.Center) {Box(Modifier.size(12.dp).clip(CircleShape).background(wd.placeInks[inkIndex(visit)%wd.placeInks.size]))}
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit),style=WdType.title3.copy(fontWeight=FontWeight.Normal,lineHeight=24.sp),color=wd.textPrimary,maxLines=if(largeText) 3 else 1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                // A quiet status line: still-here for an open stay, then its confirmation (rust while unconfirmed).
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    if(live) Text(stringResource(R.string.visit_still_here),style=WdType.caption2,color=wd.textMuted)
                                    Text(confirmationLabel(visit.confirmation),style=WdType.caption2.copy(fontWeight=if(visit.confirmation==Confirmation.unconfirmed) FontWeight.SemiBold else FontWeight.Normal),
                                        color=if(visit.confirmation==Confirmation.unconfirmed) wd.inkRust else wd.textMuted)
                                }
                            }
                            Text(if(live) stringResource(R.string.timeline_redesign_now) else journalDuration(visit.arrivedAt,visit.departedAt!!),style=WdType.caption.copy(fontWeight=if(live) FontWeight.SemiBold else FontWeight.Normal),
                                color=if(live) wd.inkRust else wd.textMuted)
                        }
                    } else {
                        val session=entry.session ?: entry.inferred!!.let { RecordingSession(it.id,it.startedAt,it.endedAt) }
                        val live=session.endedAt==null
                        val label=session.name?.takeIf {it.isNotBlank()} ?: stringResource(if(entry.inferred!=null) R.string.timeline_inferred else R.string.timeline_outing)
                        val parts=listOfNotNull(label,entry.inferred?.let {distanceText(it.summary.distanceMeters,metric,locale)},
                            if(live) stringResource(R.string.timeline_recording) else journalDuration(session.startedAt,session.endedAt!!))
                        Row(rowModifier.clickable { selectedSession=session.id }.heightIn(min=32.dp).padding(horizontal=20.dp,vertical=2.dp).testTag("today-move-${session.id}"),
                            horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Spacer(Modifier.width(JournalClockWidth))
                            Box(Modifier.width(14.dp).height(28.dp).drawBehind {
                                drawLine(tick,Offset(size.width/2,0f),Offset(size.width/2,size.height),strokeWidth=2.dp.toPx(),cap=StrokeCap.Round,
                                    pathEffect=PathEffect.dashPathEffect(floatArrayOf(0.1f,4.dp.toPx())))
                            })
                            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                // The label stays its own text so the outing's name can be found and read on its own.
                                Text(label,style=WdType.caption2,color=if(live) wd.inkRust else wd.textMuted)
                                Text(parts.drop(1).joinToString(" · ",prefix="· "),style=WdType.caption2,color=if(live) wd.inkRust else wd.textMuted)
                            }
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,stringResource(R.string.timeline_view_outing),Modifier.size(16.dp),tint=wd.textMuted)
                        }
                    }
                }
                item("today-list-end") {Spacer(Modifier.fillMaxWidth().padding(horizontal=16.dp).height(12.dp).clip(RoundedCornerShape(bottomStart=28.dp,bottomEnd=28.dp)).background(wd.containerLow))}
                if(today?.onMoments!=null) item("today-moments") {
                    val shape=RoundedCornerShape(28.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=12.dp).clip(shape).border(1.dp,wd.border,shape).clickable(role=Role.Button,onClick=today.onMoments)
                        .padding(start=20.dp,end=16.dp,top=12.dp,bottom=12.dp).testTag("today-moments"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.today_moments_title),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium),color=wd.textPrimary)
                            Text(stringResource(R.string.today_moments_body),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(24.dp),tint=wd.textPrimary)
                    }
                }
            }
        }
    }
    if(today!=null && !logStopInHeader && !railLogStop) WdExtendedFab(stringResource(R.string.today_log_stop),Icons.Outlined.AddLocationAlt,{ adding=true },
        Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("today-log-stop"))
    }
    if(picking) {
        val state=rememberDatePickerState(initialSelectedDateMillis=day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates=object:SelectableDates {override fun isSelectableDate(utcTimeMillis:Long)=utcTimeMillis<=LocalDate.now().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()})
        DatePickerDialog(onDismissRequest={picking=false},confirmButton={TextButton({
            state.selectedDateMillis?.let {model.timelineDay.value=java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()};picking=false
        }) {Text(stringResource(R.string.today_pick_day_confirm))}},dismissButton={TextButton({picking=false}) {Text(stringResource(R.string.today_pick_day_cancel))}},
            colors=DatePickerDefaults.colors(containerColor=wd.containerHigh)) {
            DatePicker(state,colors=DatePickerDefaults.colors(containerColor=wd.containerHigh,selectedDayContainerColor=wd.accent,selectedDayContentColor=wd.surface,todayDateBorderColor=wd.accent,todayContentColor=wd.accent))
        }
    }
    if(adding || editing!=null) {
        val expected=editing
        VisitEditor(graph,expected,{adding=false;editing=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,expected,reuse,remember,overlap)},expected?.let {value->{graph.history.deleteVisit(value)}})
    }
}
private data class TimelineEntry(val id: String,val time: Instant,val visit: Visit?=null,val session: RecordingSession?=null,val inferred: InferredOutingHeader?=null)

private val JournalClockWidth=48.dp

/** Moved distance as the Today hero shows it: one decimal, km or mi. */
internal fun todayDistance(meters:Double,metric:Boolean,locale:Locale):String {
    val format=java.text.NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=1;maximumFractionDigits=1}
    return format.format(meters/if(metric) 1000.0 else 1609.344)+if(metric) " km" else " mi"
}

/** What the Today tab adds around the shared day journal: recording, access, navigation and the log-a-stop entry. */
internal data class TodayChrome(val active:Boolean,val recording:RecordingRow?,val onRecord:()->Unit,val access:RecordingAccessState?,val onAccessOptions:(PermissionAccessKind)->Unit,
    val onOpenMap:()->Unit,val onSettings:()->Unit,val onImport:()->Unit,val onMoments:(()->Unit)?,val showLogStopFab:Boolean=true,val logStopRequests:Int=0,val onListening:(()->Unit)?=null)
