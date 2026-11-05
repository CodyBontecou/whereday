package com.bontecou.whereday

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The day sheet's detents: the floating peek card, about 60% of the screen, or nearly all of it. */
internal enum class DaySheetDetent {peek,half,full}

/** A detented sheet over the map. The map stays interactive above it; drag the grabber or tap it to move between detents. */
@Composable internal fun MapDetentSheet(detent:DaySheetDetent,onDetent:(DaySheetDetent)->Unit,available:Dp,topReserve:Dp,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val colors=wd
    val density=LocalDensity.current
    val half=(available*0.6f).coerceAtLeast(280.dp)
    val full=(available-topReserve).coerceAtLeast(half)
    val target=if(detent==DaySheetDetent.full) full else half
    var drag by remember {mutableStateOf(0f)}
    val height by animateDpAsState(target,label="day-sheet")
    val shown=(height-with(density) {drag.toDp()}).coerceIn(120.dp,full)
    val expand=stringResource(R.string.map_home_sheet_expand);val collapse=stringResource(R.string.map_home_sheet_collapse)
    Column(modifier.fillMaxWidth().height(shown).shadow(18.dp,RoundedCornerShape(topStart=26.dp,topEnd=26.dp),ambientColor=colors.textPrimary.copy(alpha=0.14f),spotColor=colors.textPrimary.copy(alpha=0.14f))
        .clip(RoundedCornerShape(topStart=26.dp,topEnd=26.dp)).background(colors.surface).testTag("map-day-sheet")) {
        Box(Modifier.fillMaxWidth().height(32.dp).draggable(rememberDraggableState {drag+=it},Orientation.Vertical,onDragStopped={velocity->
            val dragged=with(density) {drag.toDp()}
            val next=when {
                dragged>120.dp || velocity>2500f->if(detent==DaySheetDetent.full && dragged<(full-half+80.dp)) DaySheetDetent.half else DaySheetDetent.peek
                dragged< (-80).dp || velocity< -2500f->DaySheetDetent.full
                else->detent
            }
            drag=0f;onDetent(next)
        }).clickable(role=Role.Button,onClickLabel=if(detent==DaySheetDetent.full) collapse else expand) {
            onDetent(if(detent==DaySheetDetent.half) DaySheetDetent.full else DaySheetDetent.half)
        }.semantics {
            contentDescription=collapse
            customActions=listOf(CustomAccessibilityAction(collapse) {onDetent(DaySheetDetent.peek);true},CustomAccessibilityAction(expand) {onDetent(DaySheetDetent.full);true})
        }.testTag("map-day-sheet-handle"),contentAlignment=Alignment.Center) {
            Box(Modifier.size(width=36.dp,height=4.dp).clip(CircleShape).background(if(colors.dark) colors.border else androidx.compose.ui.graphics.Color(0xFFCFC8B7)))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp).navigationBarsPadding(),
            verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}

/** The collapsed day: mini dial, YOU'RE AT · SINCE, the place, today's distance and place count. Tap to open the day. */
@Composable internal fun MapDayPeek(story:DayStory?,metric:Boolean,now:java.time.Instant,onOpen:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val current=story?.currentStay
    val last=story?.stays?.lastOrNull()
    val kicker=when {
        current!=null->stringResource(R.string.map_home_youre_at,dayClockText(current.since))
        last!=null->stringResource(R.string.map_home_last_stop,dayClockText(last.start))
        else->stringResource(R.string.map_home_day_so_far)
    }
    val title=(current ?: last)?.let {it.name ?: stringResource(R.string.timeline_visit)} ?: stringResource(R.string.map_home_no_places)
    val open=stringResource(R.string.map_home_open_day)
    Row(modifier.fillMaxWidth().heightIn(min=64.dp).wdFloating(RoundedCornerShape(22.dp),colors,8.dp).clickable(role=Role.Button,onClickLabel=open,onClick=onOpen)
        .padding(start=8.dp,end=14.dp,top=6.dp,bottom=6.dp).testTag("map-day-peek"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        if(story!=null) WdDayDial(dayDialSegments(story),now=dayNowFraction(story,now),size=48.dp,ring=0.13f) else Spacer(Modifier.size(48.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(kicker.uppercase(locale),style=WdType.caption2.copy(fontSize=9.sp,lineHeight=12.sp,letterSpacing=0.13.em,fontWeight=FontWeight.Medium),color=colors.textMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(title,style=WdType.title3.copy(fontSize=18.sp,lineHeight=21.sp),color=colors.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("map-day-peek-place"))
        }
        if(story!=null) Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(dayDistance(story.distanceMeters,metric,locale),style=WdType.title3.copy(fontSize=16.sp,lineHeight=19.sp),color=colors.textPrimary,maxLines=1)
            Text(pluralStringResource(R.plurals.map_home_places_count,story.placeCount,story.placeCount).uppercase(locale),style=WdType.caption2.copy(fontSize=9.sp,letterSpacing=0.13.em,fontWeight=FontWeight.Medium),color=colors.textMuted,maxLines=1)
        }
        Icon(Icons.Outlined.KeyboardArrowUp,null,Modifier.size(18.dp),tint=colors.textMuted)
    }
}

/** M2: dial and three stats, then "the day so far". */
@Composable internal fun ColumnScope.MapDayContent(story:DayStory,metric:Boolean,now:java.time.Instant,onStay:(DayStay)->Unit,onTimeline:()->Unit,large:Boolean) {
    val colors=wd
    if(large) DayStats(story,metric) else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        WdDayDial(dayDialSegments(story),now=dayNowFraction(story,now),size=72.dp,ring=0.13f)
        DayStats(story,metric,Modifier.weight(1f))
    }
    Text(stringResource(R.string.map_home_day_so_far).uppercase(),style=WdType.caption2.copy(fontSize=10.5.sp,letterSpacing=0.15.em,fontWeight=FontWeight.Medium),color=colors.textMuted,
        modifier=Modifier.padding(top=4.dp).semantics {heading()})
    if(story.entries.isEmpty()) Text(stringResource(R.string.map_home_no_places_body),style=WdType.serifBody,color=colors.textMuted,modifier=Modifier.testTag("map-day-empty"))
    else DaySoFar(story,metric,onStay,Modifier.testTag("map-day-so-far"))
    TraceCapsule(stringResource(R.string.map_home_open_timeline),onTimeline,Modifier.padding(top=6.dp).testTag("map-open-timeline"),icon=Icons.AutoMirrored.Outlined.ViewList)
}

/** M6's bottom card: the selected day's summary, the range totals and a scrolling strip of day chips with distance bars. */
@Composable internal fun MapRangeCard(summary:MapRangeSummary,selected:LocalDate?,metric:Boolean,onSelect:(LocalDate)->Unit,onOpen:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val day=selected?.let(summary::day)
    val open=stringResource(R.string.map_home_open_days)
    Column(modifier.fillMaxWidth().wdFloating(RoundedCornerShape(22.dp),colors,8.dp).padding(12.dp).testTag("map-range-card"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClickLabel=open,onClick=onOpen),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(day?.let {rangeDayKicker(it.day,locale)} ?: "",style=WdType.caption2.copy(fontSize=9.5.sp,letterSpacing=0.13.em,fontWeight=FontWeight.Medium),color=colors.textMuted,maxLines=1)
                Text(day?.let {stringResource(R.string.map_home_range_day_summary,dayDistance(it.distanceMeters,metric,locale),pluralStringResource(R.plurals.map_home_places_lower,it.placeCount,it.placeCount))} ?: "",
                    style=WdType.title3.copy(fontSize=18.sp,lineHeight=21.sp),color=colors.textPrimary,maxLines=2,modifier=Modifier.testTag("map-range-day-summary"))
            }
            Text(stringResource(R.string.map_home_range_totals,dayDistance(summary.distanceMeters,metric,locale),pluralStringResource(R.plurals.map_home_visits_count,summary.visitCount,summary.visitCount)).uppercase(locale),
                style=WdType.caption2.copy(fontSize=9.5.sp,letterSpacing=0.1.em,fontWeight=FontWeight.Medium),color=colors.textMuted,maxLines=2,modifier=Modifier.testTag("map-range-totals"))
        }
        // 30D/All: one lazily composed strip that starts at the latest day, so only visible chips are built.
        val state=rememberLazyListState(initialFirstVisibleItemIndex=(summary.days.size-1).coerceAtLeast(0))
        val longest=summary.days.maxOfOrNull {it.distanceMeters}?.takeIf {it>0} ?: 1.0
        LazyRow(state=state,horizontalArrangement=Arrangement.spacedBy(5.dp),modifier=Modifier.fillMaxWidth().testTag("map-range-days")) {
            items(summary.days,key={it.day.toString()}) {item->
                val isSelected=item.day==selected
                val label=rangeDayLabel(item,metric,locale)
                Column(Modifier.width(if(summary.days.size<=7) 42.dp else 38.dp).height(64.dp).clip(RoundedCornerShape(14.dp))
                    .background(if(isSelected) colors.placeInks[1] else colors.surface).border(1.dp,if(isSelected) colors.placeInks[1] else colors.border,RoundedCornerShape(14.dp))
                    .selectable(isSelected,role=Role.Tab) {onSelect(item.day)}.semantics {contentDescription=label}.padding(vertical=8.dp).testTag("map-range-day-${item.day}"),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Bottom) {
                    val fraction=(item.distanceMeters/longest).toFloat().coerceIn(0f,1f)
                    Box(Modifier.width(8.dp).height((4+20*fraction).dp).clip(RoundedCornerShape(2.dp))
                        .background(if(isSelected) colors.surface.copy(alpha=0.85f) else colors.textPrimary.copy(alpha=0.35f)))
                    Spacer(Modifier.height(5.dp))
                    Text(item.day.dayOfWeek.getDisplayName(TextStyle.NARROW,locale)+(if(summary.days.size>7) "" else ""),style=WdType.caption2.copy(fontSize=11.sp),
                        color=if(isSelected) colors.surface else colors.textMuted,maxLines=1)
                    if(summary.days.size>7) Text(item.day.dayOfMonth.toString(),style=WdType.caption2.copy(fontSize=9.sp),color=if(isSelected) colors.surface else colors.textMuted,maxLines=1)
                }
            }
        }
    }
}

/** M2 for several days: one row per day with its distance and places; tap highlights that day on the map. */
@Composable internal fun ColumnScope.MapRangeContent(summary:MapRangeSummary,selected:LocalDate?,metric:Boolean,onSelect:(LocalDate)->Unit) {
    val colors=wd
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Text(stringResource(R.string.map_home_days_title).uppercase(),style=WdType.caption2.copy(fontSize=10.5.sp,letterSpacing=0.15.em,fontWeight=FontWeight.Medium),color=colors.textMuted,modifier=Modifier.semantics {heading()})
    summary.days.asReversed().take(400).forEach {day->
        val isSelected=day.day==selected
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).background(if(isSelected) colors.placeInks[1].copy(alpha=0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(isSelected,role=Role.Button) {onSelect(day.day)}.padding(horizontal=8.dp).testTag("map-range-row-${day.day}"),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(rangeDayKicker(day.day,locale),Modifier.weight(1f),style=WdType.caption.copy(letterSpacing=0.06.em),color=if(isSelected) colors.inkRust else colors.textPrimary,maxLines=1)
            Text(stringResource(R.string.map_home_range_day_summary,dayDistance(day.distanceMeters,metric,locale),pluralStringResource(R.plurals.map_home_places_lower,day.placeCount,day.placeCount)),
                style=WdType.caption,color=colors.textMuted,maxLines=1)
        }
    }
}

internal fun rangeDayKicker(day:LocalDate,locale:Locale):String =
    (day.dayOfWeek.getDisplayName(TextStyle.FULL,locale)+" · "+DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"MMMd"),locale).format(day)).uppercase(locale)

@Composable private fun rangeDayLabel(day:MapRangeDay,metric:Boolean,locale:Locale):String =
    rangeDayKicker(day.day,locale)+". "+stringResource(R.string.map_home_range_day_summary,dayDistance(day.distanceMeters,metric,locale),pluralStringResource(R.plurals.map_home_places_lower,day.placeCount,day.placeCount))
