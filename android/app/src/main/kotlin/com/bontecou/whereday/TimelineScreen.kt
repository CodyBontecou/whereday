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

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TimelineScreen(graph: AppGraph, model: JournalViewModel, metric: Boolean, showOnMap: (RecordingSession) -> Unit,onVisitOpened:((Visit)->Unit)?=null,visitInspectionActive:Boolean=false) {
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
    LazyColumn(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("timeline-content"),contentPadding=PaddingValues(bottom=28.dp)) {
        item { WdPageHeader(stringResource(R.string.shell_timeline),eyebrow=eyebrow) {
            TimelineCapsule(stringResource(R.string.map_add_past),{ adding=true },icon=Icons.Outlined.Add)
        } }
        item { Row(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            WdCircleButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,stringResource(R.string.timeline_previous),{ model.timelineDay.value=day.minusDays(1) },Modifier.testTag("timeline-previous"))
            val isToday=day==LocalDate.now()
            TimelineCapsule(formattedDay,{ model.timelineDay.value=LocalDate.now() },Modifier.weight(1f).testTag("timeline-today").semantics {contentDescription=todayHint},
                ink=if(isToday) wd.textMuted else wd.inkRust,fill=true)
            WdCircleButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight,stringResource(R.string.timeline_next),{ model.timelineDay.value=day.plusDays(1) },Modifier.testTag("timeline-next"))
        } }
        when(val current=state) {
            WindowState.Loading -> item { WdLoading(Modifier.padding(top=24.dp)) }
            is WindowState.Failed -> item { WdNotice(current.message,Modifier.padding(horizontal=20.dp).padding(top=22.dp),icon=Icons.Outlined.ErrorOutline,warning=true,action={
                TextButton(onClick=model::retry,colors=ButtonDefaults.textButtonColors(contentColor=wd.inkRust)) { Text(stringResource(R.string.map_retry_history),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold)) }
            }) }
            is WindowState.Ready -> {
                val window=current.window
                item {
                    val outings=window.sessions.size+window.inferred.size
                    val visits=window.visits.size
                    Text(stringResource(R.string.timeline_summary,resources.getQuantityString(R.plurals.timeline_outing_count,outings,outings),resources.getQuantityString(R.plurals.timeline_visit_count,visits,visits),distanceText(window.summary.distanceMeters,metric,locale)),
                        style=WdType.caption.copy(fontWeight=FontWeight.Medium),color=wd.textMuted,modifier=Modifier.padding(horizontal=24.dp).padding(top=18.dp).testTag("timeline-summary"))
                }
                item { Text(stringResource(R.string.timeline_redesign_the_day).uppercase(),style=WdType.eyebrow,color=wd.textMuted,
                    modifier=Modifier.padding(horizontal=24.dp).padding(top=22.dp,bottom=10.dp).semantics {heading()}) }
                val chronological=(window.sessions.map {it.id to it.startedAt}+window.inferred.map {it.id to it.startedAt}).sortedWith(compareBy({it.second},{it.first}))
                val sequences=chronological.mapIndexed {index,entry->entry.first to index+1}.toMap()
                val entries=(window.visits.map { TimelineEntry(it.id,it.arrivedAt,visit=it) }+window.sessions.map { TimelineEntry(it.id,it.startedAt,session=it) }+window.inferred.map { TimelineEntry(it.id,it.startedAt,inferred=it) }).sortedByDescending { it.time }
                val inks=journalInkIndex(window.visits.sortedBy {it.arrivedAt}.map {it.name?.takeIf {n->n.isNotBlank()} ?: it.id})
                // Like iOS at accessibility sizes, large text drops the dial so the stays stay within reach.
                if(!largeText) item("day-dial") {
                    // The iOS Today dial: each stay inked around the 24-hour ring, with the coral "now" marker on today.
                    val zone=java.time.ZoneId.systemDefault()
                    val dayStart=day.atStartOfDay(zone).toInstant()
                    fun fraction(at:java.time.Instant)=(java.time.Duration.between(dayStart,at).toMillis()/86_400_000f).coerceIn(0f,1f)
                    val nowInstant=java.time.Instant.now()
                    val isToday=day==LocalDate.now()
                    val segments=window.sessions.map {WdDialSegment(fraction(it.startedAt),fraction(it.endedAt ?: nowInstant),wd.textMuted.copy(alpha=0.45f))}+
                        window.visits.map {visit->WdDialSegment(fraction(visit.arrivedAt),fraction(visit.departedAt ?: nowInstant),
                            wd.placeInks[(inks[visit.name?.takeIf {it.isNotBlank()} ?: visit.id] ?: 0)%wd.placeInks.size])}
                    Box(Modifier.fillMaxWidth().padding(top=6.dp,bottom=14.dp),contentAlignment=Alignment.Center) {
                        WdDayDial(segments,now=if(isToday) fraction(nowInstant) else null,nowCaption=if(isToday) stringResource(R.string.timeline_redesign_now) else null,
                            centerText=if(isToday) nowInstant.atZone(zone).format(DateTimeFormatter.ofPattern(if(android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm")) else null)
                    }
                }
                if(entries.isEmpty()) item { Text(stringResource(R.string.timeline_empty),style=WdType.serifBody,color=wd.textMuted,modifier=Modifier.padding(horizontal=24.dp).testTag("timeline-empty")) }
                items(entries,key={ if(it.visit!=null) "visit:${it.id}" else "session:${it.id}" }) { entry ->
                    val visit=entry.visit
                    if(visit!=null) {
                        val ink=wd.placeInks[(inks[visit.name?.takeIf {it.isNotBlank()} ?: visit.id] ?: 0)%wd.placeInks.size]
                        val ongoing=visit.departedAt==null
                        val inspected=visitInspectionActive && openedVisitId==visit.id
                        val fg=if(ongoing) wd.onInkCard else wd.textPrimary
                        val muted=if(ongoing) wd.onInkCardMuted else wd.textMuted
                        val shape=RoundedCornerShape(if(ongoing) 18.dp else 14.dp)
                        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).journalRule(wd.border,dotted=false)
                            .clip(shape).background(when {ongoing->wd.inkCard; inspected->wd.accent.copy(alpha=if(wd.dark) 0.16f else 0.08f); else->Color.Transparent})
                            .clickable { if(onVisitOpened!=null) { openedVisitId=visit.id; onVisitOpened(visit) } else editing=visit }
                            .padding(horizontal=12.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            CompositionLocalProvider(LocalContentColor provides fg) {
                                Text(journalClock(visit.arrivedAt),style=WdType.caption,color=muted,modifier=Modifier.width(JournalClockWidth).padding(top=3.dp))
                                Box(Modifier.width(14.dp).padding(top=2.dp),contentAlignment=Alignment.Center) {
                                    if(ongoing) WdLiveDot(Modifier.requiredSize(20.dp)) else Box(Modifier.size(12.dp).clip(CircleShape).background(ink))
                                }
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit),style=WdType.title3,color=fg)
                                    Text(timelineRange(visit.arrivedAt,visit.departedAt,R.string.visit_still_here),style=WdType.caption2,color=muted)
                                    visit.address?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=muted) }
                                    visit.notes?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=fg) }
                                    Text(confirmationLabel(visit.confirmation),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.1.em),
                                        color=if(visit.confirmation==Confirmation.unconfirmed) (if(ongoing) wd.liveDot else wd.inkRust) else muted)
                                    val unknownTitle=stringResource(R.string.calendar_unknown_place)
                                    CalendarEventButton(visit,makeDraft={now->CalendarEventDraft.visit(visit,unknownTitle,now)})
                                }
                                Text(if(ongoing) stringResource(R.string.timeline_redesign_now) else journalDuration(visit.arrivedAt,visit.departedAt!!),style=WdType.caption,
                                    color=if(ongoing) wd.liveDot else muted,modifier=Modifier.padding(top=3.dp))
                            }
                        }
                    } else {
                        val session=entry.session ?: entry.inferred!!.let { RecordingSession(it.id,it.startedAt,it.endedAt) }
                        val live=session.endedAt==null
                        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).journalRule(wd.dialTick,dotted=true).padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(journalClock(session.startedAt),style=WdType.caption2,color=wd.textMuted.copy(alpha=0.8f),modifier=Modifier.width(JournalClockWidth).padding(top=16.dp))
                            Spacer(Modifier.width(14.dp))
                            val card=RoundedCornerShape(18.dp)
                            Column(Modifier.weight(1f).clip(card).background(wd.card).border(1.dp,if(live) wd.inkRust.copy(alpha=0.5f) else wd.border,card).clickable { selectedSession=session.id }
                                .padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text(session.name?.takeIf {it.isNotBlank()} ?: stringResource(if(entry.inferred!=null) R.string.timeline_inferred else R.string.timeline_outing),style=WdType.title3,color=wd.textPrimary,modifier=Modifier.weight(1f,fill=false))
                                    if(live) WdBadge(stringResource(R.string.timeline_recording),color=wd.inkRust)
                                }
                                Text(timelineRange(session.startedAt,session.endedAt,R.string.timeline_recording),style=WdType.caption2,color=if(live) wd.inkRust else wd.textMuted)
                                entry.inferred?.let { Text(stringResource(R.string.timeline_point_summary,resources.getQuantityString(R.plurals.timeline_point_count,it.summary.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),it.summary.count),distanceText(it.summary.distanceMeters,metric,locale)),style=WdType.caption,color=wd.textMuted) }
                                session.notes?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary) }
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(R.string.timeline_view_outing),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.1.em),color=wd.inkRust)
                                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(14.dp),tint=wd.inkRust)
                                }
                                val calendarTitle=stringResource(R.string.calendar_outing_title,sequences[session.id] ?: 1)
                                CalendarEventButton(session,makeDraft={now->
                                    val detail=entry.inferred?.let {graph.history.inferredDetail(it,displayLimit=2)} ?: graph.history.outingDetail(session.id,displayLimit=2)
                                    CalendarEventDraft.outing(detail.session,detail.endpoints.first?.coordinate,calendarTitle,now)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    if(adding || editing!=null) {
        val expected=editing
        VisitEditor(graph,expected,{adding=false;editing=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,expected,reuse,remember,overlap)},expected?.let {value->{graph.history.deleteVisit(value)}})
    }
}
private data class TimelineEntry(val id: String,val time: Instant,val visit: Visit?=null,val session: RecordingSession?=null,val inferred: InferredOutingHeader?=null)

private val JournalClockWidth=48.dp

/** The journal's connecting rule through the ink gutter: solid between stays, dotted for moves (iOS TimelineDottedLine). */
private fun Modifier.journalRule(color:Color,dotted:Boolean)=drawBehind {
    val x=12.dp.toPx()+JournalClockWidth.toPx()+10.dp.toPx()+7.dp.toPx()
    drawLine(color,Offset(x,0f),Offset(x,size.height),strokeWidth=if(dotted) 2.dp.toPx() else 1.dp.toPx(),cap=if(dotted) StrokeCap.Round else StrokeCap.Butt,
        pathEffect=if(dotted) PathEffect.dashPathEffect(floatArrayOf(0.1f,4.dp.toPx())) else null)
}

/** A paper capsule with mono label, as the iOS day-reset and header actions. */
@Composable private fun TimelineCapsule(text:String,onClick:()->Unit,modifier:Modifier=Modifier,icon:ImageVector?=null,ink:Color=wd.textPrimary,fill:Boolean=false) {
    Row(modifier.heightIn(min=44.dp).clip(CircleShape).background(wd.card).border(1.dp,wd.border,CircleShape).clickable(role=Role.Button,onClick=onClick)
        .padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp,if(fill) Alignment.CenterHorizontally else Alignment.Start)) {
        if(icon!=null) Icon(icon,null,Modifier.size(16.dp),tint=ink)
        Text(text,style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.08.em),color=ink,maxLines=2,textAlign=TextAlign.Center)
    }
}

