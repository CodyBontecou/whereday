package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bontecou.whereday.data.CountryDayReport
import com.bontecou.whereday.domain.CountryDayBounds
import com.bontecou.whereday.domain.CountryPresence
import com.bontecou.whereday.domain.CountryRegions
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One country's share of the shown period, with its Atlas ink. */
internal data class AtlasCountry(val code:String,val days:Int)

/** Per-day country presence for one calendar year, ready to draw. Pure so it can be unit tested. */
internal data class AtlasYear(val year:Int,val byDay:Map<LocalDate,List<CountryPresence>>,val countries:List<AtlasCountry>,val gaps:Int) {
    companion object {
        /** Countries are ordered by days (then code) so inks stay stable for the same history. Gaps count past days with no record. */
        fun of(year:Int,days:List<CountryPresence>,today:LocalDate):AtlasYear {
            val inYear=days.filter {it.day.year==year}
            val byDay=inYear.groupBy {it.day}
            val countries=inYear.groupBy {it.countryCode}.map {(code,entries)->AtlasCountry(code,entries.map {it.day}.distinct().size)}
                .sortedWith(compareByDescending<AtlasCountry> {it.days}.thenBy {it.code})
            val start=LocalDate.of(year,1,1)
            val end=minOf(LocalDate.of(year,12,31),today)
            var gaps=0
            if(!end.isBefore(start)) {
                // Days before the first record are not "gaps": the journal had not started yet.
                val first=byDay.keys.minOrNull()
                var day=first ?: end.plusDays(1)
                while(!day.isAfter(end)) {if(byDay[day].isNullOrEmpty()) gaps++;day=day.plusDays(1)}
            }
            return AtlasYear(year,byDay,countries,gaps)
        }
        fun totals(days:List<CountryPresence>)=days.groupBy {it.countryCode}.map {(code,entries)->AtlasCountry(code,entries.map {it.day}.distinct().size)}
            .sortedWith(compareByDescending<AtlasCountry> {it.days}.thenBy {it.code})
    }
}

@Composable internal fun AtlasScreen(graph:AppGraph,onMonthRecap:(()->Unit)?=null) {
    var showDates by rememberSaveable {mutableStateOf(false)}
    if(showDates) {
        BackHandler {showDates=false}
        CountryDaysScreen(graph,back={showDates=false})
        return
    }
    val today=remember {LocalDate.now()}
    var year by rememberSaveable {mutableIntStateOf(today.year)}
    var allYears by rememberSaveable {mutableStateOf(false)}
    var selectedCode by rememberSaveable {mutableStateOf<String?>(null)}
    var report by remember {mutableStateOf<CountryDayReport?>(null)}
    var allReport by remember {mutableStateOf<CountryDayReport?>(null)}
    var failure by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(year,retry,lifecycle) {
        report=null;failure=false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {withContext(Dispatchers.IO) {graph.history.observeCountryDays(CountryDayBounds(LocalDate.of(year,1,1),LocalDate.of(year,12,31))).collect {report=it}}}
            catch(e:Exception) {if(e is CancellationException) throw e;failure=true}
        }
    }
    LaunchedEffect(allYears,retry,lifecycle) {
        allReport=null
        if(allYears) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {withContext(Dispatchers.IO) {graph.history.observeCountryDays(CountryDayBounds()).collect {allReport=it}}}
            catch(e:Exception) {if(e is CancellationException) throw e;failure=true}
        }
    }
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val atlas=remember(report,year) {report?.let {AtlasYear.of(year,it.days,today)}}
    val listed=if(allYears) allReport?.let {AtlasYear.totals(it.days)} else atlas?.countries
    // Inks follow the year's own order so the grid and its list agree.
    val inkOrder=atlas?.countries?.map {it.code}.orEmpty()
    val inks=wd.placeInks
    fun ink(code:String):Color {val index=inkOrder.indexOf(code);return inks[(if(index<0) (code.hashCode() and Int.MAX_VALUE) else index)%inks.size]}
    BoxWithConstraints(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("atlas-screen")) {
        val wide=maxWidth>=720.dp
        val header:@Composable ()->Unit={AtlasHeader(year,allYears,today.year,{year--;selectedCode=null},{if(year<today.year) {year++;selectedCode=null}},{allYears=it})}
        val grid:@Composable (Modifier,Dp)->Unit={modifier,labelWidth->
            if(failure) WdNotice(stringResource(R.string.atlas_error),modifier,icon=Icons.Outlined.ErrorOutline,warning=true) {
                SettingsTextAction(stringResource(R.string.map_retry_history),{retry++})
            } else if(atlas==null) WdLoading(modifier)
            else AtlasGrid(atlas,today,::ink,if(wide) selectedCode else null,labelWidth,modifier)
        }
        val countries:@Composable (Modifier)->Unit={modifier->
            AtlasCountryList(listed,::ink,locale,selectedCode.takeIf {wide},modifier) {code->if(wide) selectedCode=if(selectedCode==code) null else code else showDates=true}
        }
        val footnote:@Composable ()->Unit={
            val gaps=atlas?.gaps ?: 0
            Text(if(gaps>0) pluralStringResource(R.plurals.atlas_footnote_gaps,gaps,NumberFormat.getIntegerInstance(locale).format(gaps)) else stringResource(R.string.atlas_footnote),
                Modifier.padding(horizontal=24.dp).padding(top=12.dp),style=MaterialTheme.typography.bodySmall.copy(fontSize=13.sp,lineHeight=18.sp),color=wd.textMuted)
        }
        if(wide) {
            // The Tablet Atlas board: the year beside the country list and a selection card.
            Row(Modifier.fillMaxSize().padding(end=16.dp,top=16.dp,bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(wd.containerLow).verticalScroll(rememberScrollState()).padding(vertical=4.dp)) {
                    header()
                    grid(Modifier.padding(horizontal=20.dp).padding(top=8.dp,bottom=16.dp),40.dp)
                }
                Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    countries(Modifier)
                    AtlasSelectionCard(selectedCode,listed,atlas,year,locale) {showDates=true}
                    footnote()
                }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=if(onMonthRecap!=null) 104.dp else 24.dp)) {
            header()
            Box(Modifier.padding(horizontal=16.dp).padding(top=4.dp).clip(RoundedCornerShape(28.dp)).background(wd.containerLow).padding(start=12.dp,end=12.dp,top=16.dp,bottom=14.dp)) {
                grid(Modifier,34.dp)
            }
            countries(Modifier.padding(horizontal=16.dp).padding(top=12.dp))
            footnote()
        }
        if(onMonthRecap!=null && !wide) WdExtendedFab(stringResource(R.string.recap_title),Icons.AutoMirrored.Outlined.Article,onMonthRecap,
            Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("atlas-month-recap"),primary=true)
    }
}

@Composable private fun AtlasHeader(year:Int,allYears:Boolean,currentYear:Int,previous:()->Unit,next:()->Unit,onRange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=16.dp,top=20.dp,bottom=8.dp),verticalAlignment=Alignment.Bottom) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.atlas_eyebrow).uppercase(),style=WdType.caption2.copy(letterSpacing=0.127.em),color=wd.textMuted)
            Row(Modifier.offset(x=(-12).dp),verticalAlignment=Alignment.CenterVertically) {
                WdIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,stringResource(R.string.atlas_previous_year),previous,Modifier.testTag("atlas-previous-year"))
                Text(if(allYears) stringResource(R.string.atlas_all_years) else year.toString(),style=WdType.pageTitle,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
                WdIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight,stringResource(R.string.atlas_next_year),next,Modifier.testTag("atlas-next-year"),enabled=year<currentYear && !allYears)
            }
        }
        WdSegmented(listOf(false,true),allYears,{stringResource(if(it) R.string.atlas_range_all else R.string.atlas_range_year)},onRange,Modifier.width(150.dp).padding(bottom=4.dp))
    }
}

/** Twelve month rows of day cells inked by country: filled for recorded days, outlined for manual stays,
 *  a quiet tone for days with no record and a faint outline for days still ahead. */
@Composable private fun AtlasGrid(atlas:AtlasYear,today:LocalDate,ink:(String)->Color,selected:String?,labelWidth:Dp,modifier:Modifier) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val noRecord=wd.containerHighest;val outline=wd.outline;val muted=wd.textMuted
    val description=stringResource(R.string.atlas_grid_description,atlas.year.toString())+
        if(atlas.countries.isEmpty()) "" else ": "+atlas.countries.joinToString {CountryRegions.displayName(it.code,locale)+" "+it.days}
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap=2.dp
            val cell=((maxWidth-labelWidth-gap*30)/31).coerceIn(4.dp,16.dp)
            val cellHeight=cell*1.5f
            val rowGap=if(cell>10.dp) 6.dp else 4.dp
            Column(Modifier.semantics(mergeDescendants=true) {contentDescription=description}.testTag("atlas-grid"),verticalArrangement=Arrangement.spacedBy(rowGap)) {
                (1..12).forEach {month->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(java.time.Month.of(month).getDisplayName(TextStyle.SHORT,locale).uppercase(locale),Modifier.width(labelWidth),style=WdType.caption2.copy(fontSize=10.sp),color=muted,maxLines=1)
                        val length=java.time.YearMonth.of(atlas.year,month).lengthOfMonth()
                        Canvas(Modifier.width(cell*31+gap*30).height(cellHeight)) {
                            val w=cell.toPx();val h=cellHeight.toPx();val g=gap.toPx();val r=CornerRadius(2.dp.toPx())
                            for(d in 1..length) {
                                val day=LocalDate.of(atlas.year,month,d)
                                val x=(d-1)*(w+g)
                                val presences=atlas.byDay[day].orEmpty()
                                if(day.isAfter(today)) {
                                    drawRoundRect(outline.copy(alpha=0.6f),Offset(x+0.5f,0.5f),Size(w-1f,h-1f),r,style=Stroke(1.dp.toPx()))
                                    continue
                                }
                                if(presences.isEmpty()) {drawRoundRect(noRecord,Offset(x,0f),Size(w,h),r);continue}
                                val primary=presences.sortedBy {p->atlas.countries.indexOfFirst {it.code==p.countryCode}.let {if(it<0) Int.MAX_VALUE else it}}.first()
                                val color=ink(primary.countryCode).copy(alpha=if(selected!=null && presences.none {it.countryCode==selected}) 0.28f else 1f)
                                if(presences.all {it.manual && !it.recorded}) drawRoundRect(color,Offset(x+0.75f,0.75f),Size(w-1.5f,h-1.5f),r,style=Stroke(1.5.dp.toPx()))
                                else drawRoundRect(color,Offset(x,0f),Size(w,h),r)
                            }
                        }
                    }
                }
            }
        }
        // Legend, aligned with the cells.
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(start=labelWidth,top=12.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            AtlasLegend(stringResource(R.string.atlas_legend_none)) {Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(noRecord))}
            AtlasLegend(stringResource(R.string.atlas_legend_manual)) {Box(Modifier.size(8.dp).border(1.5.dp,wd.textPrimary,RoundedCornerShape(2.dp)))}
            AtlasLegend(stringResource(R.string.atlas_legend_ahead)) {Box(Modifier.size(8.dp).border(1.dp,outline,RoundedCornerShape(2.dp)))}
        }
    }
}

@Composable private fun AtlasLegend(label:String,swatch:@Composable ()->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        swatch();Text(label,style=WdType.caption2.copy(fontSize=10.sp),color=wd.textMuted)
    }
}

@Composable private fun AtlasCountryList(countries:List<AtlasCountry>?,ink:(String)->Color,locale:Locale,selected:String?,modifier:Modifier,onClick:(String)->Unit) {
    val number=remember(locale) {NumberFormat.getIntegerInstance(locale)}
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(wd.containerLow).padding(vertical=8.dp).testTag("atlas-countries")) {
        Text(stringResource(R.string.atlas_by_country).uppercase(),Modifier.padding(start=20.dp,end=20.dp,top=8.dp,bottom=4.dp).semantics {heading()},style=WdType.eyebrow,color=wd.textMuted)
        when {
            countries==null->WdLoading()
            countries.isEmpty()->Text(stringResource(R.string.atlas_empty),Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
            else->{
                val max=countries.maxOf {it.days}.coerceAtLeast(1)
                countries.forEach {country->
                    val isSelected=selected==country.code
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp).clip(RoundedCornerShape(16.dp)).background(if(isSelected) wd.secondaryContainer else Color.Transparent)
                        .clickable(role=Role.Button) {onClick(country.code)}.semantics {this.selected=isSelected}.testTag("atlas-country-${country.code}")
                        .heightIn(min=56.dp).padding(start=12.dp,end=4.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text(CountryRegions.displayName(country.code,locale),Modifier.weight(1f),style=WdType.title3.copy(fontSize=19.sp,fontWeight=FontWeight.Normal),color=wd.textPrimary)
                                Text(pluralStringResource(R.plurals.atlas_days,country.days,number.format(country.days)),style=WdType.caption.copy(fontSize=13.sp),color=wd.textPrimary)
                            }
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(wd.containerHighest)) {
                                Box(Modifier.fillMaxWidth((country.days.toFloat()/max).coerceIn(0.03f,1f)).fillMaxHeight().clip(CircleShape).background(ink(country.code)))
                            }
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(20.dp),tint=wd.textMuted)
                    }
                }
            }
        }
    }
}

/** The tablet selection card: the picked country's count with its dates one tap away. */
@Composable private fun AtlasSelectionCard(selected:String?,countries:List<AtlasCountry>?,atlas:AtlasYear?,year:Int,locale:Locale,onDates:()->Unit) {
    val number=remember(locale) {NumberFormat.getIntegerInstance(locale)}
    val country=countries?.find {it.code==selected}
    val shape=RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(wd.inkCard).padding(horizontal=20.dp,vertical=18.dp).testTag("atlas-selection"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(country!=null) {
            Text(pluralStringResource(R.plurals.atlas_selected_days,country.days,number.format(country.days)).uppercase(),style=WdType.caption2.copy(fontSize=10.sp),color=wd.onInkCardMuted)
            Text(CountryRegions.displayName(country.code,locale),style=WdType.title2.copy(fontSize=26.sp,fontWeight=FontWeight.Normal),color=wd.onInkCard)
        } else {
            val count=atlas?.countries?.size ?: 0
            Text(stringResource(R.string.atlas_all_countries).uppercase(),style=WdType.caption2.copy(fontSize=10.sp),color=wd.onInkCardMuted)
            Text(pluralStringResource(R.plurals.atlas_countries_in,count,number.format(count),year.toString()),style=WdType.title2.copy(fontSize=26.sp,fontWeight=FontWeight.Normal),color=wd.onInkCard)
            Text(stringResource(R.string.atlas_pick_country),style=MaterialTheme.typography.bodyMedium,color=wd.onInkCardMuted)
        }
        Row(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.heightIn(min=40.dp).clip(CircleShape).background(wd.onInkCard).clickable(role=Role.Button,onClick=onDates).padding(horizontal=16.dp),contentAlignment=Alignment.Center) {
                Text(stringResource(R.string.atlas_view_dates),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=wd.inkCard)
            }
            Box(Modifier.heightIn(min=40.dp).clip(CircleShape).border(1.dp,wd.onInkCardMuted,CircleShape).clickable(role=Role.Button,onClick=onDates).padding(horizontal=16.dp),contentAlignment=Alignment.Center) {
                Text(stringResource(R.string.atlas_add_stay),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=wd.onInkCard)
            }
        }
    }
}
