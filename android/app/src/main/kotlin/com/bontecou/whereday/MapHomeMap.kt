package com.bontecou.whereday

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.bontecou.whereday.data.MapLayerSettings
import com.bontecou.whereday.domain.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Actions the floating chrome calls on the full-bleed map: fit the range, fit the live session, focus coordinates. */
@Stable internal class JournalMapController {
    private var fitAction:(()->Unit)?=null
    private var fitActiveAction:(()->Unit)?=null
    private var focusAction:((List<Coordinate>)->Unit)?=null
    private var centerAction:(()->Coordinate?)?=null
    internal fun attach(fit:()->Unit,fitActive:()->Unit,focus:(List<Coordinate>)->Unit,center:()->Coordinate?) {fitAction=fit;fitActiveAction=fitActive;focusAction=focus;centerAction=center}
    /** Where the map is centred now, for Drop a pin. */
    fun center():Coordinate?=centerAction?.invoke()
    fun fit() {fitAction?.invoke()}
    fun fitActive() {fitActiveAction?.invoke()}
    fun focus(coordinates:List<Coordinate>) {focusAction?.invoke(coordinates)}
}

/** What the home map draws besides the journal layers. */
internal sealed interface MapHomeMode {
    /** One day (or a short custom span): legs coloured by destination, labelled pins and the "now" marker. */
    data class Day(val now:Instant,val today:Boolean) :MapHomeMode
    /** Several days: each day's path faint, [selected] highlighted, places as count circles. */
    data class Range(val dayPaths:Map<LocalDate,List<TrackPoint>>,val selected:LocalDate?,val places:List<MapRangePlace>) :MapHomeMode
}

internal data class MapHomeOptions(val controller:JournalMapController,val layers:MapLayerSettings,val padding:PaddingValues,val mode:MapHomeMode,
    /** Replay draws the whole route faintly ahead of the playhead. */
    val replayAhead:List<TrackPoint>?=null,
    /** Numbered nearby places shown while a visit is focused ("Where were you?"). */
    val focusCandidates:List<Coordinate> = emptyList(),
    val focusRadiusMeters:Double=60.0,
    /** The overlay column starts below the floating header and keeps clear of the right rail. */
    val overlayTop:androidx.compose.ui.unit.Dp=0.dp,
    /** Where the scale bar and tile credit sit, just above the bottom chrome (below the fitted route). */
    val overlayBottom:androidx.compose.ui.unit.Dp?=null,
    /** "Selected visit: …" over the map when a saved visit is shown without its quick view (View on map, links). */
    val showsFocusLabel:Boolean=true)

internal data class MapHomeStrings(val now:String,val minutes:String,val hours:String)
@Composable internal fun mapHomeStrings()=MapHomeStrings(stringResource(R.string.map_home_now_label),stringResource(R.string.map_home_minutes),stringResource(R.string.map_home_hours))

internal fun homeDuration(duration:Duration,strings:MapHomeStrings):String {
    val minutes=duration.toMinutes().coerceAtLeast(0)
    return if(minutes<60) strings.minutes.format(minutes) else strings.hours.format(minutes/60,"%02d".format(minutes%60))
}

private val clock:DateTimeFormatter=DateTimeFormatter.ofPattern("HH:mm")

/** Turns the journal window into the map-first look. Pure apart from the bitmap art it asks for. */
internal fun buildMapHomeRender(options:MapHomeOptions,contents:MapPresentation,road:RoadMapGeometry,visits:List<Visit>,focus:VisitMapFocus?,
    colors:WdColors,art:MapMarkerArt,strings:MapHomeStrings,layers:MapLayers,replaying:Boolean):MapHomeRender {
    val images=LinkedHashMap<String,android.graphics.Bitmap>()
    val zone=ZoneId.systemDefault()
    val inks=colors.placeInks.map {it.toArgb()}
    val ink=colors.accent.toArgb().let {if(colors.dark) colors.textPrimary.toArgb() else it}
    val vermilion=colors.placeInks[1].toArgb()
    fun inkFor(index:Int?)=index?.let {inks[it%inks.size]} ?: ink
    art.diamond(images)
    when(val mode=options.mode) {
        is MapHomeMode.Range->{
            val lines=if(!layers.path) emptyList() else mode.dayPaths.entries.sortedBy {it.key==mode.selected}.flatMap {(day,points)->
                // Gaps over ten minutes stay gaps, as on the day map.
                splitAtGaps(points).map {line->
                    val selected=day==mode.selected
                    MapHomeLine(line.map {it.coordinate},if(selected) vermilion else ink,if(selected) 4f else 3f,if(selected) 1f else 0.28f,halo=selected)
                }
            }
            val markers=if(!layers.visits) emptyList() else mode.places.map {place->
                val radius=(9f+3f*kotlin.math.sqrt(place.days.toFloat())).coerceAtMost(20f)
                MapHomeMarker(place.visit.id,place.coordinate,inkFor(place.ink),radius,centeredIcon=art.number(place.days.toString(),if(colors.dark && place.ink%inks.size==0) colors.surface.toArgb() else 0xFFFBF9F4.toInt(),images))
            }
            return MapHomeRender(lines,emptyList(),markers,null,null,null,images,replacesPath=true,hidesEndpoints=true)
        }
        is MapHomeMode.Day->{
            val sorted=visits.sortedWith(compareBy({it.arrivedAt},{it.id}))
            val placeInks=PlaceInks.index(sorted)
            val legs=if(road.raw.isEmpty()) emptyList() else RouteLegs.build(contents.segments,sorted)
            val roadLines=road.roads.mapIndexed {index,line->MapHomeLine(line,inkFor(road.roadStarts.getOrNull(index)?.let {RouteLegs.inkAt(it,sorted)}))}
            // The straight legs under matched roads read as fallback gaps: thinner, without a halo.
            val straight=legs.map {leg->MapHomeLine(leg.points.map {it.coordinate},inkFor(leg.ink),if(roadLines.isEmpty()) 4f else 2.5f,halo=roadLines.isEmpty())}
            val ahead=options.replayAhead?.takeIf {layers.path}?.let {all->
                RouteLegs.build(splitAtGaps(all.filter {layers.outliers || !it.outlier}),sorted).map {leg->MapHomeLine(leg.points.map {it.coordinate},inkFor(leg.ink),4f,0.35f,halo=false)}
            }.orEmpty()
            val chips=if(replaying) emptyList() else RouteLegs.chipLegs(legs,minimumMeters=400.0,limit=3).map {leg->leg.points[leg.points.size*2/5].coordinate to art.chip(clock.format(leg.start.atZone(zone)),images)}
            val current=sorted.lastOrNull {it.departedAt==null}?.takeIf {mode.today}
            val focused=focus?.id
            val opacity=when {replaying->0.45f;focused!=null->0.5f;else->1f}
            val labelled=sorted.groupBy(::placeKey).mapValues {it.value.last().id}.values.toSet()
            val markers=if(!layers.visits) emptyList() else sorted.map {visit->
                val isNow=visit.id==current?.id
                val name=visit.name?.trim()?.takeIf {it.isNotEmpty()}
                val detail=if(isNow) strings.now.format(homeDuration(Duration.between(visit.arrivedAt,mode.now),strings)).uppercase()
                    else visit.departedAt?.let {homeDuration(Duration.between(visit.arrivedAt,it),strings)}
                val labelled=name!=null && visit.id in labelled && !replaying
                val pin=if(isNow) 8f else 9f
                val icon=if(labelled) art.label(name!!,detail,if(isNow) vermilion else null,pin,images) else null
                val iconLeft=if(labelled) art.label(name!!,detail,if(isNow) vermilion else null,pin,images,left=true) else null
                MapHomeMarker(visit.id,visit.coordinate,if(isNow) vermilion else inkFor(placeInks[placeKey(visit)]),if(isNow) 7f else 9f,if(visit.id==focused) 1f else opacity,icon,priority=isNow || visit.id==focused,
                    iconLeft=iconLeft,labelWidthDp=if(labelled) art.labelWidth(name!!,detail,pin) else 0f,prefersLeft=routeLeavesRight(visit,legs))
            }
            val candidates=options.focusCandidates.mapIndexed {index,coordinate->
                MapHomeMarker("candidate-${index+1}",coordinate,if(colors.dark) colors.card.toArgb() else 0xFFFBF9F4.toInt(),9f,centeredIcon=art.number("${index+1}",colors.textPrimary.toArgb(),images))
            }
            val focusArea=focused?.let {id->visits.firstOrNull {it.id==id}?.coordinate}?.let {it to options.focusRadiusMeters}
            return MapHomeRender(ahead+straight+roadLines,chips,markers+candidates,current?.coordinate,contents.start?.coordinate,focusArea,images,replacesPath=true)
        }
    }
}

/** True when more of the day's path runs level with a stay on its east side than on its west, so the label (a band to the
 *  pin's right, about 150 m tall) reads better on the left. */
internal fun routeLeavesRight(visit:Visit,legs:List<RouteLeg>):Boolean {
    val level=legs.flatMap {it.points}.filter {point->
        kotlin.math.abs(point.coordinate.latitude-visit.coordinate.latitude)*111_000<=150 && point.coordinate.distanceTo(visit.coordinate) in 30.0..2000.0
    }
    val east=level.count {it.coordinate.longitude>visit.coordinate.longitude}
    return east>level.size-east
}

private fun splitAtGaps(points:List<TrackPoint>):List<List<TrackPoint>> {
    val lines=mutableListOf<List<TrackPoint>>();var current=mutableListOf<TrackPoint>()
    points.sortedWith(compareBy({it.time},{it.id})).forEach {point->
        if(current.lastOrNull()?.let {Duration.between(it.time,point.time)>Duration.ofMinutes(10)}==true) {if(current.size>1) lines+=current;current=mutableListOf()}
        current.add(point)
    }
    if(current.size>1) lines+=current
    return lines
}

/** True when the system asks for less motion (animator duration scale off): pulses stop, replay jumps. */
@Composable internal fun rememberReduceMotion():Boolean {
    val context=LocalContext.current
    return remember(context) {Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f}
}

/** The full-bleed map with the journal's status lines floating under the header instead of below a frame. */
@Composable internal fun JournalMapHomeFrame(modifier:Modifier,home:MapHomeOptions,nativeMap:@Composable (Modifier)->Unit,markerLoad:MapPointMarkerLoad,selectedPoint:TrackPoint?,metric:Boolean,metersPerDp:Double?,focusedVisit:Visit?,
    closePoint:()->Unit,tilesFailed:Boolean,roadEstimate:Boolean,credits:List<RoadAttribution>,photoLayer:PhotoMapLayer?,online:Boolean,browsePoints:()->Unit,browseVisits:(()->Unit)?) {
    val colors=wd
    val pointsLabel=stringResource(R.string.map_point_browse)
    val visitsLabel=stringResource(R.string.map_visits)
    val uri=androidx.compose.ui.platform.LocalUriHandler.current
    Box(modifier.fillMaxSize().background(Color(MapStyles.land(colors.dark)))) {
        nativeMap(Modifier.fillMaxSize().semantics {
            // The sampled-point and visit choosers stay reachable without precise taps.
            customActions=listOfNotNull(CustomAccessibilityAction(pointsLabel) {browsePoints();true},browseVisits?.let {CustomAccessibilityAction(visitsLabel) {it();true}})
        })
        Column(Modifier.align(Alignment.TopStart).padding(top=home.overlayTop,start=12.dp,end=80.dp).widthIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            focusedVisit?.let {visit->
                Row(Modifier.wdFloating(CircleShape,colors,4.dp).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors.placeInks[1]))
                    Text(stringResource(R.string.map_selected_visit,visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit)),Modifier.testTag("map-focused-visit"),
                        style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=colors.textPrimary)
                }
            }
            selectedPoint?.let {MapPointTooltip(it,metric,closePoint)}
            if(markerLoad.loading) Row(Modifier.wdFloating(CircleShape,colors,4.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp),color=colors.accent,trackColor=colors.dialTrack,strokeWidth=2.dp)
                Text(stringResource(R.string.map_point_loading),Modifier.testTag("map-point-loading"),style=WdType.caption2,color=colors.textMuted)
            }
            if(markerLoad.failed) Row(Modifier.wdFloating(RoundedCornerShape(14.dp),colors,4.dp).padding(start=12.dp,end=4.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.map_point_load_failed),Modifier.weight(1f,fill=false).testTag("map-point-load-failed"),style=MaterialTheme.typography.bodySmall,color=colors.inkRust)
                TraceCapsule(stringResource(R.string.map_point_retry),markerLoad.retry,Modifier.testTag("map-point-retry"),icon=Icons.Outlined.Refresh,compact=true)
            }
            if(tilesFailed) WdNotice(stringResource(R.string.map_home_tiles_failed),icon=Icons.Outlined.CloudOff,warning=true)
            photoLayer?.browsePlaces?.let {browse->TraceCapsule(stringResource(R.string.map_browse_photos),browse,Modifier.testTag("browse-photo-places"),icon=Icons.Outlined.PhotoAlbum,floating=true)}
            photoLayer?.retryDirectory?.let {retry->TraceCapsule(stringResource(R.string.map_retry_photos),retry,icon=Icons.Outlined.Refresh,floating=true)}
            photoLayer?.directoryMessage?.let {Text(it,Modifier.wdFloating(RoundedCornerShape(12.dp),colors,2.dp).padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.bodySmall,color=colors.textMuted)}
        }
        // Road estimates name their sources; street tiles carry OpenFreeMap / OpenMapTiles / OpenStreetMap credit.
        Column(Modifier.align(Alignment.BottomStart).padding(bottom=(home.overlayBottom ?: home.padding.calculateBottomPadding())+4.dp,start=10.dp,end=80.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(roadEstimate) Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.testTag("road-attributions")) {
                credits.distinct().forEach {credit->
                    Text(stringResource(if(credit.kind==RoadAttributionKind.provider) R.string.map_road_provider_credit else R.string.map_road_data_credit,credit.name),
                        Modifier.clip(CircleShape).background(colors.card.copy(alpha=0.85f)).padding(horizontal=8.dp,vertical=3.dp).clickable(role=androidx.compose.ui.semantics.Role.Button) {runCatching {uri.openUri(credit.url)}},style=WdType.caption2,color=colors.textMuted)
                }
            }
            metersPerDp?.let {MapScaleBar(it,metric)}
            if(online) Text(stringResource(R.string.map_home_tiles_credit),Modifier.clip(CircleShape).background(colors.card.copy(alpha=0.7f)).padding(horizontal=6.dp,vertical=1.dp),style=WdType.caption2.copy(fontSize=androidx.compose.ui.unit.TextUnit(8.5f,androidx.compose.ui.unit.TextUnitType.Sp),letterSpacing=0.01.em),color=colors.textMuted)
        }
    }
}


/** M3's navy tooltip for a tapped GPS point: time, accuracy and coordinates (old PathPointTooltip), with the full inspector one tap away. */
@Composable internal fun MapPointTooltip(point:TrackPoint,metric:Boolean,close:()->Unit) {
    val colors=wd
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var expanded by remember(point.id) {mutableStateOf(false)}
    if(expanded) {MapPointInspection(point,metric,close);return}
    val time=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(point.time)
    val accuracy=if(metric) "±%.0f M".format(locale,point.accuracy) else "±%.0f FT".format(locale,point.accuracy/0.3048)
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.inkCard).testTag("map-point-tooltip")
        .clickable(role=androidx.compose.ui.semantics.Role.Button) {expanded=true}.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(time,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.04.em),color=colors.onInkCard)
        Text("$accuracy · %.4f, %.4f".format(locale,point.coordinate.latitude,point.coordinate.longitude).replace("-","−"),style=WdType.caption2.copy(fontSize=androidx.compose.ui.unit.TextUnit(9f,androidx.compose.ui.unit.TextUnitType.Sp)),color=colors.onInkCardMuted)
        if(point.outlier) Text(stringResource(R.string.map_point_outlier),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold),color=colors.liveDot)
    }
}

/** The map scale (parity with the old map's MapScaleView): a hairline bar with a round distance, up to 80dp long. */
@Composable internal fun MapScaleBar(metersPerDp:Double,metric:Boolean) {
    val colors=wd
    val unit=if(metric) 1.0 else 0.3048
    val maxUnits=80*metersPerDp/unit
    if(!maxUnits.isFinite() || maxUnits<=0) return
    val magnitude=Math.pow(10.0,kotlin.math.floor(kotlin.math.log10(maxUnits)))
    val step=listOf(5.0,2.0,1.0).map {it*magnitude}.first {it<=maxUnits}
    val widthDp=(step*unit/metersPerDp).toFloat()
    val label=when {
        metric && step>=1000->"${(step/1000).let {if(it%1.0==0.0) it.toInt().toString() else it.toString()}} km"
        metric->"${step.toInt()} m"
        step>=5280->"${(step/5280).let {if(it%1.0==0.0) it.toInt().toString() else "%.1f".format(Locale.ROOT,it)}} mi"
        else->"${step.toInt()} ft"
    }
    Row(Modifier.semantics(mergeDescendants=true) {}.testTag("map-scale"),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(widthDp.dp).height(5.dp).drawBehindCompat(colors.textMuted))
        Text(label,style=WdType.caption2.copy(fontSize=androidx.compose.ui.unit.TextUnit(9f,androidx.compose.ui.unit.TextUnitType.Sp)),color=colors.textMuted)
    }
}
private fun Modifier.drawBehindCompat(color:Color)=this.then(Modifier.drawBehind {
    val stroke=1.dp.toPx()
    drawLine(color,androidx.compose.ui.geometry.Offset(0f,size.height),androidx.compose.ui.geometry.Offset(size.width,size.height),stroke)
    drawLine(color,androidx.compose.ui.geometry.Offset(0f,0f),androidx.compose.ui.geometry.Offset(0f,size.height),stroke)
    drawLine(color,androidx.compose.ui.geometry.Offset(size.width,0f),androidx.compose.ui.geometry.Offset(size.width,size.height),stroke)
})
