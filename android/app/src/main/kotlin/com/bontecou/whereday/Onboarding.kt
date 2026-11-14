package com.bontecou.whereday

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.domain.RecordingDetail
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** [detail] is the chosen recording preset, applied on finishing; null when the import shortcut skipped the choice. */
internal data class OnboardingDecision(val start:Boolean,val authorization:UsageAuthorization,val permit:UsageActionPermit?,
    val detail:RecordingDetail?=null,val openImport:Boolean=false)

/** The "Match photos to places" switch; [toggle] asks for library access before turning matching on. */
internal class OnboardingPhotos(val enabled:Boolean,val busy:Boolean,val toggle:(Boolean)->Unit)

@Composable internal fun rememberOnboardingPhotos(graph:AppGraph):OnboardingPhotos {
    val scope=rememberCoroutineScope()
    val state by graph.automaticPhotos.state.collectAsStateWithLifecycle()
    var busy by remember {mutableStateOf(false)}
    fun configure(enabled:Boolean) {
        busy=true
        scope.launch {try {graph.automaticPhotos.configure(enabled)} catch(e:CancellationException) {throw e} catch(_:Exception) {} finally {busy=false}}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Matching turns on only when Android granted some library access.
        if(graph.photoAccess.current()!=PhotoLibraryAccess.None) configure(true) else busy=false
        graph.automaticPhotos.refresh()
    }
    return OnboardingPhotos(state.enabled,busy || !state.loaded) {wanted->
        when {
            !wanted -> configure(false)
            graph.photoAccess.current()!=PhotoLibraryAccess.None -> configure(true)
            else -> {busy=true;permission.launch(graph.photoAccess.requestPermissions())}
        }
    }
}

private const val PAGES=3
private const val REMEMBER=2

/** Three steps, as iOS 2.0: a page per day, it stays on this phone, and how much to remember. */
@Composable internal fun Onboarding(requestLocation: () -> Unit, requestNotifications: () -> Unit, analytics:UsageAnalyticsController?=null, authorization:UsageAuthorization=UsageAuthorization.unknown,
    access:RecordingAccessState?=null,onAccessOptions:(PermissionAccessKind)->Unit={},photos:OnboardingPhotos?=null,onImportSelected:((Uri)->Unit)?=null,
    complete:suspend (OnboardingDecision)->Unit) {
    val ink=LocalWheredayInk.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detailName by rememberSaveable { mutableStateOf(RecordingDetail.routes.name) }
    val detail=RecordingDetail.valueOf(detailName)
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf<String?>(null)}
    val granted=authorization==UsageAuthorization.foreground || authorization==UsageAuthorization.always
    val denied=authorization==UsageAuthorization.denied || authorization==UsageAuthorization.restricted
    var started by rememberSaveable {mutableStateOf(false)}
    var sharing by rememberSaveable {mutableStateOf(false)}
    val analyticsState=analytics?.state?.collectAsStateWithLifecycle()?.value
    if(sharing && analytics!=null) {UsageAnalyticsScreen(analytics) {sharing=false};return}
    // The funnel keeps its welcome, features and ready steps; Android asks for access on the last page.
    val step=listOf(UsageStep.welcome,UsageStep.features,UsageStep.ready)[page]
    LaunchedEffect(page,analyticsState?.enabled,sharing) {
        if(!sharing) {
            if(page==0 && !started && analytics?.track {UsageEvent(UsageEventName.onboardingStarted,step=UsageStep.welcome)}!=null) started=true
            analytics?.track {UsageEvent(UsageEventName.stepViewed,step=step,authorization=authorization,
                tracking=(if(granted) UsageTrackingIntent.later else UsageTrackingIntent.unavailable).takeIf {page==REMEMBER})}
        }
    }
    val startError=stringResource(R.string.onboarding_start_error)
    val saveError=stringResource(R.string.onboarding_save_error)
    fun finish(start:Boolean,openImport:Boolean=false) {
        if(busy) return
        busy=true;failure=null
        val decision=OnboardingDecision(start && granted,authorization,analytics?.capture(),detail.takeUnless {openImport},openImport)
        scope.launch {
            try {complete(decision)}
            catch(error:CancellationException) {throw error}
            catch(_:OnboardingStartFailed) {failure=startError}
            catch(_:Exception) {failure=saveError}
            finally {busy=false}
        }
    }
    // Only hosts that can run an import get a document picker.
    val importer=if(onImportSelected!=null) rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) {onImportSelected(uri);finish(false,openImport=true)}
    } else null
    BackHandler(enabled=page>0 && !busy) {page--;failure=null}
    Box(Modifier.fillMaxSize().background(ink.surface).semantics {testTagsAsResourceId=true}.testTag("onboarding-screen"),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=560.dp).fillMaxSize().safeDrawingPadding().padding(start=24.dp,end=24.dp,bottom=16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.recap_brand),Modifier.weight(1f),style=WheredayType.title3.copy(fontStyle=FontStyle.Italic),color=ink.textPrimary)
                StepIndicators(page)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical=24.dp),verticalArrangement=Arrangement.Center) {
                when(page) {
                    0->WelcomePage()
                    1->PrivacyPage(analyticsState?.available==true) {sharing=true}
                    else->RememberPage(detail,{detailName=it.name},photos,authorization,access,onAccessOptions,requestNotifications,!busy)
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom=8.dp))
            failure?.let {Text(it,Modifier.padding(bottom=8.dp).testTag("onboarding-error"),color=MaterialTheme.colorScheme.error)}
            val primary=when {
                page<REMEMBER -> R.string.common_continue
                granted -> R.string.onboarding_start_recording
                denied -> R.string.onboarding_open_settings
                else -> R.string.onboarding_allow_location
            }
            TEActionButton(stringResource(primary),{
                when {
                    page<REMEMBER -> {page++;failure=null}
                    granted -> finish(true)
                    denied -> onAccessOptions(PermissionAccessKind.location)
                    else -> requestLocation()
                }
            },Modifier.fillMaxWidth().testTag("onboarding-continue"),TEEmphasis.primary,enabled=!busy)
            val secondary=when {
                page==0 -> R.string.onboarding_import.takeIf {onImportSelected!=null}
                page==REMEMBER && granted -> R.string.onboarding_not_now
                // Unlike iOS, Android lets setup finish before location is decided; the rest of the app works without it.
                page==REMEMBER -> R.string.onboarding_finish_without_location
                else -> R.string.common_back
            }
            secondary?.let {label->
                TextButton(enabled=!busy,onClick={
                    when {
                        page==0 -> importer?.launch(arrayOf("application/json","text/*","application/octet-stream"))
                        page==REMEMBER -> finish(false)
                        else -> {page--;failure=null}
                    }
                },modifier=Modifier.fillMaxWidth().heightIn(min=44.dp).testTag(if(page==0) "onboarding-import" else if(page==REMEMBER) "onboarding-finish" else "onboarding-back")) {
                    Text(stringResource(label),color=ink.textMuted,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                }
            }
        }
    }
}

@Composable private fun StepIndicators(page:Int) {
    val ink=LocalWheredayInk.current
    val label=stringResource(R.string.onboarding_step,page+1,PAGES)
    Row(Modifier.clearAndSetSemantics {contentDescription=label;testTag="onboarding-steps"},horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        repeat(PAGES) {index->
            Box(Modifier.width(if(index==page) 20.dp else 6.dp).height(6.dp).background(if(index==page) ink.placeInk(1) else ink.border,RoundedCornerShape(50)))
        }
    }
}

@Composable private fun PageTitle(text:Int) {
    Text(stringResource(text),Modifier.semantics {heading()}.testTag("onboarding-title"),style=WheredayType.editorial(40),color=LocalWheredayInk.current.textPrimary)
}

@Composable private fun WelcomePage() {
    val ink=LocalWheredayInk.current
    Column(verticalArrangement=Arrangement.spacedBy(28.dp)) {
        OnboardingDial(Modifier.size(260.dp).align(Alignment.CenterHorizontally))
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            PageTitle(R.string.onboarding_title_page)
            Text(stringResource(R.string.onboarding_body_page),style=WheredayType.body,color=ink.textMuted)
        }
    }
}

@Composable private fun PrivacyPage(analyticsAvailable:Boolean,openAnalytics:()->Unit) {
    val ink=LocalWheredayInk.current
    Column(verticalArrangement=Arrangement.spacedBy(26.dp)) {
        PageTitle(R.string.onboarding_title_private)
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(ink.border))
            listOf(R.string.onboarding_private_account,R.string.onboarding_private_server,R.string.onboarding_private_export).forEachIndexed {index,text->
                Row(Modifier.fillMaxWidth().padding(vertical=16.dp).semantics(mergeDescendants=true) {}.testTag("onboarding-privacy-row")) {
                    Text("%02d".format(index+1),Modifier.width(34.dp).clearAndSetSemantics {},style=WheredayType.mono(12),color=ink.inkRust)
                    Text(stringResource(text),style=WheredayType.body,color=ink.textPrimary)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(ink.border))
            }
        }
        // Usage analytics stay off unless chosen here or in Settings.
        if(analyticsAvailable) TextButton(onClick=openAnalytics,modifier=Modifier.testTag("onboarding-analytics")) {Text(stringResource(R.string.usage_onboarding),color=ink.accent)}
    }
}

@Composable private fun RememberPage(detail:RecordingDetail,select:(RecordingDetail)->Unit,photos:OnboardingPhotos?,authorization:UsageAuthorization,access:RecordingAccessState?,
    onAccessOptions:(PermissionAccessKind)->Unit,requestNotifications:()->Unit,enabled:Boolean) {
    val ink=LocalWheredayInk.current
    Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
        PageTitle(R.string.onboarding_title_remember)
        Column(Modifier.semantics {selectableGroup()},verticalArrangement=Arrangement.spacedBy(8.dp)) {
            RecordingDetail.entries.forEach {option->
                val selected=option==detail
                Column(Modifier.fillMaxWidth().background(ink.card,RoundedCornerShape(16.dp)).border(if(selected) 2.dp else 1.dp,if(selected) ink.textPrimary else ink.border,RoundedCornerShape(16.dp))
                    .selectable(selected,enabled=enabled,role=Role.RadioButton) {select(option)}.padding(horizontal=16.dp,vertical=14.dp).testTag("onboarding-detail-${option.name}"),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(detailTitle(option)),style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.SemiBold,color=ink.textPrimary)
                    Text(stringResource(detailSummary(option)),style=MaterialTheme.typography.bodySmall,color=ink.textMuted)
                }
            }
            photos?.let {
                Row(Modifier.fillMaxWidth().background(ink.card,RoundedCornerShape(16.dp)).border(1.dp,ink.border,RoundedCornerShape(16.dp))
                    .toggleable(it.enabled,enabled=enabled && !it.busy,role=Role.Switch) {wanted->it.toggle(wanted)}.padding(horizontal=16.dp,vertical=14.dp).testTag("onboarding-photos"),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.onboarding_photos_title),style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.SemiBold,color=ink.textPrimary)
                        Text(stringResource(R.string.onboarding_photos_body),style=MaterialTheme.typography.bodySmall,color=ink.textMuted)
                    }
                    Switch(it.enabled,null,enabled=enabled && !it.busy)
                }
            }
        }
        val granted=authorization==UsageAuthorization.foreground || authorization==UsageAuthorization.always
        // Android shows recording controls in a notification, so it asks for notifications once location is allowed.
        if(granted && access?.notifications==false) TextButton(requestNotifications,enabled=enabled,modifier=Modifier.testTag("onboarding-notifications")) {
            Text(stringResource(R.string.onboarding_allow_notifications),color=ink.accent)
        }
        if(granted) access?.takeIf {it.location==LocationPrecision.approximate}?.let {RecordingAccessNotice(it,onAccessOptions,location=true,notifications=false)}
        Text(stringResource(when(authorization) {
            UsageAuthorization.foreground,UsageAuthorization.always -> R.string.onboarding_note_granted
            UsageAuthorization.denied,UsageAuthorization.restricted -> R.string.onboarding_note_denied
            else -> R.string.onboarding_note_default
        }),style=WheredayType.mono(11),color=ink.textMuted,modifier=Modifier.testTag("onboarding-note"))
    }
}

private fun detailTitle(detail:RecordingDetail)=when(detail) {RecordingDetail.places->R.string.onboarding_detail_places;RecordingDetail.routes->R.string.onboarding_detail_routes;RecordingDetail.everyStep->R.string.onboarding_detail_every}
private fun detailSummary(detail:RecordingDetail)=when(detail) {RecordingDetail.places->R.string.onboarding_detail_places_body;RecordingDetail.routes->R.string.onboarding_detail_routes_body;RecordingDetail.everyStep->R.string.onboarding_detail_every_body}

/** The first-run illustration: a day dial with its first places filling in. */
@Composable private fun OnboardingDial(modifier:Modifier) {
    val ink=LocalWheredayInk.current
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val today=remember {LocalDate.now()}
    val label=stringResource(R.string.onboarding_dial)
    Box(modifier.clearAndSetSemantics {contentDescription=label;testTag="onboarding-dial"},contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width=23.dp.toPx();val inset=26.dp.toPx()+width/2
            val arc=Size(size.width-inset*2,size.height-inset*2);val origin=Offset(inset,inset)
            fun ring(from:Float,to:Float,color:androidx.compose.ui.graphics.Color)=drawArc(color,-90f+from*360f,(to-from)*360f,false,origin,arc,style=Stroke(width,cap=StrokeCap.Butt))
            ring(0f,1f,ink.dialTrack);ring(0f,0.342f,ink.placeInk(0));ring(0.361f,0.392f,ink.placeInk(1));ring(0.41f,0.49f,ink.placeInk(2).copy(alpha=0.5f))
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(today.dayOfWeek.getDisplayName(TextStyle.SHORT,locale),style=WheredayType.editorial(39),color=ink.textPrimary)
            Text(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"MMMd"),locale).format(today).uppercase(locale),style=WheredayType.mono(11),color=ink.textMuted)
        }
    }
}
