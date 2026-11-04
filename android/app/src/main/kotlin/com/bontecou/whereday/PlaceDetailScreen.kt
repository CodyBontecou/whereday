package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.domain.SavedPlace
import com.bontecou.whereday.domain.Visit
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/** The iOS PlaceRhythm, ported: visit count, median stay, time this year and seconds present per weekday × hour. */
internal class PlaceRhythm(visits:List<Visit>,now:Instant,private val zone:ZoneId) {
    val visitCount=visits.size
    val typicalStay:Duration?
    val timeThisYear:Duration
    /** [weekday 1..7 (ISO, Monday=1)][hour 0..23] seconds. */
    private val cells=Array(8) {LongArray(24)}
    private val maximum:Long
    enum class Part {mornings,midday,afternoons,evenings,nights}
    enum class Leaning {weekday,weekend,any}
    init {
        val durations=visits.mapNotNull {v->v.departedAt?.takeIf {it>v.arrivedAt}?.let {Duration.between(v.arrivedAt,it)}}.sorted()
        typicalStay=durations.getOrNull(durations.size/2)
        val yearStart=now.atZone(zone).toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant()
        var year=0L
        for(visit in visits) {
            val end=minOf(visit.departedAt ?: now,now)
            if(!end.isAfter(visit.arrivedAt)) continue
            year+=maxOf(0,Duration.between(maxOf(visit.arrivedAt,yearStart),end).seconds)
            // A week is enough to show the pattern without walking a stale open visit hour by hour.
            val rhythmEnd=minOf(end,visit.arrivedAt.plus(Duration.ofDays(7)))
            var cursor=visit.arrivedAt
            while(cursor<rhythmEnd) {
                val local=cursor.atZone(zone)
                val hourEnd=local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
                val segmentEnd=minOf(hourEnd,rhythmEnd)
                cells[local.dayOfWeek.value][local.hour]+=Duration.between(cursor,segmentEnd).seconds
                cursor=segmentEnd
            }
        }
        timeThisYear=Duration.ofSeconds(year)
        maximum=cells.maxOf {it.maxOrNull() ?: 0}
    }
    /** 0 means never; 1–3 are thirds of the busiest hour. */
    fun level(weekday:Int,hour:Int):Int {
        val value=cells[weekday][hour]
        if(value<=0 || maximum<=0) return 0
        return minOf(3,((value.toDouble()/maximum)*3).toInt()+1)
    }
    private val peak:Pair<Int,Int>? get() {
        if(maximum<=0) return null
        for(day in 1..7) for(hour in 0..23) if(cells[day][hour]==maximum) return day to hour
        return null
    }
    /** An honest one-line read of the pattern, e.g. mostly weekday evenings; null below three visits. */
    val summary:Pair<Leaning,Part>? get() {
        val (_,hour)=peak ?: return null
        if(visitCount<3) return null
        val total=cells.sumOf {it.sum()}
        val weekend=cells[6].sum()+cells[7].sum()
        val part=when(hour) {in 5..10->Part.mornings;in 11..13->Part.midday;in 14..16->Part.afternoons;in 17..21->Part.evenings;else->Part.nights}
        val leaning=when {total>0 && weekend.toDouble()/total>=0.6->Leaning.weekend;total>0 && weekend.toDouble()/total<=0.15->Leaning.weekday;else->Leaning.any}
        return leaning to part
    }
    companion object {
        /** Visits whose position lies inside the place's match radius. */
        fun visitsFor(place:SavedPlace,visits:List<Visit>)=visits.filter {it.coordinate.distanceTo(place.coordinate)<=place.radius}
        fun duration(value:Duration):String {
            val minutes=maxOf(0,value.toMinutes())
            if(minutes<60) return "${minutes}m"
            val hours=minutes/60
            if(hours>=48) return "${hours/24}d ${hours%24}h"
            return if(minutes%60==0L) "${hours}h" else "${hours}h ${minutes%60}m"
        }
        fun hours(value:Duration)=if(value<Duration.ofHours(1)) duration(value) else "${value.toHours()}h"
    }
}

/** The Place board (iOS PlaceDetailView): map, name, actions, the three figures, the weekday × hour rhythm and recent visits. */
@Composable internal fun PlaceDetailScreen(graph:AppGraph,place:SavedPlace,back:()->Unit,edit:()->Unit,openVisit:(Visit)->Unit) {
    BackHandler(onBack=back)
    val zone=ZoneId.systemDefault()
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var visits by remember {mutableStateOf<List<Visit>?>(null)}
    LaunchedEffect(place) {
        visits=runCatching {withContext(Dispatchers.IO) {
            PlaceRhythm.visitsFor(place,graph.history.dao.rangeVisits(Instant.EPOCH,Instant.now().plusSeconds(1)).map {it.value})
        }}.getOrDefault(emptyList())
    }
    val now=remember {Instant.now()}
    val rhythm=remember(visits) {visits?.let {PlaceRhythm(it,now,zone)}}
    val ink=placeInk(place.id)
    Column(Modifier.fillMaxSize().background(wd.surface).verticalScroll(rememberScrollState()).semantics {testTagsAsResourceId=true}.testTag("place-detail")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            WdBackChip(stringResource(R.string.shell_places),back,Modifier.testTag("place-detail-back"))
            Spacer(Modifier.weight(1f))
            WdExtendedFab(stringResource(R.string.place_detail_edit),Icons.Outlined.Edit,edit,Modifier.testTag("place-detail-edit"))
        }
        // A still sketch of the match radius in the place's ink; the journal map shows the real streets.
        PlaceRadiusSketch(ink,place.radius,Modifier.padding(horizontal=16.dp).fillMaxWidth().height(180.dp))
        Column(Modifier.padding(start=20.dp,end=20.dp,top=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.place_detail_kind).uppercase(),style=WdType.caption2.copy(letterSpacing=0.127.em),color=wd.textMuted)
            Text(place.name,style=WdType.pageTitle.copy(fontSize=34.sp,lineHeight=40.sp),color=wd.textPrimary,modifier=Modifier.semantics {heading()})
            place.address?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
            Text(stringResource(R.string.place_detail_radius,place.radius.toInt()),style=WdType.caption2,color=wd.textMuted)
        }
        val value=rhythm
        if(value==null) WdLoading() else {
            Row(Modifier.padding(horizontal=16.dp).padding(top=16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(wd.containerLow).padding(horizontal=20.dp,vertical=16.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PlaceFigure(java.text.NumberFormat.getIntegerInstance(locale).format(value.visitCount),stringResource(R.string.place_detail_visits),Modifier.weight(1f))
                PlaceFigure(value.typicalStay?.let {PlaceRhythm.duration(it)} ?: "—",stringResource(R.string.place_detail_typical),Modifier.weight(1f))
                PlaceFigure(PlaceRhythm.hours(value.timeThisYear),stringResource(R.string.place_detail_this_year,LocalDate.now(zone).year),Modifier.weight(1f))
            }
            Column(Modifier.padding(horizontal=16.dp).padding(top=12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(wd.containerLow).padding(horizontal=20.dp,vertical=16.dp).testTag("place-rhythm"),
                verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.Bottom) {
                    Text(stringResource(R.string.place_detail_rhythm).uppercase(),Modifier.weight(1f),style=WdType.eyebrow,color=wd.textMuted)
                    value.summary?.let {(leaning,part)->Text(rhythmSummary(leaning,part),style=WdType.serifBody.copy(fontStyle=FontStyle.Italic),color=wd.textPrimary)}
                }
                val firstDay=WeekFields.of(locale).firstDayOfWeek
                val hours=7..22
                val dots=wd.dialTick.copy(alpha=0.6f)
                (0 until 7).forEach {offset->
                    val day=firstDay.plus(offset.toLong())
                    Row(Modifier.fillMaxWidth().height(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(day.getDisplayName(TextStyle.NARROW_STANDALONE,locale),Modifier.width(22.dp),style=WdType.caption2.copy(fontSize=10.sp),color=wd.textMuted)
                        Canvas(Modifier.weight(1f).fillMaxHeight()) {
                            val step=size.width/hours.count()
                            hours.forEachIndexed {i,hour->
                                val level=value.level(day.value,hour)
                                val radius=floatArrayOf(1.5f,3f,4.5f,6f)[level]*density
                                drawCircle(if(level==0) dots else ink,radius,androidx.compose.ui.geometry.Offset(step*i+step/2,size.height/2))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start=22.dp,top=4.dp)) {
                    hours.forEach {hour->Text(if(hour in setOf(7,11,15,19,22)) "$hour" else "",Modifier.weight(1f),style=WdType.caption2.copy(fontSize=10.sp),color=wd.textMuted)}
                }
            }
            val recent=visits.orEmpty().sortedByDescending {it.arrivedAt}.take(5)
            Column(Modifier.padding(horizontal=16.dp).padding(top=12.dp,bottom=24.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(wd.containerLow).padding(vertical=8.dp)) {
                Text(stringResource(R.string.place_detail_recent).uppercase(),Modifier.padding(horizontal=20.dp,vertical=8.dp),style=WdType.eyebrow,color=wd.textMuted)
                if(recent.isEmpty()) Text(stringResource(R.string.place_detail_no_visits),Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                recent.forEachIndexed {index,visit->
                    if(index>0) WdDivider(Modifier.padding(start=20.dp))
                    Row(Modifier.fillMaxWidth().clickable(role=Role.Button) {openVisit(visit)}.heightIn(min=56.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(visitRange(visit,zone,locale),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                        Text(visit.departedAt?.let {PlaceRhythm.duration(Duration.between(visit.arrivedAt,it))} ?: stringResource(R.string.timeline_redesign_now),style=WdType.caption,color=wd.textMuted)
                    }
                }
            }
        }
    }
}

@Composable private fun PlaceFigure(value:String,label:String,modifier:Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(value,style=WdType.figure.copy(fontSize=28.sp),color=wd.textPrimary,maxLines=1)
        Text(label.uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.1.em),color=wd.textMuted,maxLines=2)
    }
}

@Composable private fun rhythmSummary(leaning:PlaceRhythm.Leaning,part:PlaceRhythm.Part):String {
    val partText=stringResource(when(part) {
        PlaceRhythm.Part.mornings->R.string.place_rhythm_mornings;PlaceRhythm.Part.midday->R.string.place_rhythm_midday
        PlaceRhythm.Part.afternoons->R.string.place_rhythm_afternoons;PlaceRhythm.Part.evenings->R.string.place_rhythm_evenings
        PlaceRhythm.Part.nights->R.string.place_rhythm_nights
    })
    return stringResource(when(leaning) {PlaceRhythm.Leaning.weekday->R.string.place_rhythm_weekday;PlaceRhythm.Leaning.weekend->R.string.place_rhythm_weekend;else->R.string.place_rhythm_any},partText)
}

private fun visitRange(visit:Visit,zone:ZoneId,locale:Locale):String {
    val day=visit.arrivedAt.atZone(zone).toLocalDate()
    val dayLabel=day.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"EEEMMMd"),locale))
    val time=DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    return visit.departedAt?.let {"$dayLabel · ${time.format(visit.arrivedAt)} – ${time.format(it)}"} ?: "$dayLabel · ${time.format(visit.arrivedAt)}"
}

/** The board's paper map card: a faint street grid with the match radius ring and centre pin in the place's ink. */
@Composable private fun PlaceRadiusSketch(ink:Color,radius:Double,modifier:Modifier) {
    val street=wd.border;val paper=wd.containerHigh;val surface=wd.surface
    val description=stringResource(R.string.place_detail_map_description,radius.toInt())
    Canvas(modifier.clip(RoundedCornerShape(28.dp)).background(paper).semantics {contentDescription=description}) {
        val step=size.width/6
        for(i in 1..6) {
            drawLine(street,androidx.compose.ui.geometry.Offset(step*i,0f),androidx.compose.ui.geometry.Offset(step*i,size.height),1.2f*density)
            drawLine(street,androidx.compose.ui.geometry.Offset(0f,size.height/4*i),androidx.compose.ui.geometry.Offset(size.width,size.height/4*i),1.2f*density)
        }
        val ring=(size.minDimension*0.18f+(radius/300.0).coerceIn(0.0,1.0).toFloat()*size.minDimension*0.18f)
        drawCircle(ink.copy(alpha=0.14f),ring,center)
        drawCircle(ink,ring,center,style=androidx.compose.ui.graphics.drawscope.Stroke(1.5f*density,pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f*density,4f*density))))
        drawCircle(surface,13f*density,center)
        drawCircle(ink,10f*density,center)
    }
}
