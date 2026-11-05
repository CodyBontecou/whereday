package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.ReplayArchive
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Trace sheet says about one moment of the day. Pure, so the stay/move/later rules are unit tested. */
internal sealed interface TraceMoment {
    data class Staying(val name:String?):TraceMoment
    data object Moving:TraceMoment
    data object Later:TraceMoment
    companion object {
        fun at(time:Instant,now:Instant?,visits:List<Visit>):TraceMoment {
            if(now!=null && time.isAfter(now)) return Later
            val stay=visits.lastOrNull {!time.isBefore(it.arrivedAt) && (it.departedAt==null || !time.isAfter(it.departedAt))}
            return if(stay!=null) Staying(stay.name?.takeIf {it.isNotBlank()}) else Moving
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** Trace: the day's route edge to edge, with the Material 3 bottom sheet that scrubs it by clock time and jumps between places. */
@Composable internal fun TraceScreen(graph:AppGraph,model:JournalViewModel,metric:Boolean,onBack:(()->Unit)?,backLabel:String,onOpenMap:(()->Unit)?) {
    val day by model.timelineDay.collectAsStateWithLifecycle()
    val state by model.timelineWindow.collectAsStateWithLifecycle()
    val zone=ZoneId.systemDefault()
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val dayStart=day.atStartOfDay(zone).toInstant()
    val isToday=day==LocalDate.now()
    var now by remember {mutableStateOf(Instant.now())}
    LaunchedEffect(isToday) {while(isToday) {kotlinx.coroutines.delay(30_000);now=Instant.now()}}
    val limitMinute=if(isToday) (Duration.between(dayStart,now).toMinutes().toInt()).coerceIn(0,1440) else 1440
    var minute by rememberSaveable(day) {mutableIntStateOf(limitMinute)}
    val window=(state as? WindowState.Ready)?.window
    val visits=window?.visits.orEmpty().sortedBy {it.arrivedAt}
    // The archive is the same disk-backed replay cache the route replay uses; it reloads when the day's points change.
    var archive by remember {mutableStateOf<ReplayArchive?>(null)}
    var frame by remember {mutableStateOf<ReplayFrame?>(null)}
    val points=window?.summary?.count ?: 0
    // Re-prepare in steps of new points, not on every fix while recording.
    LaunchedEffect(day,points>0,points/50) {
        var owned:ReplayArchive?=null
        try {
            owned=if(points>=1) graph.replay.prepare(ReplayRequest.Range(HistoryRange.day(day,zone))) else null
            archive=owned
            if(owned==null) frame=null
            awaitCancellation()
        } catch(e:CancellationException) {throw e} catch(_:Exception) {archive=null;frame=null}
        finally {
            if(owned!=null) {if(archive===owned) {archive=null};withContext(NonCancellable) {owned.close()}}
        }
    }
    val time=dayStart.plus(Duration.ofMinutes(minute.toLong()))
    LaunchedEffect(archive,minute) {
        val source=archive ?: return@LaunchedEffect
        try {frame=source.frame(source.indexAt(minOf(time,if(isToday) now else time)))}
        catch(e:CancellationException) {throw e} catch(_:Exception) {frame=null}
    }
    val inks=journalInkIndex(visits.map {it.name?.takeIf {n->n.isNotBlank()} ?: it.id})
    val placeInks=wd.placeInks
    fun ink(visit:Visit)=placeInks[(inks[visit.name?.takeIf {it.isNotBlank()} ?: visit.id] ?: 0)%placeInks.size]
    fun minuteOf(at:Instant)=Duration.between(dayStart,at).toMinutes().toInt().coerceIn(0,1440)
    val clock=DateTimeFormatter.ofPattern("HH:mm")
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("trace-screen")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val shown=frame
            if(shown!=null) JournalMap(shown.visiblePoints,archive?.visits ?: visits,Modifier.fillMaxSize(),replay=shown,endpoints=archive?.endpoints ?: RouteEndpoints(),
                followMetric=metric,roadController=graph.roadDisplay,fullBleed=true)
            else Box(Modifier.fillMaxSize().background(wd.dialTrack),contentAlignment=Alignment.Center) {
                if(state is WindowState.Loading || (points>0 && archive==null)) WdLoading()
                else Text(stringResource(R.string.trace_empty),Modifier.padding(32.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(onBack!=null) WdBackChip(backLabel,onBack,Modifier.testTag("trace-back"))
                Spacer(Modifier.weight(1f))
            }
        }
        // The replay sheet: kicker and serif label beside a large clock, the stay-inked scrubber, place chips and the running distance.
        val sheet=RoundedCornerShape(topStart=28.dp,topEnd=28.dp)
        Column(Modifier.fillMaxWidth().shadow(6.dp,sheet).clip(sheet).background(wd.surface).navigationBarsPadding().padding(start=24.dp,end=24.dp,bottom=16.dp)
            .testTag("trace-sheet"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top=16.dp,bottom=2.dp).size(32.dp,4.dp).clip(CircleShape).background(wd.outline.copy(alpha=0.4f)))
            val moment=TraceMoment.at(time,if(isToday) now else null,visits)
            val (kicker,label)=when(moment) {
                TraceMoment.Later->stringResource(R.string.trace_later) to stringResource(R.string.trace_not_lived)
                TraceMoment.Moving->stringResource(R.string.trace_moving) to stringResource(R.string.trace_between)
                is TraceMoment.Staying->stringResource(R.string.trace_staying) to (moment.name ?: stringResource(R.string.timeline_visit))
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(kicker.uppercase(),style=WdType.caption2.copy(letterSpacing=0.127.em),color=wd.textMuted)
                    Text(label,Modifier.testTag("trace-label"),style=WdType.title.copy(fontWeight=FontWeight.Normal),color=wd.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Text(time.atZone(zone).format(clock),Modifier.testTag("trace-clock"),style=WdType.title.copy(fontWeight=FontWeight.Normal,fontSize=45.sp,lineHeight=45.sp,fontFeatureSettings="tnum"),color=wd.textPrimary)
            }
            // Stays inked along the 24-hour track under the slider.
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp).align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(wd.containerHigh)) {
                    val width=maxWidth
                    visits.forEach {visit->
                        val start=minuteOf(maxOf(visit.arrivedAt,dayStart));val end=minuteOf(visit.departedAt ?: now)
                        Box(Modifier.offset(x=width*(start/1440f)).width((width*((end-start).coerceAtLeast(4)/1440f))).fillMaxHeight().background(ink(visit)))
                    }
                }
                val state=stringResource(R.string.trace_scrub_state,time.atZone(zone).format(clock),label)
                val scrubLabel=stringResource(R.string.trace_scrub)
                Slider(minute.toFloat(),{minute=it.toInt().coerceIn(0,limitMinute)},Modifier.fillMaxWidth().testTag("trace-scrubber").semantics {contentDescription=scrubLabel;stateDescription=state},
                    valueRange=0f..1440f,colors=SliderDefaults.colors(thumbColor=wd.inkRust,activeTrackColor=Color.Transparent,inactiveTrackColor=Color.Transparent),
                    thumb={Box(Modifier.size(4.dp,44.dp).clip(RoundedCornerShape(2.dp)).background(wd.inkRust))})
            }
            Row(Modifier.fillMaxWidth().offset(y=(-8).dp),horizontalArrangement=Arrangement.SpaceBetween) {
                listOf("00","06","12","18","24").forEach {Text(it,style=WdType.caption2.copy(fontSize=10.sp),color=wd.textMuted)}
            }
            if(visits.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("trace-jumps"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                val seen=LinkedHashMap<String,Visit>()
                visits.forEach {seen.getOrPut(it.name?.takeIf {n->n.isNotBlank()} ?: it.id) {it}}
                seen.values.forEach {visit->
                    val start=minuteOf(maxOf(visit.arrivedAt,dayStart));val end=minuteOf(visit.departedAt ?: now)
                    val target=((start+end)/2).coerceAtMost(limitMinute)
                    val on=minute in start..end
                    val shape=RoundedCornerShape(8.dp)
                    Row(Modifier.heightIn(min=32.dp).clip(shape).background(if(on) wd.accent else Color.Transparent).then(if(on) Modifier else Modifier.border(1.dp,wd.outline,shape))
                        .selectable(on,role=Role.Button) {minute=target}.padding(start=12.dp,end=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if(on) wd.surface else ink(visit)))
                        Text(visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp,fontWeight=FontWeight.Medium),
                            color=if(on) wd.surface else wd.textPrimary,maxLines=1)
                    }
                }
            }
            WdDivider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(stringResource(R.string.trace_so_far,todayDistance(frame?.distanceMeters ?: 0.0,metric,locale)),Modifier.testTag("trace-distance"),style=WdType.caption2,color=wd.textMuted)
                if(isToday) Text(stringResource(R.string.trace_drag_hint,now.atZone(zone).format(clock)),style=WdType.caption2,color=wd.textMuted)
            }
            if(onOpenMap!=null) Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clip(RoundedCornerShape(20.dp)).selectable(false,role=Role.Button,onClick=onOpenMap).testTag("trace-open-map"),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally)) {
                Icon(Icons.Outlined.Layers,null,Modifier.size(18.dp),tint=wd.accent)
                Text(stringResource(R.string.trace_open_map),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=wd.accent)
            }
        }
    }
}
