package com.bontecou.whereday

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.CalendarEventDraft
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun CalendarEventDraft.calendarIntent()=Intent(Intent.ACTION_INSERT,CalendarContract.Events.CONTENT_URI).apply {
    putExtra(CalendarContract.Events.TITLE,title)
    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,startedAt.toEpochMilli())
    putExtra(CalendarContract.EXTRA_EVENT_END_TIME,endedAt.toEpochMilli())
    putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY,false)
    location?.let {putExtra(CalendarContract.Events.EVENT_LOCATION,it)}
}

/** Only a current, foreground action may hand a snapshot to another application. */
@Composable internal fun CalendarEventButton(sourceKey:Any,enabled:Boolean=true,
    makeDraft:suspend (Instant)->CalendarEventDraft,launch:((CalendarEventDraft)->Unit)?=null) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val scope=rememberCoroutineScope()
    val alive=remember(sourceKey,owner) {AtomicBoolean(true)}
    val generation=remember(sourceKey,owner) {AtomicLong()}
    var busy by remember(sourceKey,owner) {mutableStateOf(false)}
    var error by remember(sourceKey,owner) {mutableStateOf<Int?>(null)}
    var job by remember(sourceKey,owner) {mutableStateOf<Job?>(null)}
    val latestKey by rememberUpdatedState(sourceKey)
    val latestEnabled by rememberUpdatedState(enabled)
    val currentMake by rememberUpdatedState(makeDraft)
    val currentLaunch by rememberUpdatedState(launch ?: {draft:CalendarEventDraft->context.startActivity(draft.calendarIntent())})
    fun current()=alive.get() && sourceKey==latestKey && latestEnabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(alive,owner) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) {generation.incrementAndGet();job?.cancel();job=null;busy=false}
            if(event==Lifecycle.Event.ON_RESUME) busy=false
        }
        owner.lifecycle.addObserver(observer)
        onDispose {alive.set(false);job?.cancel();owner.lifecycle.removeObserver(observer)}
    }
    key(alive) { Column {
        OutlinedButton(enabled=enabled && !busy,onClick={
            if(current() && !busy) {
                busy=true;error=null
                val prepare=currentMake;val handoff=currentLaunch;val now=Instant.now();val ticket=generation.get()
                job=scope.launch {
                    var handedOff=false
                    try {
                        val draft=prepare(now)
                        if(current() && ticket==generation.get()) {handoff(draft);handedOff=true}
                    } catch(e:CancellationException) {throw e}
                    catch(e:ActivityNotFoundException) {if(current() && ticket==generation.get()) error=R.string.calendar_unavailable}
                    catch(e:SecurityException) {if(current() && ticket==generation.get()) error=R.string.calendar_unavailable}
                    catch(e:Exception) {if(current() && ticket==generation.get()) error=R.string.calendar_prepare_error}
                    finally {if(alive.get() && ticket==generation.get() && !handedOff) busy=false}
                }
            }
        },modifier=Modifier.testTag("calendar-add")) {Text(stringResource(if(busy) R.string.calendar_opening else R.string.calendar_add))}
        error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error,
            modifier=Modifier.testTag("calendar-error").semantics {liveRegion=LiveRegionMode.Polite})}
    } }
}
