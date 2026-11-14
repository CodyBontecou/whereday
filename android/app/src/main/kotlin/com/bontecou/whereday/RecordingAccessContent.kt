package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

@Composable internal fun RecordingAccessNotice(state:RecordingAccessState,options:(PermissionAccessKind)->Unit,location:Boolean=true,notifications:Boolean=true) {
    if((!location || state.location==LocationPrecision.precise) && (!notifications || state.notifications) && state.failure==null) return
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).testTag("recording-access-notice").semantics {liveRegion=LiveRegionMode.Polite},verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(location && state.location!=LocationPrecision.precise) {
            Text(stringResource(when {state.location==LocationPrecision.approximate->R.string.permission_approximate_body;state.locationDeclined->R.string.permission_location_denied;else->R.string.permission_location_none}),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick={options(if(state.location==LocationPrecision.approximate) PermissionAccessKind.precise else PermissionAccessKind.location)},modifier=Modifier.testTag("location-access-options")) {Text(stringResource(R.string.permission_location_options))}
        }
        if(notifications && !state.notifications) {
            Text(stringResource(R.string.permission_notifications_body),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick={options(PermissionAccessKind.notifications)},modifier=Modifier.testTag("notification-access-options")) {Text(stringResource(R.string.permission_notification_options))}
        }
        state.failure?.let {Text(stringResource(if(it==PermissionAccessFailure.settings) R.string.permission_settings_failed else R.string.permission_request_failed),color=MaterialTheme.colorScheme.error)}
    }
}

/** Reviews are ephemeral, single use, and cannot grant an action after background/disposal. */
@Composable internal fun PermissionAccessReview(review:PermissionReview?,dismiss:(Long)->Unit,request:(Long)->Unit,settings:(Long)->Unit) {
    val current=rememberUpdatedState(review)
    review?.let {ticket->key(ticket.id,ticket.generation,ticket.foreground) {
        BlockVisitLinks()
        val lifecycle=LocalLifecycleOwner.current.lifecycle
        val active=remember {AtomicBoolean(true)}
        val consumed=remember {AtomicBoolean(false)}
        val latestDismiss=rememberUpdatedState(dismiss)
        val latestRequest=rememberUpdatedState(request)
        val latestSettings=rememberUpdatedState(settings)
        fun consume(action:()->Unit) {if(active.get() && current.value==ticket && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && consumed.compareAndSet(false,true)) action()}
        DisposableEffect(lifecycle) {
            val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {consumed.set(true);latestDismiss.value(ticket.id)}}
            lifecycle.addObserver(observer)
            onDispose {active.set(false);lifecycle.removeObserver(observer)}
        }
        val title=when(ticket.kind) {PermissionAccessKind.location->R.string.permission_location_title;PermissionAccessKind.precise->R.string.permission_approximate_title;PermissionAccessKind.notifications->R.string.permission_notifications_title}
        val body=when(ticket.kind) {PermissionAccessKind.location->R.string.permission_review_location;PermissionAccessKind.precise->R.string.permission_approximate_body;PermissionAccessKind.notifications->R.string.permission_notifications_body}
        val action=when {ticket.kind==PermissionAccessKind.notifications->R.string.permission_request_notifications;ticket.kind==PermissionAccessKind.precise->R.string.permission_request_precise;ticket.start->R.string.permission_request_location_start;else->R.string.permission_request_location}
        WorkspaceAlertDialog(onDismissRequest={consume {latestDismiss.value(ticket.id)}},modifier=Modifier.testTag("permission-access-review"),
            title={Text(stringResource(title))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(stringResource(body));Text(stringResource(R.string.permission_settings_help),style=MaterialTheme.typography.bodySmall)
                if(ticket.requestAllowed) TextButton(onClick={consume {latestSettings.value(ticket.id)}},modifier=Modifier.testTag("permission-review-settings")) {Text(stringResource(R.string.permission_open_settings))}
            }},
            confirmButton={TextButton(onClick={consume {if(ticket.requestAllowed) latestRequest.value(ticket.id) else latestSettings.value(ticket.id)}},modifier=Modifier.testTag("permission-review-confirm")) {Text(stringResource(if(ticket.requestAllowed) action else R.string.permission_open_settings))}},
            dismissButton={TextButton(onClick={consume {latestDismiss.value(ticket.id)}},modifier=Modifier.testTag("permission-review-dismiss")) {Text(stringResource(R.string.permission_not_now))}})
    }}
}
