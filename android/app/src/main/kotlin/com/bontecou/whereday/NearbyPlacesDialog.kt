package com.bontecou.whereday

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import java.text.NumberFormat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Opening this dialog never starts a lookup. Suggestions only change the unsaved editor draft. */
@Composable internal fun NearbyPlacesDialog(
    origin:Coordinate,service:PlaceLookupService,networkAllowed:Flow<Boolean>,available:Boolean,
    dismiss:()->Unit,select:(NearbyPlaceSuggestion)->Unit,
    currentFix:CurrentVisitLocation?=null,elapsedMillis:()->Long={SystemClock.elapsedRealtime()},metric:Boolean=true
) {
    val allowed by networkAllowed.collectAsState(initial=false)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    val uri=LocalUriHandler.current
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var filter by remember(origin) {mutableStateOf("")}
    var results by remember(origin) {mutableStateOf<List<NearbyPlaceSuggestion>>(emptyList())}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<Int?>(null)}
    var job by remember {mutableStateOf<Job?>(null)}
    var generation by remember {mutableIntStateOf(0)}
    val elapsed by produceState(elapsedMillis()) {while(true) {delay(1000);value=elapsedMillis()}}
    fun fresh()=currentFix==null || currentFix.isUsable(maxOf(elapsed,elapsedMillis()))
    fun clear() {generation++;job?.cancel();job=null;results=emptyList();busy=false;message=null;filter=""}
    LaunchedEffect(origin) {clear()}
    LaunchedEffect(allowed,fresh()) {if(!allowed || !fresh()) clear()}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) clear()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);job?.cancel()}
    }
    WorkspaceAlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.nearby_title))},text={
        Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("nearby-content").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.nearby_disclosure),style=WdType.caption2,color=wd.textMuted)
            when {
                !available->WdNotice(stringResource(R.string.nearby_unavailable),icon=Icons.Outlined.CloudOff,warning=true)
                !allowed->WdNotice(stringResource(R.string.nearby_disabled),icon=Icons.Outlined.CloudOff)
                !fresh()->WdNotice(stringResource(R.string.nearby_expired),icon=Icons.Outlined.TimerOff,warning=true)
            }
            WdButton(stringResource(R.string.nearby_search),enabled=available && allowed && fresh() && !busy,modifier=Modifier.testTag("nearby-search"),emphasis=WdEmphasis.primary,icon=Icons.Outlined.Storefront,onClick={
                clear();busy=true
                val request=generation
                job=scope.launch {
                    try {
                        val found=service.nearby(origin,radiusMeters=1000.0,limit=6)
                        if(request==generation && networkAllowed.first() && fresh() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            results=found;message=if(found.isEmpty()) R.string.nearby_empty else null
                        }
                    } catch(_:TimeoutCancellationException) {if(request==generation) message=R.string.nearby_error}
                    catch(error:CancellationException) {throw error}
                    catch(_:PlaceLookupDisabled) {if(request==generation) message=R.string.nearby_disabled}
                    catch(_:NearbyPlaceLookupUnavailable) {if(request==generation) message=R.string.nearby_unavailable}
                    catch(_:Exception) {if(request==generation) message=R.string.nearby_error}
                    finally {if(request==generation) busy=false}
                }
            })
            if(busy) VisitPickerProgress(stringResource(R.string.nearby_loading))
            message?.let {VisitPickerNote(stringResource(it),if(it==R.string.nearby_empty) Icons.Outlined.SearchOff else Icons.Outlined.ErrorOutline)}
            // Hide immediately on opt-out/expiry, before the clearing effect has run.
            if(allowed && fresh() && results.isNotEmpty()) {
                OutlinedTextField(filter,{filter=it},label={Text(stringResource(R.string.nearby_filter))},singleLine=true,colors=wdTextFieldColors(),leadingIcon={Icon(Icons.Outlined.FilterList,null,tint=wd.textMuted)},modifier=Modifier.fillMaxWidth().testTag("nearby-filter"))
                if(filter.isNotEmpty()) TextButton(onClick={filter=""},modifier=Modifier.testTag("nearby-filter-clear")) {Text(stringResource(R.string.nearby_filter_clear))}
                val filtered=remember(results,filter,locale) {filterNearbyPlaceSuggestions(results,filter,locale.toLanguageTag())}
                if(filtered.isEmpty()) Text(stringResource(R.string.nearby_filter_empty,filter.trim()),Modifier.testTag("nearby-filter-empty"),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                filtered.forEach {suggestion->
                WdCard {
                    Column(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(suggestion.name,style=WdType.title3,color=wd.textPrimary)
                        suggestion.address?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
                        Text(stringResource(R.string.nearby_distance,nearbyDistanceText(suggestion.distanceMeters,metric,locale)),Modifier.testTag("nearby-distance-${suggestion.id}"),style=WdType.caption,color=wd.textMuted)
                        TextButton(modifier=Modifier.testTag("nearby-use-${suggestion.id}"),onClick={
                            val request=generation
                            scope.launch {
                                if(networkAllowed.first() && request==generation && suggestion in filterNearbyPlaceSuggestions(results,filter,locale.toLanguageTag()) && fresh() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) select(suggestion)
                            }
                        }) {Text(stringResource(R.string.nearby_use),fontWeight=FontWeight.SemiBold)}
                    }
                }
            }
            }
            TextButton(onClick={uri.openUri("https://www.geoapify.com/")}) {Text(stringResource(R.string.nearby_attribution),style=WdType.caption2)}
            TextButton(onClick={uri.openUri("https://www.openstreetmap.org/copyright")}) {Text(stringResource(R.string.nearby_osm),style=WdType.caption2)}
        }
    },confirmButton={TextButton(onClick=dismiss,modifier=Modifier.testTag("nearby-close")) {Text(stringResource(R.string.nearby_close))}})
}

@Composable internal fun nearbyDistanceText(meters:Double,metric:Boolean,locale:Locale):String {
    val (value,unit)=when {
        metric && meters<1000->meters to R.string.nearby_meters
        metric->meters/1000 to R.string.nearby_kilometers
        meters<1609.344->meters/0.3048 to R.string.nearby_feet
        else->meters/1609.344 to R.string.nearby_miles
    }
    val number=NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=if(unit==R.string.nearby_meters || unit==R.string.nearby_feet) 0 else 1}
    return stringResource(unit,number.format(value))
}
