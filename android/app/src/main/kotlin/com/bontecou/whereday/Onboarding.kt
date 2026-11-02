package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*

internal data class OnboardingDecision(val start:Boolean,val authorization:UsageAuthorization,val permit:UsageActionPermit?)

@Composable internal fun Onboarding(requestLocation: () -> Unit, requestNotifications: () -> Unit, analytics:UsageAnalyticsController?=null, authorization:UsageAuthorization=UsageAuthorization.unknown,access:RecordingAccessState?=null,onAccessOptions:(PermissionAccessKind)->Unit={}, complete:suspend (OnboardingDecision)->Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    var startImmediately by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf<String?>(null)}
    val canStart=authorization==UsageAuthorization.foreground || authorization==UsageAuthorization.always
    val intent=if(!canStart) UsageTrackingIntent.unavailable else if(startImmediately) UsageTrackingIntent.startImmediately else UsageTrackingIntent.later
    var started by rememberSaveable {mutableStateOf(false)}
    var sharing by rememberSaveable {mutableStateOf(false)}
    val analyticsState=analytics?.state?.collectAsStateWithLifecycle()?.value
    if(sharing && analytics!=null) {UsageAnalyticsScreen(analytics) {sharing=false};return}
    LaunchedEffect(page,analyticsState?.enabled,sharing) {
        if(!sharing) {
            if(page==0 && !started && analytics?.track {UsageEvent(UsageEventName.onboardingStarted,step=UsageStep.welcome)}!=null) started=true
            analytics?.track {UsageEvent(UsageEventName.stepViewed,step=UsageStep.entries[page],authorization=authorization,tracking=intent.takeIf {page==4})}
        }
    }
    val titles = listOf(R.string.onboarding_title_welcome,R.string.onboarding_title_journal,R.string.onboarding_title_background,R.string.onboarding_title_notification,R.string.onboarding_title_ready)
    val descriptions = listOf(R.string.onboarding_body_welcome,R.string.onboarding_body_journal,R.string.onboarding_body_background,R.string.onboarding_body_notification,R.string.onboarding_body_ready)
    Surface(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("onboarding-screen")) {
        Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("WHEREDAY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { (page + 1) / 5f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(40.dp))
            Text(stringResource(titles[page]), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(descriptions[page]), style = MaterialTheme.typography.bodyLarge)
            if (page == 2) Button(onClick = requestLocation) { Text(stringResource(R.string.onboarding_allow_location)) }
            if (page == 3) Button(onClick = requestNotifications) { Text(stringResource(R.string.onboarding_allow_notifications)) }
            if(page in 2..4) access?.let {RecordingAccessNotice(it,onAccessOptions,location=page!=3,notifications=page==3)}
            if(page==0 && analyticsState?.available==true) TextButton(onClick={sharing=true}) {Text(stringResource(R.string.usage_onboarding))}
            if(page==4) {
                Text(stringResource(if(canStart) R.string.onboarding_location_allowed else R.string.onboarding_location_missing))
                if(canStart) {
                    val label=stringResource(R.string.onboarding_start_now)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(label,Modifier.weight(1f).padding(top=12.dp))
                        Switch(startImmediately,{value->
                            startImmediately=value;failure=null
                            analytics?.track {UsageEvent(UsageEventName.trackingIntentChanged,step=UsageStep.ready,authorization=authorization,
                                tracking=if(value) UsageTrackingIntent.startImmediately else UsageTrackingIntent.later)}
                        },enabled=!busy,modifier=Modifier.testTag("onboarding-start-now").semantics {contentDescription=label})
                    }
                } else Text(stringResource(R.string.onboarding_allow_later),style=MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            val startError=stringResource(R.string.onboarding_start_error)
            val saveError=stringResource(R.string.onboarding_save_error)
            Button(enabled=!busy,onClick = {
                if(busy) return@Button
                if(page!=4) {page++;failure=null}
                else {
                    val decision=OnboardingDecision(startImmediately && canStart,authorization,analytics?.capture())
                    busy=true;failure=null
                    scope.launch {
                        try {complete(decision)}
                        catch(error:CancellationException) {throw error}
                        catch(_:OnboardingStartFailed) {failure=startError}
                        catch(_:Exception) {failure=saveError}
                        finally {busy=false}
                    }
                }
            },modifier=Modifier.fillMaxWidth().testTag("onboarding-continue")) {
                Text(stringResource(if(page!=4) R.string.common_continue else if(startImmediately && canStart) R.string.onboarding_start_finish else R.string.onboarding_finish))
            }
            if(page>0) TextButton(enabled=!busy,onClick={page--;failure=null}) {Text(stringResource(R.string.common_back))}
        }
    }
}
