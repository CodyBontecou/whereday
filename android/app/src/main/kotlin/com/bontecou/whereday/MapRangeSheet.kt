package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

internal val mapRangeDraftSaver=listSaver<MapRangeDraft,String>(
    save={listOf(it.preset.orEmpty(),it.lower.toString(),it.upper.toString(),it.month.toString(),it.anchor?.toString().orEmpty())},
    restore={MapRangeDraft(it[0].ifEmpty {null},LocalDate.parse(it[1]),LocalDate.parse(it[2]),YearMonth.parse(it[3]),it[4].ifEmpty {null}?.let(LocalDate::parse))}
)

/** The iOS 2.0 "Show days" sheet: preset chips over a month calendar of whole days, dots on recorded days. */
@Composable internal fun MapRangeSheet(
    initial:MapRangeDraft,recordedDays:Set<LocalDate>,now:()->Instant,zone:ZoneId,dismiss:()->Unit,apply:(MapSelection)->Unit
) {
    BlockVisitLinks()
    var draft by rememberSaveable(initial,stateSaver=mapRangeDraftSaver) {mutableStateOf(initial)}
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val colors=wd
    val applyDraft={apply(draft.apply(now(),zone))}
    val count=draft.applyDayCount(recordedDays.minOrNull(),now(),zone)
    WorkspaceModalBottomSheet(dismiss,fullHeight=true) {
        Box(Modifier.fillMaxWidth().padding(horizontal=20.dp).heightIn(min=44.dp),contentAlignment=Alignment.Center) {
            Text(stringResource(R.string.map_show_days),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),
                color=colors.textPrimary,modifier=Modifier.semantics {heading()})
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton({draft=draft.select("Today",now(),zone)},Modifier.testTag("map-range-reset"),colors=ButtonDefaults.textButtonColors(contentColor=colors.textMuted)) {
                    Text(stringResource(R.string.map_reset_dates))
                }
                Spacer(Modifier.weight(1f))
                TextButton(applyDraft,Modifier.testTag("map-range-apply"),colors=ButtonDefaults.textButtonColors(contentColor=colors.inkRust)) {
                    Text(stringResource(R.string.map_apply_dates),fontWeight=FontWeight.SemiBold)
                }
            }
        }
        Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=12.dp,bottom=16.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MapRangeDraft.presets.forEach {option->
                    val on=draft.preset==option
                    Text(stringResource(mapRangePresetLabel(option)),style=MaterialTheme.typography.bodySmall.copy(fontWeight=FontWeight.Medium),
                        color=if(on) colors.onInkCard else colors.textPrimary,
                        modifier=Modifier.heightIn(min=44.dp).background(if(on) colors.accent else colors.card,CircleShape)
                            .border(1.dp,if(on) Color.Transparent else colors.border,CircleShape)
                            .clickable(role=Role.Button) {draft=draft.select(option,now(),zone)}
                            .semantics {selected=on}.testTag("map-range-preset-$option")
                            .wrapContentHeight(Alignment.CenterVertically).padding(horizontal=14.dp))
                }
            }
            MapRangeCalendar(draft,recordedDays,now(),zone,locale,{draft=draft.showMonth(it)}) {draft=draft.tap(it,now(),zone)}
        }
        Text(pluralStringResource(R.plurals.map_show_day_count,count,count),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),
            color=colors.onInkCard,textAlign=TextAlign.Center,
            modifier=Modifier.padding(horizontal=16.dp).padding(bottom=12.dp).fillMaxWidth().heightIn(min=52.dp)
                .background(colors.accent,RoundedCornerShape(16.dp)).clickable(role=Role.Button,onClick=applyDraft)
                .testTag("map-range-show").wrapContentHeight(Alignment.CenterVertically))
    }
}

private fun mapRangePresetLabel(preset:String)=when(preset) {
    "Today"->R.string.map_date_today
    "Yesterday"->R.string.map_date_yesterday
    "7 days"->R.string.map_range_last_7_days
    "This month"->R.string.map_range_this_month
    "30 days"->R.string.map_range_last_30_days
    else->R.string.map_range_all_time
}

@Composable private fun MapRangeCalendar(
    draft:MapRangeDraft,recordedDays:Set<LocalDate>,now:Instant,zone:ZoneId,locale:Locale,showMonth:(Long)->Unit,select:(LocalDate)->Unit
) {
    val colors=wd
    val today=now.atZone(zone).toLocalDate()
    val allTime=draft.preset=="All"
    val firstWeekday=remember(locale) {WeekFields.of(locale).firstDayOfWeek}
    val monthTitle=remember(locale) {DateTimeFormatter.ofPattern("LLLL yyyy",locale)}
    val dayLabel=remember(locale) {DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)}
    val short=remember(locale) {DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"MMMd"),locale)}
    val rangeText=when {
        allTime->stringResource(R.string.map_range_all_time)
        draft.lower==draft.upper->draft.lower.format(short)
        else->"${draft.lower.format(short)} – ${draft.upper.format(short)}"
    }
    val hasVisits=stringResource(R.string.map_range_has_visits)
    Column(Modifier.fillMaxWidth().background(colors.card,RoundedCornerShape(18.dp)).border(1.dp,colors.border,RoundedCornerShape(18.dp))
        .padding(horizontal=12.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(start=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(draft.month.format(monthTitle),style=WdType.title2,color=colors.textPrimary,modifier=Modifier.semantics {heading()}.testTag("map-range-month"))
            Spacer(Modifier.weight(1f).widthIn(min=8.dp))
            Text(rangeText,style=WdType.caption,color=colors.inkRust,modifier=Modifier.testTag("map-range-text"))
            MonthButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft,stringResource(R.string.map_range_previous_month),"map-range-previous-month",true) {showMonth(-1)}
            MonthButton(Icons.AutoMirrored.Filled.KeyboardArrowRight,stringResource(R.string.map_range_next_month),"map-range-next-month",draft.canShowNextMonth(now,zone)) {showMonth(1)}
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            repeat(7) {
                Text(firstWeekday.plus(it.toLong()).getDisplayName(TextStyle.NARROW,locale),Modifier.weight(1f),
                    style=WdType.caption2,color=colors.textMuted,textAlign=TextAlign.Center)
            }
        }
        val first=draft.month.atDay(1)
        val leading=(first.dayOfWeek.value-firstWeekday.value+7)%7
        val cells=List(leading) {null}+List(draft.month.lengthOfMonth()) {first.plusDays(it.toLong())}
        cells.chunked(7).forEach {week->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                week.forEach {day->
                    if(day==null) Spacer(Modifier.weight(1f).height(40.dp))
                    else {
                        val endpoint=!allTime && (day==draft.lower || day==draft.upper)
                        val inRange=!allTime && day>=draft.lower && day<=draft.upper
                        val future=day>today
                        val recorded=day in recordedDays
                        val label=day.format(dayLabel)
                        Column(Modifier.weight(1f).heightIn(min=40.dp)
                            .background(when {endpoint->colors.accent;inRange->colors.dialTrack;else->Color.Transparent},if(endpoint) RoundedCornerShape(12.dp) else RoundedCornerShape(0.dp))
                            .clickable(enabled=!future,role=Role.Button) {select(day)}
                            .clearAndSetSemantics {
                                contentDescription=label;role=Role.Button;selected=inRange;testTag="map-range-calendar-day-$day"
                                if(recorded) stateDescription=hasVisits
                                if(future) disabled()
                                onClick {select(day);true}
                            }.alpha(if(future) 0.35f else 1f),
                            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp,Alignment.CenterVertically)) {
                            Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.bodyMedium,maxLines=1,color=if(endpoint) colors.onInkCard else colors.textPrimary)
                            Box(Modifier.size(4.dp).background(if(!recorded) Color.Transparent else if(endpoint) colors.liveDot else colors.placeInks[1],CircleShape))
                        }
                    }
                }
                repeat(7-week.size) {Spacer(Modifier.weight(1f))}
            }
        }
    }
}

@Composable private fun MonthButton(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,tag:String,enabled:Boolean,onClick:()->Unit) {
    IconButton(onClick,Modifier.size(width=36.dp,height=44.dp).testTag(tag),enabled=enabled,
        colors=IconButtonDefaults.iconButtonColors(contentColor=wd.textPrimary,disabledContentColor=wd.textMuted.copy(alpha=0.4f))) {
        Icon(icon,label,Modifier.size(20.dp))
    }
}
