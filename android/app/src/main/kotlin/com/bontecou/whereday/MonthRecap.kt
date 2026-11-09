package com.bontecou.whereday

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bontecou.whereday.domain.HistoryRange
import com.bontecou.whereday.domain.Visit
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One place's share of a day, as fractions of that day, for the recap's small dials. */
internal data class RecapArc(val start:Float,val end:Float,val ink:Int)

/** The iOS MonthRecap, ported: visits/places per day, moved distance per day (from route points), the biggest day and new places.
 *  [priorPlaces] are the display names seen before the month starts, so "new" means first-ever. */
internal data class MonthRecap(val month:YearMonth,val visitCount:Int,val placeCount:Int,val recordedDays:Int,val newPlaces:Int,
    val distancePerDay:List<Double>,val placesPerDay:List<Int>,val biggestDay:LocalDate?,val arcs:List<List<RecapArc>>) {
    val totalDistance:Double get()=distancePerDay.sum()
    val biggestDistance:Double get()=biggestDay?.let {distancePerDay.getOrNull(it.dayOfMonth-1)} ?: 0.0
    companion object {
        fun of(month:YearMonth,zone:ZoneId,visits:List<Visit>,distancePerDay:List<Double>,pointDays:Set<Int>,priorPlaces:Set<String>,now:Instant):MonthRecap {
            val start=month.atDay(1).atStartOfDay(zone).toInstant();val end=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
            fun name(visit:Visit)=visit.name?.takeIf {it.isNotBlank()} ?: visit.id
            val inMonth=visits.filter {it.arrivedAt<end && (it.departedAt ?: now)>start}.sortedBy {it.arrivedAt}
            val days=month.lengthOfMonth()
            val perDay=List(days) {mutableSetOf<String>()}
            // Longest-stayed places take the first inks, so home reads as the first ink every day.
            val durations=LinkedHashMap<String,Long>()
            inMonth.forEach {visit->
                val s=maxOf(visit.arrivedAt,start);val e=minOf(visit.departedAt ?: now,end,now)
                durations[name(visit)]=(durations[name(visit)] ?: 0)+maxOf(0,e.toEpochMilli()-s.toEpochMilli())
            }
            val inkOrder=durations.entries.sortedByDescending {it.value}.mapIndexed {index,entry->entry.key to index}.toMap()
            val arcs=List(days) {index->
                val dayStart=month.atDay(index+1).atStartOfDay(zone).toInstant();val dayEnd=month.atDay(index+1).plusDays(1).atStartOfDay(zone).toInstant()
                val dayMillis=(dayEnd.toEpochMilli()-dayStart.toEpochMilli()).toFloat()
                fun fraction(at:Instant)=((at.toEpochMilli()-dayStart.toEpochMilli())/dayMillis).coerceIn(0f,1f)
                inMonth.mapNotNull {visit->
                    val s=maxOf(visit.arrivedAt,dayStart);val e=minOf(visit.departedAt ?: now,dayEnd,now)
                    if(e<=s) return@mapNotNull null
                    // Count the same positive civil-day overlap that the dial displays.
                    perDay[index]+=name(visit)
                    RecapArc(fraction(s),fraction(e),inkOrder[name(visit)] ?: 0)
                }
            }
            val counts=perDay.map {it.size}
            val distances=List(days) {distancePerDay.getOrElse(it) {0.0}}
            val ranking=if(distances.sum()>0) distances else counts.map {it.toDouble()}
            val best=ranking.indices.maxByOrNull {ranking[it]}?.takeIf {ranking[it]>0}
            val newPlaces=inMonth.map {name(it)}.distinct().count {it !in priorPlaces}
            return MonthRecap(month,inMonth.size,inMonth.map {name(it)}.toSet().size,counts.indices.count {counts[it]>0 || it in pointDays},newPlaces,
                distances,counts,best?.let {month.atDay(it+1)},arcs)
        }
    }
}

/** Reads one month from the journal: visits, per-day moved distance (day windows' summaries) and the places seen before it. */
internal suspend fun loadMonthRecap(graph:AppGraph,month:YearMonth,zone:ZoneId=ZoneId.systemDefault()):MonthRecap=withContext(Dispatchers.IO) {
    val start=month.atDay(1).atStartOfDay(zone).toInstant();val end=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    val window=graph.history.observeWindow(HistoryRange(start,end),includeOutliers=false).first()
    val today=LocalDate.now(zone)
    val distances=ArrayList<Double>();val pointDays=HashSet<Int>()
    for(day in 1..month.lengthOfMonth()) {
        val date=month.atDay(day)
        if(date.isAfter(today)) {distances+=0.0;continue}
        val summary=graph.history.observeWindow(HistoryRange.day(date,zone),includeOutliers=false).first().summary
        distances+=summary.distanceMeters;if(summary.count>0) pointDays+=day-1
    }
    val prior=graph.history.observeWindow(HistoryRange(Instant.ofEpochMilli(0),start)).first().visits.map {it.name?.takeIf {n->n.isNotBlank()} ?: it.id}.toSet()
    MonthRecap.of(month,zone,window.visits,distances,pointDays,prior,Instant.now())
}

/** The Month recap board: a shareable paper card of small day dials and four figures, with no coordinates. */
@Composable internal fun MonthRecapScreen(graph:AppGraph,metric:Boolean,onDone:()->Unit) {
    val current=remember {YearMonth.now()}
    var offset by rememberSaveable {mutableIntStateOf(0)}
    val month=current.minusMonths(offset.toLong())
    var recap by remember {mutableStateOf<MonthRecap?>(null)}
    LaunchedEffect(month) {recap=null;recap=runCatching {loadMonthRecap(graph,month)}.getOrNull()}
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val layer=rememberGraphicsLayer()
    val shareTitle=stringResource(R.string.recap_share)
    androidx.activity.compose.BackHandler(onBack=onDone)
    Column(Modifier.fillMaxSize().background(wd.container).semantics {testTagsAsResourceId=true}.testTag("month-recap")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            WdIconButton(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.recap_done),onDone,Modifier.testTag("recap-close"))
            Text(stringResource(R.string.recap_title),Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleLarge.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Default,fontWeight=FontWeight.Normal,fontSize=22.sp),color=wd.textPrimary)
            WdIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,stringResource(R.string.recap_previous),{offset++},Modifier.testTag("recap-previous"))
            WdIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight,stringResource(R.string.recap_next),{if(offset>0) offset--},Modifier.testTag("recap-next"),enabled=offset>0)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=8.dp)) {
            val value=recap
            if(value==null) WdLoading() else Box(Modifier.widthIn(max=420.dp).align(Alignment.CenterHorizontally).drawWithContent {layer.record {this@drawWithContent.drawContent()};drawLayer(layer)}) {MonthRecapCard(value,metric,locale)}
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start=24.dp,end=24.dp,top=16.dp,bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            WdButton(shareTitle,{scope.launch {
                runCatching {
                    val bitmap=layer.toImageBitmap().asAndroidBitmap()
                    val file=withContext(Dispatchers.IO) {
                        val dir=File(context.cacheDir,"recaps").apply {mkdirs();listFiles()?.forEach {it.delete()}}
                        File(dir,"whereday-${month}.png").also {out->out.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
                    }
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.exports",file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),shareTitle))
                }
            }},Modifier.weight(1f).heightIn(min=56.dp).testTag("recap-share"),emphasis=WdEmphasis.primary,enabled=recap!=null,icon=Icons.Outlined.Share)
            WdButton(stringResource(R.string.recap_done),onDone,Modifier.width(IntrinsicSize.Max).heightIn(min=56.dp))
        }
    }
}

@Composable private fun MonthRecapCard(recap:MonthRecap,metric:Boolean,locale:Locale) {
    val shape=RoundedCornerShape(28.dp)
    val number=NumberFormat.getIntegerInstance(locale)
    val inks=wd.placeInks;val track=wd.dialTrack
    Column(Modifier.fillMaxWidth().shadow(4.dp,shape).clip(shape).background(wd.surface).padding(start=22.dp,end=22.dp,top=24.dp,bottom=20.dp).testTag("recap-card"),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(stringResource(R.string.paywall_eyebrow).uppercase(),style=WdType.caption2.copy(letterSpacing=0.145.em),color=wd.textMuted)
            Text(pluralStringResource(R.plurals.recap_days,recap.recordedDays,number.format(recap.recordedDays)).uppercase(),Modifier.testTag("recap-days"),style=WdType.caption2.copy(letterSpacing=0.145.em),color=wd.inkRust)
        }
        val monthName=recap.month.month.getDisplayName(TextStyle.FULL_STANDALONE,locale).replaceFirstChar {it.titlecase(locale)}
        Text(buildAnnotatedString {append(monthName);append("\n");withStyle(SpanStyle(fontStyle=FontStyle.Italic)) {append(recap.month.year.toString())}},
            style=WdType.pageTitle.copy(fontSize=44.sp,lineHeight=44.sp),color=wd.textPrimary)
        // Seven columns of small day dials, Monday first, starting under the month's first weekday.
        val lead=(recap.month.atDay(1).dayOfWeek.value-1)
        val cells=List(lead) {null}+recap.arcs.indices.toList()
        val description=stringResource(R.string.recap_dials_description,monthName)
        Column(Modifier.semantics {contentDescription=description},verticalArrangement=Arrangement.spacedBy(6.dp)) {
            cells.chunked(7).forEach {week->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    (0 until 7).forEach {column->
                        val day=week.getOrNull(column)
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            if(day!=null) Canvas(Modifier.fillMaxSize()) {
                                val stroke=size.minDimension*0.15f
                                val inset=stroke/2+size.minDimension*0.05f
                                val arc=Size(size.minDimension-inset*2,size.minDimension-inset*2)
                                drawArc(track,0f,360f,false,Offset(inset,inset),arc,style=Stroke(stroke))
                                recap.arcs[day].forEach {a->drawArc(inks[a.ink%inks.size],-90f+a.start*360f,((a.end-a.start).coerceAtLeast(0.01f))*360f,false,Offset(inset,inset),arc,style=Stroke(stroke))}
                            }
                        }
                    }
                }
            }
        }
        WdDivider()
        val moved=if(recap.totalDistance>0) recapDistance(recap.totalDistance,metric,locale) else "—"
        val biggest=recap.biggestDay?.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"MMMd"),locale)) ?: "—"
        val stats=listOf(
            moved to stringResource(R.string.today_stat_moved),
            number.format(recap.visitCount) to stringResource(R.string.recap_visits),
            number.format(recap.newPlaces) to stringResource(R.string.recap_new_places),
            biggest to if(recap.biggestDistance>0) stringResource(R.string.recap_biggest_distance,recapDistance(recap.biggestDistance,metric,locale)) else stringResource(R.string.recap_biggest),
        )
        stats.chunked(2).forEach {row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach {(value,label)->
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(value,style=WdType.figure.copy(fontSize=28.sp),color=wd.textPrimary,maxLines=1)
                        Text(label.uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.1.em),color=wd.textMuted)
                    }
                }
            }
        }
        Text(stringResource(R.string.recap_privacy),style=WdType.caption2.copy(fontSize=10.sp),color=wd.textMuted)
    }
}

internal fun recapDistance(meters:Double,metric:Boolean,locale:Locale):String {
    val value=meters/if(metric) 1000.0 else 1609.344
    return NumberFormat.getIntegerInstance(locale).format(Math.round(value))+if(metric) " km" else " mi"
}
