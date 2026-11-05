package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.domain.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The pieces of Today (iOS TodayView) that Timeline and the map's day sheet share: the dial, the stats and "the day so far" rows.

/** A place's ink on the dial and the map, in order of first arrival. */
@Composable @ReadOnlyComposable internal fun dayInk(index:Int):Color = wd.placeInks[index%wd.placeInks.size]

/** The 24-hour dial's arcs for a day story: one place-ink arc per stay. */
@Composable internal fun dayDialSegments(story:DayStory):List<WdDialSegment> = story.segments.map {WdDialSegment(it.start.toFloat(),it.end.toFloat(),dayInk(it.ink))}

/** Where now falls on today's dial, or null on any other day. */
internal fun dayNowFraction(story:DayStory,now:Instant,zone:ZoneId=ZoneId.systemDefault()):Float? {
    val start=story.day.atStartOfDay(zone).toInstant();val end=story.day.plusDays(1).atStartOfDay(zone).toInstant()
    if(now<start || now>=end) return null
    return (Duration.between(start,now).toMillis()/Duration.between(start,end).toMillis().toFloat()).coerceIn(0f,1f)
}

/** The 24-hour clock the dial and the day rows read, whatever the system setting (as on iOS). */
internal val dayClock:DateTimeFormatter=DateTimeFormatter.ofPattern("HH:mm")
internal fun dayClockText(instant:Instant):String=dayClock.withZone(ZoneId.systemDefault()).format(instant)

/** Distance as iOS DistanceFormatter: one decimal kilometre (or mile), whole metres (feet) below that. */
internal fun dayDistance(meters:Double,metric:Boolean,locale:Locale):String = if(metric) {
    if(meters>=1000) String.format(locale,"%.1f km",meters/1000) else String.format(locale,"%.0f m",meters)
} else {
    val miles=meters/1609.344
    if(miles<0.1) String.format(locale,"%.0f ft",meters*3.28084) else String.format(locale,"%.1f mi",miles)
}

/** "8h 12m" / "45m", as Today's stay durations. */
@Composable internal fun dayStayDuration(duration:Duration):String {
    val minutes=duration.toMinutes().coerceAtLeast(0)
    return if(minutes<60) stringResource(R.string.timeline_redesign_minutes,minutes.toInt())
    else stringResource(R.string.timeline_redesign_hours,(minutes/60).toInt(),"%02d".format((minutes%60).toInt()))
}

@Composable internal fun travelModeLabel(mode:TravelMode):String = stringResource(when(mode) {
    TravelMode.walk->R.string.travel_walk;TravelMode.run->R.string.travel_run;TravelMode.bike->R.string.travel_bike
    TravelMode.drive->R.string.travel_drive;TravelMode.transit->R.string.travel_transit;TravelMode.fly->R.string.travel_fly
})
@Composable internal fun travelModeActive(mode:TravelMode):String = stringResource(when(mode) {
    TravelMode.walk->R.string.travel_walking;TravelMode.run->R.string.travel_running;TravelMode.bike->R.string.travel_cycling
    TravelMode.drive->R.string.travel_driving;TravelMode.transit->R.string.travel_on_train;TravelMode.fly->R.string.travel_flying
})

/** "Bike · 3.4 km · 28 min". */
@Composable internal fun dayMoveLabel(move:DayMove,metric:Boolean):String {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val minutes=move.duration.toMinutes().coerceAtLeast(0)
    val time=if(minutes<60) stringResource(R.string.map_home_minutes,minutes) else stringResource(R.string.map_home_hours,minutes/60,"%02d".format(minutes%60))
    return listOfNotNull(move.mode?.let {travelModeLabel(it)},move.distanceMeters.takeIf {it>0}?.let {dayDistance(it,metric,locale)},time).joinToString(" · ")
}

private val DayClockColumn=44.dp
private val DayDotColumn=14.dp

/** One stay in "the day so far": mono clock, place-ink dot, serif name, duration (or NOW). */
@Composable internal fun DayStayRow(stay:DayStay,onClick:(()->Unit)?,modifier:Modifier=Modifier) {
    val colors=wd
    val name=stay.name ?: stringResource(R.string.timeline_visit)
    Row(modifier.fillMaxWidth().heightIn(min=44.dp).then(if(onClick!=null) Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(role=Role.Button,onClick=onClick) else Modifier)
        .testTag("day-stay-${stay.id}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(dayClockText(stay.start),Modifier.width(DayClockColumn),style=WdType.caption,color=colors.textMuted,maxLines=1)
        Box(Modifier.width(DayDotColumn),contentAlignment=Alignment.Center) {
            if(stay.current) WdLiveDot(Modifier.requiredSize(18.dp)) else Box(Modifier.size(12.dp).clip(CircleShape).background(dayInk(stay.ink)))
        }
        Text(name,Modifier.weight(1f),style=WdType.title3.copy(fontSize=19.sp,lineHeight=23.sp),color=colors.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(if(stay.current) stringResource(R.string.timeline_redesign_now).uppercase() else dayStayDuration(stay.duration),style=WdType.caption,
            color=if(stay.current) colors.liveDot else colors.textMuted,maxLines=1)
    }
}

/** A move between stays: the dotted connector and "Walk · 1.1 km · 25 min". */
@Composable internal fun DayMoveRow(move:DayMove,metric:Boolean,modifier:Modifier=Modifier) {
    val colors=wd
    Row(modifier.fillMaxWidth().heightIn(min=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.width(DayClockColumn))
        Box(Modifier.width(DayDotColumn).height(22.dp).drawBehind {
            drawLine(colors.dialTick,Offset(size.width/2,0f),Offset(size.width/2,size.height),strokeWidth=2.dp.toPx(),cap=StrokeCap.Round,
                pathEffect=PathEffect.dashPathEffect(floatArrayOf(0.1f,4.dp.toPx())))
        })
        Text(dayMoveLabel(move,metric),style=WdType.caption2.copy(fontSize=10.5.sp,letterSpacing=0.02.em),color=colors.textMuted,maxLines=2)
    }
}

/** The three day figures beside the dial: MOVED / AWAY / PLACES. */
@Composable internal fun DayStats(story:DayStory,metric:Boolean,modifier:Modifier=Modifier) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val away=story.awayDuration?.let {
        val minutes=it.toMinutes();if(minutes<60) stringResource(R.string.map_home_minutes,minutes) else stringResource(R.string.map_home_hours,minutes/60,"%02d".format(minutes%60))
    } ?: "—"
    Row(modifier.fillMaxWidth()) {
        DayFigure(dayDistance(story.distanceMeters,metric,locale),stringResource(R.string.map_home_moved),Modifier.weight(1f).testTag("day-stat-moved"))
        DayFigure(away,stringResource(R.string.map_home_away),Modifier.weight(1f).testTag("day-stat-away"))
        DayFigure(story.placeCount.toString(),stringResource(R.string.map_home_places),Modifier.weight(1f).testTag("day-stat-places"))
    }
}

@Composable internal fun DayFigure(value:String,label:String,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(1.dp)) {
        Text(value,style=WdType.title3.copy(fontSize=21.sp,lineHeight=24.sp),color=wd.textPrimary,maxLines=1)
        Text(label.uppercase(),style=WdType.caption2.copy(fontSize=9.sp,letterSpacing=0.1.em),color=wd.textMuted,maxLines=2)
    }
}

/** "The day so far": stays and the moves between them, oldest first, as on Today. */
@Composable internal fun DaySoFar(story:DayStory,metric:Boolean,onStay:((DayStay)->Unit)?,modifier:Modifier=Modifier) {
    Column(modifier) {
        story.entries.forEach {entry->
            when(entry) {
                is DayEntry.Stay->DayStayRow(entry.stay,onStay?.let {open->{open(entry.stay)}})
                is DayEntry.Move->DayMoveRow(entry.move,metric)
            }
        }
    }
}
