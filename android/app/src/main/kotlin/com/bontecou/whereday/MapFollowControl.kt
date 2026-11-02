package com.bontecou.whereday

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bontecou.whereday.domain.MapLocationFix
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import kotlin.math.ceil

internal class MapFollowUi(val enabled:Boolean,val showing:Boolean,val fix:MapLocationFix?,val message:String?,val toggle:()->Unit,val stop:()->Unit,val hide:()->Unit)

/** Explicit user intent lasts only in this composition. Hidden maps collect no location. */
@Composable internal fun rememberMapFollow(source:MapLocationSource?=null,allowed:(()->Boolean)?=null):MapFollowUi {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val focused=LocalWindowInfo.current.isWindowFocused
    val provider=remember(context,source) {source ?: FusedMapLocationSource(context)}
    val permitted=allowed ?: {mapLocationAllowed(context)}
    val currentPermission by rememberUpdatedState(permitted)
    var enabled by remember {mutableStateOf(false)}
    var showing by remember {mutableStateOf(false)}
    var fix by remember {mutableStateOf<MapLocationFix?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var permissionExplanation by remember {mutableStateOf(false)}
    var pendingPermission by remember {mutableStateOf(false)}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {result->
        if(pendingPermission) {
            pendingPermission=false
            if(result.values.any {it}) {showing=true;enabled=true;message=null}
            else message=context.getString(R.string.map_follow_denied)
        }
    }
    LaunchedEffect(showing,focused,provider,lifecycle) {
        if(!showing || !focused) {fix=null;return@LaunchedEffect}
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                fix=null;message=context.getString(R.string.map_follow_finding)
                val started=SystemClock.elapsedRealtime()
                try {coroutineScope {
                    launch {
                        provider.updates().collect {value->
                            val time=SystemClock.elapsedRealtime()
                            if(value.usable(time) && (fix==null || value.elapsedMillis>fix!!.elapsedMillis)) {fix=value;message=null}
                        }
                        error("Location updates ended")
                    }
                    while(isActive) {
                        if(!currentPermission()) throw SecurityException()
                        val time=SystemClock.elapsedRealtime()
                        if(fix?.usable(time)==false) {fix=null;message=context.getString(R.string.map_follow_waiting)}
                        if(fix==null && time-started>=30_000) message=context.getString(R.string.map_follow_waiting)
                        delay(1_000)
                    }
                }} finally {fix=null}
            }
        } catch(canceled:CancellationException) {throw canceled}
        catch(_:Exception) {fix=null;showing=false;enabled=false;message=context.getString(R.string.map_follow_failed)}
    }
    if(permissionExplanation) WorkspaceAlertDialog(onDismissRequest={permissionExplanation=false},title={Text(stringResource(R.string.map_follow_question))},
        text={Text(stringResource(R.string.map_follow_explanation),Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick={permissionExplanation=false;pendingPermission=true;request.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))}) {Text(stringResource(R.string.map_follow_allow))}},
        dismissButton={TextButton(onClick={permissionExplanation=false}) {Text(stringResource(R.string.map_follow_later))}})
    val stop={enabled=false;Unit}
    val hide={enabled=false;showing=false;fix=null;message=null;Unit}
    return MapFollowUi(enabled,showing,fix,message,{
        if(enabled) stop() else if(permitted()) {showing=true;enabled=true;message=null} else permissionExplanation=true
    },stop,hide)
}

@Composable internal fun MapFollowControl(follow:MapFollowUi,metric:Boolean=true) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        FilterChip(follow.enabled,follow.toggle,label={Text(stringResource(if(follow.enabled) R.string.map_following else R.string.map_follow))},modifier=Modifier.testTag("map-follow-location"))
        if(follow.showing) TextButton(onClick=follow.hide,modifier=Modifier.testTag("map-hide-location")) {Text(stringResource(R.string.map_hide_location))}
        follow.message?.let {Text(it,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("map-follow-status"))}
        follow.fix?.let {fix->
            val accuracy=ceil(if(metric) fix.accuracyMeters else fix.accuracyMeters/0.3048).toInt().coerceAtLeast(1)
            Text(stringResource(R.string.map_follow_accuracy,accuracy,if(metric) "m" else "ft"),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("map-location-accuracy"))
        }
    }
}
