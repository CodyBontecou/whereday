package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Recording-access callout: rust ink on paper with quiet text actions, as in the iOS permission cards. */
@Composable internal fun RecordingAccessNotice(state:RecordingAccessState,options:(PermissionAccessKind)->Unit,location:Boolean=true,notifications:Boolean=true,
    contentPadding:PaddingValues=PaddingValues(horizontal=20.dp,vertical=12.dp)) {
    if((!location || state.location==LocationPrecision.precise) && (!notifications || state.notifications) && state.failure==null) return
    val shape=RoundedCornerShape(14.dp)
    val ink=wd.inkRust
    Column(Modifier.fillMaxWidth().padding(contentPadding).testTag("recording-access-notice").semantics {liveRegion=LiveRegionMode.Polite}
        .clip(shape).background(wd.card).background(ink.copy(alpha=if(wd.dark) 0.10f else 0.05f)).border(1.dp,ink.copy(alpha=0.35f),shape)
        .padding(vertical=6.dp)) {
        var first=true
        if(location && state.location!=LocationPrecision.precise) {
            AccessNoticeItem(if(state.location==LocationPrecision.approximate) Icons.Outlined.LocationSearching else Icons.Outlined.LocationOff,
                stringResource(when {state.location==LocationPrecision.approximate->R.string.permission_approximate_body;state.locationDeclined->R.string.permission_location_denied;else->R.string.permission_location_none}),
                stringResource(R.string.permission_location_options),Modifier.testTag("location-access-options")) {options(if(state.location==LocationPrecision.approximate) PermissionAccessKind.precise else PermissionAccessKind.location)}
            first=false
        }
        if(notifications && !state.notifications) {
            if(!first) WdDivider(Modifier.padding(start=46.dp))
            AccessNoticeItem(Icons.Outlined.NotificationsOff,stringResource(R.string.permission_notifications_body),
                stringResource(R.string.permission_notification_options),Modifier.testTag("notification-access-options")) {options(PermissionAccessKind.notifications)}
            first=false
        }
        state.failure?.let {
            if(!first) WdDivider(Modifier.padding(start=46.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ErrorOutline,null,Modifier.size(20.dp),tint=ink)
                Text(stringResource(if(it==PermissionAccessFailure.settings) R.string.permission_settings_failed else R.string.permission_request_failed),style=MaterialTheme.typography.bodyMedium,color=ink)
            }
        }
    }
}

@Composable private fun AccessNoticeItem(icon:ImageVector,text:String,action:String,actionModifier:Modifier,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(start=14.dp,end=14.dp,top=10.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(20.dp),tint=wd.inkRust)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(text,style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)
            TextButton(onClick=onClick,modifier=actionModifier.offset(x=(-12).dp),colors=ButtonDefaults.textButtonColors(contentColor=wd.accent)) {
                Text(action,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(16.dp))
            }
        }
    }
}

/** Dialog action in Whereday ink: accent for affirmative, rust for destructive, muted for cancel. */
@Composable internal fun InkDialogButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,destructive:Boolean=false,muted:Boolean=false) {
    TextButton(onClick=onClick,modifier=modifier.heightIn(min=44.dp),shape=RoundedCornerShape(12.dp),
        colors=ButtonDefaults.textButtonColors(contentColor=when {destructive->wd.inkRust;muted->wd.textMuted;else->wd.accent})) {
        Text(text,style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold))
    }
}

/** Serif dialog title used by the access and recording confirmations. */
@Composable internal fun InkDialogTitle(text:String,icon:ImageVector?=null,tint:Color=wd.accent) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(icon!=null) Box(Modifier.size(40.dp).clip(CircleShape).background(wd.card).border(1.dp,wd.border,CircleShape),contentAlignment=Alignment.Center) {
            Icon(icon,null,Modifier.size(20.dp),tint=tint)
        }
        Text(text,style=WdType.title2,color=wd.textPrimary)
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
            title={InkDialogTitle(stringResource(title),when(ticket.kind) {PermissionAccessKind.notifications->Icons.Outlined.Notifications;PermissionAccessKind.precise->Icons.Outlined.MyLocation;PermissionAccessKind.location->Icons.Outlined.LocationOn})},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(stringResource(body),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                Text(stringResource(R.string.permission_settings_help),style=WdType.caption,color=wd.textMuted)
                if(ticket.requestAllowed) TextButton(onClick={consume {latestSettings.value(ticket.id)}},modifier=Modifier.testTag("permission-review-settings"),
                    colors=ButtonDefaults.textButtonColors(contentColor=wd.accent),contentPadding=PaddingValues(horizontal=0.dp,vertical=8.dp)) {
                    Icon(Icons.Outlined.Settings,null,Modifier.size(16.dp));Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.permission_open_settings),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold))
                }
            }},
            confirmButton={InkDialogButton(stringResource(if(ticket.requestAllowed) action else R.string.permission_open_settings),{consume {if(ticket.requestAllowed) latestRequest.value(ticket.id) else latestSettings.value(ticket.id)}},Modifier.testTag("permission-review-confirm"))},
            dismissButton={InkDialogButton(stringResource(R.string.permission_not_now),{consume {latestDismiss.value(ticket.id)}},Modifier.testTag("permission-review-dismiss"),muted=true)})
    }}
}
