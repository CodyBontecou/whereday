package com.bontecou.whereday

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import java.text.NumberFormat
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*

@Composable internal fun CurrentVisitLocationDialog(
    source:CurrentVisitLocationSource,dismiss:()->Unit,places:List<SavedPlace>? = emptyList(),
    selectSaved:((SavedPlace)->Unit)?=null,nearby:PlaceLookupService?=null,
    networkAllowed:Flow<Boolean>?=null,nearbyAvailable:Boolean=false,
    selectBusiness:((NearbyPlaceSuggestion)->Unit)?=null,readSavedPlaces:(suspend ()->List<SavedPlace>?)?=null,metric:Boolean=true,
    select:(CurrentVisitLocation)->Unit
) {
    val context=LocalContext.current
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    var job by remember {mutableStateOf<Job?>(null)}
    var generation by remember {mutableIntStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    var location by remember {mutableStateOf<CurrentVisitLocation?>(null)}
    var message by remember {mutableStateOf<Int?>(null)}
    var denied by remember {mutableStateOf(false)}
    val hasBusinessLookup=nearbyAvailable && nearby!=null && networkAllowed!=null && selectBusiness!=null && readSavedPlaces!=null
    val elapsed by produceState(SystemClock.elapsedRealtime()) {while(true) {delay(1000);value=SystemClock.elapsedRealtime()}}
    fun request() {
        if(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        generation++;val request=generation
        job?.cancel();location=null;message=null;busy=true;denied=false
        job=scope.launch {
            try {
                val fix=source.locate()
                currentCoroutineContext().ensureActive()
                if(request==generation) {
                    if(fix?.isUsable(SystemClock.elapsedRealtime())==true) location=fix
                    else message=R.string.current_location_no_fix
                }
            } catch(_:TimeoutCancellationException) {if(request==generation) message=R.string.current_location_timeout}
            catch(error:CancellationException) {throw error}
            catch(_:SecurityException) {if(request==generation) {denied=true;message=R.string.current_location_access_error}}
            catch(_:Exception) {if(request==generation) message=R.string.current_location_error}
            finally {if(request==generation) busy=false}
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {result->
        if(result.values.any {it}) request() else {denied=true;message=R.string.current_location_denied}
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {generation++;job?.cancel();job=null;busy=false;location=null;message=R.string.current_location_interrupted}}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);job?.cancel()}
    }
    val usable=location?.takeIf {it.isUsable(maxOf(elapsed,SystemClock.elapsedRealtime()))}
    WorkspaceAlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.visit_find_current))},text={
        Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("current-location-content").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if(hasBusinessLookup) R.string.current_location_business_disclosure else R.string.current_location_disclosure))
            Text(stringResource(R.string.current_location_quality))
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.current_location_loading))}
            else OutlinedButton(modifier=Modifier.testTag("current-location-request"),onClick={
                if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) request()
                else {generation++;job?.cancel();location=null;message=null;busy=false;permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
            }) {Text(stringResource(R.string.current_location_request))}
            usable?.let {
                Text(stringResource(R.string.current_location_ready))
                val number=NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=0}
                Text(stringResource(R.string.current_location_coordinate,String.format(locale,"%.5f",it.coordinate.latitude),String.format(locale,"%.5f",it.coordinate.longitude),number.format(it.accuracyMeters)),Modifier.testTag("current-location-coordinate"))
            }
            if(!busy && selectSaved!=null) CurrentSavedPlaceSuggestion(location,places.orEmpty(),elapsed,selectSaved)
            if(!busy && hasBusinessLookup) CurrentBusinessSuggestion(location,places,elapsed,nearby!!,networkAllowed!!,nearbyAvailable,readSavedPlaces!!,selectBusiness!!,metric=metric)
            if(location!=null && usable==null) Text(stringResource(R.string.current_location_expired))
            message?.let {Text(stringResource(it))}
            if(denied) TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:${context.packageName}".toUri()))}) {Text(stringResource(R.string.current_location_permissions))}
        }
    },confirmButton={TextButton(modifier=Modifier.testTag("current-location-use"),enabled=usable!=null && !busy,onClick={location?.takeIf {it.isUsable(SystemClock.elapsedRealtime())}?.let(select)}) {Text(stringResource(R.string.current_location_use))}},
        dismissButton={TextButton(modifier=Modifier.testTag("current-location-close"),onClick=dismiss) {Text(stringResource(R.string.current_location_cancel))}})
}

@Composable internal fun CurrentSavedPlaceSuggestion(fix:CurrentVisitLocation?,places:List<SavedPlace>,nowElapsedMillis:Long,use:(SavedPlace)->Unit,elapsedMillis:()->Long={SystemClock.elapsedRealtime()}) {
    val latestFix by rememberUpdatedState(fix)
    val latestPlaces by rememberUpdatedState(places)
    val usable=fix?.takeIf {it.isUsable(maxOf(nowElapsedMillis,elapsedMillis()))} ?: return
    val suggestion=remember(usable,places) {usable.savedPlaceSuggestion(places,usable.elapsedMillis)} ?: return
    OutlinedCard(Modifier.fillMaxWidth().semantics {testTagsAsResourceId=true}.testTag("current-saved-place-suggestion")) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.current_saved_place_title),style=MaterialTheme.typography.titleSmall)
            Text(suggestion.name,style=MaterialTheme.typography.titleMedium)
            suggestion.address?.takeIf {it.isNotBlank()}?.let {Text(it)}
            Text(stringResource(R.string.current_saved_place_explanation),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={
                // A queued click cannot use an expired fix or a no-longer-matching place snapshot.
                val current=latestFix?.savedPlaceSuggestion(latestPlaces,maxOf(nowElapsedMillis,elapsedMillis()))
                if(current==suggestion) use(suggestion)
            },modifier=Modifier.testTag("use-current-saved-place")) {Text(stringResource(R.string.current_saved_place_use))}
        }
    }
}
