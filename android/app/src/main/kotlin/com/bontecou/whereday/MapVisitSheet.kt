package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A nearby place the visit might have been: a saved place first, then a nearby business when lookups are allowed. */
internal data class VisitCandidate(val id:String,val name:String,val address:String?,val coordinate:Coordinate,val distanceMeters:Double,val saved:Boolean)

/** Saved places within 300 m of the visit, then nearby businesses, nearest first; at most three, numbered as on the map. */
@Composable internal fun rememberVisitCandidates(graph:AppGraph,visit:Visit?):List<VisitCandidate> {
    val places by graph.history.dao.observePlaces().collectAsStateWithLifecycle(emptyList())
    var nearby by remember(visit?.id) {mutableStateOf<List<VisitCandidate>>(emptyList())}
    LaunchedEffect(visit?.id) {
        val target=visit ?: return@LaunchedEffect
        nearby=try {graph.placeLookup.nearby(target.coordinate,300.0,5).map {VisitCandidate(it.id,it.name,it.address,it.coordinate,it.distanceMeters,false)}}
            catch(e:CancellationException) {throw e} catch(_:Exception) {emptyList()}
    }
    if(visit==null) return emptyList()
    val saved=places.map {it.value}.map {VisitCandidate("saved-${it.id}",it.name,it.address,it.coordinate,it.coordinate.distanceTo(visit.coordinate),true)}.filter {it.distanceMeters<=300}
    return (saved.sortedBy {it.distanceMeters}+nearby.sortedBy {it.distanceMeters}).distinctBy {it.name.lowercase()}
        .filterNot {it.name.equals(visit.name?.trim(),ignoreCase=true) && it.distanceMeters<30}.take(3)
}

/** M5: VisitQuickView over the focused map: status, name and times, "Where were you?" and Not here / Details / Confirm. */
@Composable internal fun MapVisitSheet(graph:AppGraph,visit:Visit,candidates:List<VisitCandidate>,metric:Boolean,onClose:()->Unit,onNotHere:()->Unit,onDetails:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val scope=rememberCoroutineScope()
    var chosen by remember(visit.id) {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var failed by remember {mutableStateOf(false)}
    val zone=ZoneId.systemDefault()
    val day=visit.arrivedAt.atZone(zone).toLocalDate()
    val dayLabel=when(day) {LocalDate.now(zone)->stringResource(R.string.map_date_today);LocalDate.now(zone).minusDays(1)->stringResource(R.string.map_date_yesterday)
        else->rangeDayKicker(day,locale)}
    val end=visit.departedAt
    val times=listOfNotNull(dayLabel,dayClockText(visit.arrivedAt)+" – "+(end?.let(::dayClockText) ?: stringResource(R.string.timeline_redesign_now)),
        homeDuration(Duration.between(visit.arrivedAt,end ?: Instant.now()),mapHomeStrings())).joinToString(" · ").uppercase(locale)
    val (badge,badgeInk,badgeFill)=when(visit.confirmation) {
        Confirmation.unconfirmed->Triple(stringResource(R.string.map_home_badge_detected),if(colors.dark) Color(0xFFE0B565) else Color(0xFF8A5A12),if(colors.dark) Color(0x33E0B565) else Color(0xFFF1E3C6))
        Confirmation.confirmed->Triple(stringResource(R.string.timeline_confirmed),colors.success,colors.success.copy(alpha=0.14f))
        Confirmation.corrected->Triple(stringResource(R.string.timeline_corrected),colors.success,colors.success.copy(alpha=0.14f))
    }
    val title=visit.name?.trim()?.takeIf {it.isNotEmpty()} ?: candidates.firstOrNull()?.let {stringResource(R.string.map_home_near,it.name)} ?: stringResource(R.string.timeline_visit)
    Column(modifier.fillMaxWidth().shadow(18.dp,RoundedCornerShape(topStart=26.dp,topEnd=26.dp),ambientColor=colors.textPrimary.copy(alpha=0.14f),spotColor=colors.textPrimary.copy(alpha=0.14f))
        .clip(RoundedCornerShape(topStart=26.dp,topEnd=26.dp)).background(colors.surface).navigationBarsPadding()
        .heightIn(max=560.dp).verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,top=18.dp,bottom=24.dp)
        .semantics {testTagsAsResourceId=true}.testTag("map-visit-sheet"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.Top) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(badge.uppercase(locale),Modifier.clip(RoundedCornerShape(6.dp)).background(badgeFill).padding(horizontal=8.dp,vertical=4.dp).testTag("map-visit-badge"),
                    style=WdType.caption2.copy(fontSize=9.5.sp,fontWeight=FontWeight.SemiBold,letterSpacing=0.1.em),color=badgeInk)
                Text(title,style=WdType.title.copy(fontSize=26.sp,lineHeight=30.sp),color=colors.textPrimary,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.semantics {heading()}.testTag("map-visit-title"))
                Text(times,style=WdType.caption2.copy(letterSpacing=0.1.em),color=colors.textMuted,modifier=Modifier.testTag("map-visit-times"))
            }
            IconButton(onClick=onClose,Modifier.size(44.dp).testTag("map-visit-close")) {Icon(Icons.Outlined.Close,stringResource(R.string.map_visits_close),tint=colors.textMuted)}
        }
        if(candidates.isNotEmpty()) {
            Text(stringResource(R.string.map_home_where_were_you).uppercase(locale),style=WdType.caption2.copy(fontSize=10.5.sp,letterSpacing=0.15.em,fontWeight=FontWeight.Medium),color=colors.textMuted,
                modifier=Modifier.padding(top=4.dp).semantics {heading()})
            candidates.forEachIndexed {index,candidate->
                val selected=chosen==candidate.id
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(14.dp)).background(colors.card)
                    .border(if(selected) 2.dp else 1.dp,if(selected) colors.accent else colors.border,RoundedCornerShape(14.dp))
                    .selectable(selected,role=Role.RadioButton) {chosen=if(selected) null else candidate.id}.padding(horizontal=12.dp).testTag("map-visit-candidate-${index+1}"),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(colors.card).border(1.5.dp,colors.textPrimary,CircleShape),contentAlignment=Alignment.Center) {
                        Text("${index+1}",style=WdType.caption2.copy(fontSize=10.sp,fontWeight=FontWeight.SemiBold),color=colors.textPrimary)
                    }
                    Text(candidate.name,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium,fontSize=14.sp),color=colors.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(dayDistance(candidate.distanceMeters,metric,locale),style=WdType.caption2,color=colors.textMuted)
                }
            }
        }
        if(failed) Text(stringResource(R.string.visit_error_save),style=MaterialTheme.typography.bodySmall,color=colors.inkRust)
        Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            WdButton(stringResource(R.string.map_home_not_here),onNotHere,Modifier.weight(1f).heightIn(min=46.dp).testTag("map-visit-not-here"),enabled=!busy)
            WdButton(stringResource(R.string.map_home_details),onDetails,Modifier.weight(1f).heightIn(min=46.dp).testTag("map-visit-details"),enabled=!busy)
            val confirmed=visit.confirmation!=Confirmation.unconfirmed && chosen==null
            WdButton(stringResource(R.string.map_home_confirm),{
                busy=true;failed=false
                scope.launch {
                    try {
                        val now=Instant.now()
                        val pick=candidates.firstOrNull {it.id==chosen}
                        val updated=if(pick!=null) visit.correct(pick.coordinate,pick.name,pick.address,now)
                            else visit.copy(confirmation=Confirmation.confirmed,confirmedAt=visit.confirmedAt ?: now,updatedAt=now)
                        graph.history.saveVisit(updated,visit)
                        onClose()
                    } catch(e:CancellationException) {throw e} catch(_:Exception) {failed=true} finally {busy=false}
                }
            },Modifier.weight(1.4f).heightIn(min=46.dp).testTag("map-visit-confirm"),emphasis=WdEmphasis.primary,enabled=!busy && !confirmed)
        }
    }
}
