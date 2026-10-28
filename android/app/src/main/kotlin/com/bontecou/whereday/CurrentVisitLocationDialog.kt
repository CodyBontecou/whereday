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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*

@Composable internal fun CurrentVisitLocationDialog(source:CurrentVisitLocationSource,dismiss:()->Unit,select:(CurrentVisitLocation)->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    var job by remember {mutableStateOf<Job?>(null)}
    var busy by remember {mutableStateOf(false)}
    var location by remember {mutableStateOf<CurrentVisitLocation?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var denied by remember {mutableStateOf(false)}
    val elapsed by produceState(SystemClock.elapsedRealtime()) {while(true) {delay(1000);value=SystemClock.elapsedRealtime()}}
    fun request() {
        job?.cancel();location=null;message=null;busy=true
        job=scope.launch {
            try {
                val fix=source.locate()
                if(fix?.isUsable(SystemClock.elapsedRealtime())==true) location=fix
                else message="No fresh accurate location was found. Check device location settings, move where GPS is available, or choose a saved place."
            } catch(_:TimeoutCancellationException) {message="Location request timed out. Try again or choose a saved place."}
            catch(error:CancellationException) {throw error}
            catch(_:SecurityException) {denied=true;message="Location access is unavailable. Allow it in app settings or choose a saved place."}
            catch(_:Exception) {message="Current location is unavailable. Check device location settings or choose a saved place."}
            finally {busy=false}
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {result->
        if(result.values.any {it}) request() else {denied=true;message="Location access was not allowed. You can still choose a saved place."}
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {job?.cancel();job=null;busy=false}}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);job?.cancel()}
    }
    val usable=location?.takeIf {it.isUsable(maxOf(elapsed,SystemClock.elapsedRealtime()))}
    AlertDialog(onDismissRequest=dismiss,title={Text("Find current location")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Request a single location for this visit. Recording stays unchanged. No address lookup is performed.")
            Text("Use a fix from the last 5 minutes with accuracy within 100 m, or choose a saved place instead.")
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Finding current location…")}
            else OutlinedButton(onClick={
                if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) request()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
            }) {Text("Request location")}
            usable?.let {Text("Current location ready");Text("${it.coordinate.latitude}, ${it.coordinate.longitude} · ±${it.accuracyMeters.toInt()} m")}
            if(location!=null && usable==null) Text("This location has expired. Request a new location.")
            message?.let {Text(it)}
            if(denied) TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:${context.packageName}".toUri()))}) {Text("Open app permissions")}
        }
    },confirmButton={TextButton(enabled=usable!=null && !busy,onClick={location?.takeIf {it.isUsable(SystemClock.elapsedRealtime())}?.let(select)}) {Text("Use current location")}},
        dismissButton={TextButton(onClick=dismiss) {Text("Cancel current location")}})
}
