package com.bontecou.whereday

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
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
import java.util.Locale

/** A fresh user-requested fix checks saved places first, then requests one business within 500 m. */
@Composable internal fun CurrentBusinessSuggestion(
    fix:CurrentVisitLocation?,places:List<SavedPlace>?,nowElapsedMillis:Long,
    service:PlaceLookupService,networkAllowed:Flow<Boolean>,available:Boolean,
    readSavedPlaces:suspend ()->List<SavedPlace>?,select:(NearbyPlaceSuggestion)->Unit,elapsedMillis:()->Long={SystemClock.elapsedRealtime()},metric:Boolean=true
) {
    val allowed by networkAllowed.collectAsState(initial=false)
    val latestFix by rememberUpdatedState(fix)
    val latestPlaces by rememberUpdatedState(places)
    val latestAvailable by rememberUpdatedState(available)
    val latestSavedReader by rememberUpdatedState(readSavedPlaces)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    val uri=LocalUriHandler.current
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var foreground by remember(lifecycle) {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))}
    var paused by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    var generation by remember {mutableIntStateOf(0)}
    var job by remember {mutableStateOf<Job?>(null)}
    var result by remember {mutableStateOf<NearbyPlaceSuggestion?>(null)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<Int?>(null)}
    fun usable(value:CurrentVisitLocation?)=value?.isUsable(maxOf(nowElapsedMillis,elapsedMillis()))==true
    fun noSavedPlace(value:CurrentVisitLocation?)=latestPlaces?.let {value?.savedPlaceSuggestion(it,maxOf(nowElapsedMillis,elapsedMillis()))==null}==true
    val fresh=usable(fix)
    val saved=fix?.savedPlaceSuggestion(places.orEmpty(),maxOf(nowElapsedMillis,elapsedMillis()))
    val eligible=fresh && places!=null && saved==null && available && allowed && foreground && !paused
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP) {
                foreground=false;paused=true;generation++;job?.cancel();result=null;busy=false;message=null
            } else if(event==Lifecycle.Event.ON_START) foreground=true
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);job?.cancel()}
    }
    LaunchedEffect(fix) {paused=false}
    LaunchedEffect(service,networkAllowed,fix,eligible,retry) {
        generation++;val request=generation
        job=currentCoroutineContext().job;result=null;message=null;busy=false
        val origin=fix ?: return@LaunchedEffect
        if(!eligible) return@LaunchedEffect
        busy=true
        try {
            val stored=latestSavedReader() ?: return@LaunchedEffect
            currentCoroutineContext().ensureActive()
            if(origin.savedPlaceSuggestion(stored,maxOf(nowElapsedMillis,elapsedMillis()))!=null || !usable(origin) || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
            val found=service.nearby(origin.coordinate,radiusMeters=500.0,limit=1)
            currentCoroutineContext().ensureActive()
            val currentPlaces=latestSavedReader() ?: return@LaunchedEffect
            if(origin.savedPlaceSuggestion(currentPlaces,maxOf(nowElapsedMillis,elapsedMillis()))!=null) return@LaunchedEffect
            if(request==generation && networkAllowed.first() && latestAvailable && latestFix==origin && usable(latestFix) && noSavedPlace(latestFix) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                result=found.firstOrNull();message=if(result==null) R.string.current_business_empty else null
            }
        } catch(_:TimeoutCancellationException) {if(request==generation) message=R.string.current_business_error}
        catch(error:CancellationException) {throw error}
        catch(_:PlaceLookupDisabled) {if(request==generation) message=R.string.nearby_disabled}
        catch(_:NearbyPlaceLookupUnavailable) {if(request==generation) message=R.string.nearby_unavailable}
        catch(_:Exception) {if(request==generation) message=R.string.current_business_error}
        finally {if(request==generation) busy=false}
    }
    if(!fresh || saved!=null || !available) return
    Column(Modifier.fillMaxWidth().semantics {testTagsAsResourceId=true}.testTag("current-business-section"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.current_business_title),style=MaterialTheme.typography.titleSmall)
        if(places==null) Text(stringResource(R.string.current_business_checking_saved))
        else if(!allowed) Text(stringResource(R.string.nearby_disabled))
        else {
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.current_business_loading))}
            if(paused) Text(stringResource(R.string.current_business_paused))
            else message?.let {Text(stringResource(it))}
            if(!paused && foreground) result?.let {candidate->
                val displayedGeneration=generation
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(candidate.name,style=MaterialTheme.typography.titleMedium)
                        candidate.address?.let {Text(it)}
                        Text(stringResource(R.string.nearby_distance,nearbyDistanceText(candidate.distanceMeters,metric,locale)))
                        Text(stringResource(R.string.current_business_explanation),style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(modifier=Modifier.testTag("current-business-use"),onClick={scope.launch {
                            val stored=try {latestSavedReader()} catch(error:CancellationException) {throw error} catch(_:Exception) {null}
                            val savedNow=stored?.let {latestFix?.savedPlaceSuggestion(it,maxOf(nowElapsedMillis,elapsedMillis()))==null}==true
                            if(savedNow && networkAllowed.first() && latestAvailable && displayedGeneration==generation && result==candidate && latestFix==fix && usable(latestFix) && noSavedPlace(latestFix) && !paused && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) select(candidate)
                        }}) {Text(stringResource(R.string.nearby_use))}
                    }
                }
            }
            if(!busy && (paused || message!=null)) TextButton(enabled=foreground,modifier=Modifier.testTag("current-business-retry"),onClick={paused=false;retry++}) {Text(stringResource(R.string.current_business_retry))}
            TextButton(onClick={uri.openUri("https://www.geoapify.com/")}) {Text(stringResource(R.string.nearby_attribution))}
            TextButton(onClick={uri.openUri("https://www.openstreetmap.org/copyright")}) {Text(stringResource(R.string.nearby_osm))}
        }
    }
}
