package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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
    val kickers=listOf(R.string.onboarding_kicker_welcome,R.string.onboarding_kicker_journal,R.string.onboarding_kicker_background,R.string.onboarding_kicker_notification,R.string.onboarding_kicker_ready)
    val kickerIcons=listOf(Icons.Outlined.WavingHand,Icons.Outlined.AutoStories,Icons.Outlined.MyLocation,Icons.Outlined.NotificationsActive,Icons.Outlined.Verified)
    val large=LocalDensity.current.fontScale>=1.3f
    Surface(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("onboarding-screen"),color=wd.surface) {Box(Modifier.fillMaxSize()) {
        FieldNotesBackground(cloudsOpacity=0.30f,streetOpacity=0.22f)
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val viewport=maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().heightIn(min=viewport).padding(horizontal=24.dp).padding(top=12.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            val stepLabel=stringResource(R.string.onboarding_step_count,page+1,5)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("WHEREDAY",style=WdType.eyebrow.copy(fontSize=13.sp,letterSpacing=0.32.em),color=wd.accent,modifier=Modifier.weight(1f))
                Row(Modifier.semantics(mergeDescendants=true) {contentDescription=stepLabel;progressBarRangeInfo=ProgressBarRangeInfo((page+1)/5f,0f..1f)},horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    repeat(5) {index->
                        Box(Modifier.size(9.dp).clip(CircleShape).then(if(index<=page) Modifier.background(wd.accent) else Modifier.border(1.25.dp,wd.accent.copy(alpha=0.55f),CircleShape)))
                    }
                }
            }
            Spacer(Modifier.height(if(large) 40.dp else 120.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
                Icon(kickerIcons[page],null,Modifier.size(16.dp),tint=wd.accent)
                Text(stringResource(kickers[page]).uppercase(),style=WdType.eyebrow.copy(fontSize=12.sp,letterSpacing=if(large) 0.10.em else 0.24.em),color=wd.accent)
            }
            Text(stringResource(titles[page]),style=WdType.largeTitle.copy(fontSize=36.sp,lineHeight=42.sp,fontWeight=FontWeight.SemiBold),color=wd.textPrimary,
                textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().semantics {heading()})
            Text(stringResource(descriptions[page]),style=MaterialTheme.typography.bodyLarge,color=wd.textMuted,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
            if(page==0) OnboardingPoints(listOf(Icons.Outlined.PhoneAndroid to R.string.onboarding_point_on_device,Icons.Outlined.NoAccounts to R.string.onboarding_point_no_account,Icons.Outlined.EditNote to R.string.onboarding_point_browse))
            if(page==1) OnboardingPoints(listOf(Icons.Outlined.Route to R.string.onboarding_point_routes,Icons.Outlined.Place to R.string.onboarding_point_visits,Icons.Outlined.BookmarkBorder to R.string.onboarding_point_notes))
            if (page == 2) WdButton(stringResource(R.string.onboarding_allow_location),requestLocation,emphasis=WdEmphasis.primary,icon=Icons.Outlined.NearMe)
            if (page == 3) WdButton(stringResource(R.string.onboarding_allow_notifications),requestNotifications,emphasis=WdEmphasis.primary,icon=Icons.Outlined.NotificationsActive)
            if(page in 2..4) access?.let {RecordingAccessNotice(it,onAccessOptions,location=page!=3,notifications=page==3,contentPadding=PaddingValues(0.dp))}
            if(page==0 && analyticsState?.available==true) TextButton(onClick={sharing=true},modifier=Modifier.align(Alignment.CenterHorizontally),colors=ButtonDefaults.textButtonColors(contentColor=wd.accent)) {
                Icon(Icons.Outlined.Insights,null,Modifier.size(16.dp));Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usage_onboarding),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold))
            }
            if(page==4) {
                WdCard {
                    WdRow(divider=canStart) {
                        Icon(if(canStart) Icons.Outlined.NearMe else Icons.Outlined.LocationOff,null,Modifier.size(20.dp),tint=if(canStart) wd.accent else wd.textMuted)
                        Text(stringResource(if(canStart) R.string.onboarding_location_allowed else R.string.onboarding_location_missing),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary,modifier=Modifier.weight(1f))
                        Icon(if(canStart) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.HelpOutline,null,Modifier.size(18.dp),tint=if(canStart) wd.success else wd.textMuted)
                    }
                    if(canStart) {
                        val label=stringResource(R.string.onboarding_start_now)
                        WdRow(divider=false) {
                            Icon(Icons.Outlined.FiberManualRecord,null,Modifier.size(20.dp),tint=if(startImmediately) wd.liveDot else wd.textMuted)
                            Text(label,style=MaterialTheme.typography.bodyLarge,color=if(busy) wd.textMuted else wd.textPrimary,modifier=Modifier.weight(1f))
                            Switch(startImmediately,{value->
                                startImmediately=value;failure=null
                                analytics?.track {UsageEvent(UsageEventName.trackingIntentChanged,step=UsageStep.ready,authorization=authorization,
                                    tracking=if(value) UsageTrackingIntent.startImmediately else UsageTrackingIntent.later)}
                            },enabled=!busy,colors=wdSwitchColors(),modifier=Modifier.testTag("onboarding-start-now").semantics {contentDescription=label})
                        }
                    }
                }
                if(!canStart) {
                    val shape=RoundedCornerShape(14.dp)
                    Text(stringResource(R.string.onboarding_allow_later),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted,textAlign=TextAlign.Center,
                        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp).clip(shape).background(wd.card).border(1.dp,wd.border,shape).padding(horizontal=16.dp,vertical=14.dp))
                }
            }
            Spacer(Modifier.weight(1f).heightIn(min=8.dp))
            failure?.let {WdNotice(it,icon=Icons.Outlined.ErrorOutline,warning=true)}
            val startError=stringResource(R.string.onboarding_start_error)
            val saveError=stringResource(R.string.onboarding_save_error)
            val label=stringResource(if(page!=4) R.string.common_continue else if(startImmediately && canStart) R.string.onboarding_start_finish else R.string.onboarding_finish)
            val advance={
                if(!busy) {
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
                }
            }
            val continueButton=@Composable {mod:Modifier->
                WdButton(label,advance,mod.testTag("onboarding-continue"),emphasis=if(page==2 || page==3) WdEmphasis.secondary else WdEmphasis.primary,
                    busy=busy,trailingIcon=if(busy) null else if(page!=4) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Outlined.Check)
            }
            if(page>0 && !large) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                OnboardingBack(!busy) {page--;failure=null}
                continueButton(Modifier.weight(1f))
            } else {
                continueButton(Modifier)
                if(page>0) OnboardingBack(!busy,Modifier.align(Alignment.CenterHorizontally)) {page--;failure=null}
            }
        }}}
    }}
}

@Composable private fun OnboardingPoints(points:List<Pair<ImageVector,Int>>) {
    Column(Modifier.fillMaxWidth().padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        points.forEach {(icon,text)->
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(icon,null,Modifier.size(20.dp),tint=wd.accent)
                Text(stringResource(text),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
            }
        }
    }
}

/** The quiet "‹ Back" link beside the ink primary, as on iOS. */
@Composable private fun OnboardingBack(enabled:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Row(modifier.heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        val ink=if(enabled) wd.accent else wd.textMuted
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft,null,Modifier.size(20.dp),tint=ink)
        Text(stringResource(R.string.common_back),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium),color=ink)
    }
}
