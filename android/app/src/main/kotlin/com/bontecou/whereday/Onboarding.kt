package com.bontecou.whereday

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.HistoryRange
import com.bontecou.whereday.domain.RecordingPreset
import com.bontecou.whereday.domain.SavedPlace
import com.bontecou.whereday.export.ExportFormat
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

internal data class OnboardingDecision(val start:Boolean,val authorization:UsageAuthorization,val permit:UsageActionPermit?,
    /** "Send me tonight's page", already limited to installs that may post notifications. */
    val nightly:Boolean=false,
    /** Finished from "You're in" after a verified free-week (or plan) checkout, not from the tonight step. */
    val trial:Boolean=false)

/** One current fix for the stamp step. */
internal data class OnboardingFix(val latitude:Double,val longitude:Double,val accuracyMeters:Double) {
    val coordinate get()=Coordinate(latitude,longitude)
}

/** The photo-matching switch reuses automatic photo sync; counts come from located photos already matched on this phone. */
internal class OnboardingPhotos(val granted:()->Boolean,val permissions:Array<String>,val enabled:Flow<Boolean>,val running:Flow<Boolean>,
    val enable:suspend(Boolean,HistoryRange)->Unit,val dayCounts:(HistoryRange,ZoneId)->Flow<Map<LocalDate,Int>>)

/** The existing Google Timeline / Whereday backup import, hosted over onboarding while it runs. */
internal class OnboardingImporter(val active:@Composable ()->Boolean,val content:@Composable ()->Unit,val select:(android.content.Context,List<Uri>)->Unit)

/** App services onboarding drives. Defaults are inert, so component tests need no journal. */
internal class OnboardingServices(
    val locate:(suspend()->OnboardingFix?)?=null,
    val savePlace:suspend(SavedPlace,SavedPlace?)->Unit={_,_->},
    val saveIntents:suspend(Set<OnboardingIntent>)->Unit={},
    val saveExportFormat:(ExportFormat)->Unit={},
    val photos:OnboardingPhotos?=null,
    val importer:OnboardingImporter?=null,
)

@Composable internal fun Onboarding(requestLocation: () -> Unit, requestNotifications: () -> Unit, analytics:UsageAnalyticsController?=null, authorization:UsageAuthorization=UsageAuthorization.unknown,access:RecordingAccessState?=null,onAccessOptions:(PermissionAccessKind)->Unit={},
    initialPreset:RecordingPreset?=null,choosePreset:(RecordingPreset)->Unit={},services:OnboardingServices=remember {OnboardingServices()},
    initialIntents:Set<OnboardingIntent> = OnboardingIntent.DEFAULT,notificationRequestAvailable:Boolean=android.os.Build.VERSION.SDK_INT>=33,
    /** What the last step can sell; null or unconfigured billing keeps the tonight step. */
    offer:OnboardingOffer?=null,initialStep:OnboardingStep=OnboardingStep.cover,
    complete:suspend (OnboardingDecision)->Unit) {
    var page by rememberSaveable { mutableIntStateOf(initialStep.ordinal) }
    val step=OnboardingStep.at(page)
    // iOS asks "How much should it remember?" before location access. Nothing selected means custom settings, which stay.
    var presetKey by rememberSaveable {mutableStateOf(initialPreset?.key)}
    val preset=RecordingPreset.fromKey(presetKey)
    var intentKeys by rememberSaveable {mutableStateOf(OnboardingIntent.encode(initialIntents).joinToString(","))}
    val intents=OnboardingIntent.decode(intentKeys.split(',').filter {it.isNotEmpty()}.toSet())
    var hour by rememberSaveable {mutableFloatStateOf(OnboardingSampleDay.START_HOUR)}
    var placeKey by rememberSaveable {mutableStateOf<String?>(null)}
    val placeName=OnboardingPlaceName.fromKey(placeKey)
    var fixLatitude by rememberSaveable {mutableStateOf<Double?>(null)}
    var fixLongitude by rememberSaveable {mutableStateOf<Double?>(null)}
    var fixAccuracy by rememberSaveable {mutableStateOf<Double?>(null)}
    val fix=if(fixLatitude!=null && fixLongitude!=null) OnboardingFix(fixLatitude!!,fixLongitude!!,fixAccuracy ?: 0.0) else null
    var locating by remember {mutableStateOf(false)}
    var locateFinished by rememberSaveable {mutableStateOf(false)}
    // One saved place per run: going back and renaming updates the same record.
    val placeId=rememberSaveable {java.util.UUID.randomUUID().toString()}
    var savedName by rememberSaveable {mutableStateOf<String?>(null)}
    var savedLatitude by rememberSaveable {mutableStateOf<Double?>(null)}
    var savedLongitude by rememberSaveable {mutableStateOf<Double?>(null)}
    var formatKey by rememberSaveable {mutableStateOf<String?>(null)}
    val format=OnboardingFormat.entries.firstOrNull {it.key==formatKey} ?: OnboardingFormat.preferred(intents)
    var nightly by rememberSaveable {mutableStateOf(true)}
    var photoDenied by rememberSaveable {mutableStateOf(false)}
    var photoError by remember {mutableStateOf(false)}
    var locationRequested by rememberSaveable {mutableStateOf(false)}
    // The free week: a checkout started here turns verified access into "You're in"; a restore keeps the tonight step.
    var checkedOut by rememberSaveable {mutableStateOf(false)}
    var planKey by rememberSaveable {mutableStateOf<String?>(null)}
    var boughtKey by rememberSaveable {mutableStateOf<String?>(null)}
    var plansOpen by rememberSaveable {mutableStateOf(false)}
    val finale=OnboardingFinale.of(offer?.state,checkedOut)
    val chosenPlan=offer?.let {OnboardingPlans.selected(it.state.products,planKey?.let {key->com.bontecou.whereday.billing.StorePlan.entries.firstOrNull {it.name==key}})}
    val chosenProduct=chosenPlan?.let {offer.state.product(it)}
    val boughtProduct=boughtKey?.let {key->offer?.state?.products?.firstOrNull {it.plan.name==key}}
    val scope=rememberCoroutineScope()
    var startImmediately by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf<String?>(null)}
    val canStart=authorization==UsageAuthorization.foreground || authorization==UsageAuthorization.always
    val locationGranted=canStart || access?.location?.let {it!=LocationPrecision.none}==true
    val notificationsAllowed=access?.notifications==true
    val intent=if(!canStart) UsageTrackingIntent.unavailable else if(startImmediately) UsageTrackingIntent.startImmediately else UsageTrackingIntent.later
    var started by rememberSaveable {mutableStateOf(false)}
    var sharing by rememberSaveable {mutableStateOf(false)}
    val analyticsState=analytics?.state?.collectAsStateWithLifecycle()?.value
    val importing=services.importer?.active?.invoke()==true
    val context=LocalContext.current
    // Launchers exist only with their services, so component tests need no activity result registry.
    val importLauncher=services.importer?.let {importer->rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris->
        if(uris.isNotEmpty()) importer.select(context,uris)
    }}
    if(importing) {services.importer?.content?.invoke();return}
    if(sharing && analytics!=null) {UsageAnalyticsScreen(analytics) {sharing=false};return}
    LaunchedEffect(page,analyticsState?.enabled,sharing) {
        if(!sharing) {
            if(page==0 && !started && analytics?.track {UsageEvent(UsageEventName.onboardingStarted,step=UsageStep.welcome)}!=null) started=true
            analytics?.track {UsageEvent(UsageEventName.stepViewed,step=step.usageStep,authorization=authorization,tracking=intent.takeIf {step==OnboardingStep.tonight})}
        }
    }
    // Location granted from this step's request moves on by itself, as the system sheet closing is the answer.
    LaunchedEffect(step,locationGranted,locationRequested) {
        if(step==OnboardingStep.perm && locationGranted && locationRequested) {locationRequested=false;page++}
    }
    // The stamp needs one current fix, taken only after permission and only on its own step.
    LaunchedEffect(step,locationGranted,services.locate) {
        val locate=services.locate
        if(step==OnboardingStep.stamp && locationGranted && locate!=null && fix==null && !locateFinished) {
            locating=true
            try {locate()?.let {fixLatitude=it.latitude;fixLongitude=it.longitude;fixAccuracy=it.accuracyMeters}}
            catch(error:CancellationException) {throw error}
            catch(_:Exception) {}
            finally {locating=false;locateFinished=true}
        }
    }
    val today=remember {LocalDate.now()}
    val photos=services.photos
    val photoEnabled by remember(photos) {photos?.enabled ?: kotlinx.coroutines.flow.flowOf(false)}.collectAsStateWithLifecycle(false)
    val photoRunning by remember(photos) {photos?.running ?: kotlinx.coroutines.flow.flowOf(false)}.collectAsStateWithLifecycle(false)
    val zone=remember {ZoneId.systemDefault()}
    val heatmapRange=remember(today) {HistoryRange(today.minusDays((OnboardingHeatmap.DAYS-1).toLong()).atStartOfDay(zone).toInstant(),today.plusDays(1).atStartOfDay(zone).toInstant())}
    val photoCounts by remember(photos,photoEnabled,heatmapRange) {
        if(photos!=null && photoEnabled && photos.granted()) photos.dayCounts(heatmapRange,zone) else kotlinx.coroutines.flow.flowOf(emptyMap())
    }.collectAsStateWithLifecycle(emptyMap())
    fun setPhotos(on:Boolean) {
        val source=photos ?: return
        photoError=false
        scope.launch {
            try {source.enable(on,heatmapRange)}
            catch(error:CancellationException) {throw error}
            catch(_:Exception) {photoError=true}
        }
    }
    val photoPermission=photos?.let {source->rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(source.granted()) {photoDenied=false;setPhotos(true)} else photoDenied=true
    }}
    val now=remember(step) {ZonedDateTime.now()}
    val large=LocalDensity.current.fontScale>=1.3f
    Surface(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("onboarding-screen"),color=wd.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val viewport=maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().heightIn(min=viewport).padding(horizontal=24.dp).padding(top=12.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            val total=OnboardingStep.entries.size
            val stepLabel=stringResource(R.string.onboarding_step_count,page+1,total)
            Row(Modifier.fillMaxWidth().heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.onboarding_brand),style=WdType.title2.copy(fontStyle=FontStyle.Italic,fontWeight=FontWeight.Normal,fontSize=21.sp),color=wd.textPrimary,modifier=Modifier.weight(1f))
                Row(Modifier.testTag("onboarding-progress").clearAndSetSemantics {contentDescription=stepLabel;progressBarRangeInfo=ProgressBarRangeInfo((page+1)/total.toFloat(),0f..1f)},
                    horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    repeat(total) {index->
                        Box(Modifier.width(if(index==page) 20.dp else 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if(index==page) wd.inkRust else wd.border))
                    }
                }
            }
            Spacer(Modifier.weight(1f).heightIn(min=if(large) 4.dp else 12.dp))
            // Each step is its own pane, so TalkBack and accessibility clients hear and see the step change.
            Column(Modifier.fillMaxWidth().semantics {paneTitle=stepLabel},verticalArrangement=Arrangement.spacedBy(18.dp)) {
                when(step) {
                    OnboardingStep.cover->CoverStep(today)
                    OnboardingStep.intent->IntentStep(intents,enabled=!busy) {choice,on->
                        val next=if(on) intents+choice else intents-choice
                        intentKeys=OnboardingIntent.encode(next).joinToString(",")
                        if(choice==OnboardingIntent.routes) presetKey=(if(on) RecordingPreset.everyStep else RecordingPreset.routes).key
                    }
                    OnboardingStep.scrub->ScrubStep(hour) {hour=it}
                    OnboardingStep.pen->PenStep(preset,enabled=!busy) {presetKey=it.key}
                    OnboardingStep.perm->{
                        PermissionStep()
                        access?.let {RecordingAccessNotice(it,onAccessOptions,location=true,notifications=false,contentPadding=PaddingValues(0.dp))}
                    }
                    OnboardingStep.stamp->StampStep(fix,locating || (locationGranted && services.locate!=null && fix==null && !locateFinished),available=locationGranted && services.locate!=null,placeName,enabled=!busy) {
                        placeKey=it.key;failure=null
                    }
                    OnboardingStep.backfill->BackfillStep(today,photoCounts,photos!=null,photoEnabled && photos?.granted()==true,photoRunning,photoDenied,photoError,
                        onToggle={on->
                            if(!on) setPhotos(false)
                            else if(photos?.granted()==true) setPhotos(true)
                            else photos?.let {photoPermission?.launch(it.permissions)}
                        },
                        onImport=importLauncher?.let {launcher->{launcher.launch(arrayOf("application/json","text/*","application/octet-stream"))}})
                    OnboardingStep.glance->{
                        GlanceStep(savedName)
                        access?.let {RecordingAccessNotice(it,onAccessOptions,location=false,notifications=true,contentPadding=PaddingValues(0.dp))}
                    }
                    OnboardingStep.export->ExportStep(format) {formatKey=it.key}
                    OnboardingStep.tonight->if(finale==OnboardingFinale.freeWeek) {
                        val stampedName=savedName ?: placeName?.takeIf {it.saves && fix!=null}?.let {stringResource(it.label)}
                        FreeWeekStep(offer!!,chosenPlan,{planKey=it.name},now,
                            TrialToday.of(stampedName,if(photoEnabled) OnboardingHeatmap.foundDays(photoCounts,today) else 0,recording=canStart),enabled=!busy && !offer.checkout)
                        if(plansOpen) AllPlansSheet(offer,chosenPlan,intents,today,enabled=!busy && !offer.checkout,busy=offer.checkout || offer.state.busy,
                            onSelect={planKey=it.name},onBuy={chosenPlan?.let {plan->checkedOut=true;boughtKey=plan.name;offer.buy(plan)}},dismiss={plansOpen=false})
                    } else if(finale==OnboardingFinale.done) {
                        TrialDoneStep(boughtProduct,today,canStart,nightly=nightly && notificationsAllowed,nightlyEnabled=(notificationsAllowed || notificationRequestAvailable) && !busy,
                            notificationsAllowed=notificationsAllowed,tomorrow=com.bontecou.whereday.platform.NightlyPageReminder.tomorrow(now)) {on->
                            // The only notification request for tonight's page, and only after the purchase.
                            nightly=on;if(on && !notificationsAllowed) requestNotifications()
                        }
                    } else {
                        TonightStep(today,savedName ?: placeName?.takeIf {it.saves && fix!=null}?.let {stringResource(it.label)},stamped=fix!=null,
                            canStart=canStart,startImmediately=startImmediately,busy=busy,
                            onStartChange={value->
                                startImmediately=value;failure=null
                                analytics?.track {UsageEvent(UsageEventName.trackingIntentChanged,step=UsageStep.ready,authorization=authorization,
                                    tracking=if(value) UsageTrackingIntent.startImmediately else UsageTrackingIntent.later)}
                            },
                            nightly=nightly && notificationsAllowed,nightlyAvailable=notificationsAllowed,tomorrow=com.bontecou.whereday.platform.NightlyPageReminder.tomorrow(now),
                            onNightlyChange={nightly=it})
                        access?.let {RecordingAccessNotice(it,onAccessOptions,location=true,notifications=false,contentPadding=PaddingValues(0.dp))}
                    }
                }
            }
            if(step==OnboardingStep.cover && analyticsState?.available==true) TextButton(onClick={sharing=true},modifier=Modifier.align(Alignment.CenterHorizontally),colors=ButtonDefaults.textButtonColors(contentColor=wd.accent)) {
                Icon(Icons.Outlined.Insights,null,Modifier.size(16.dp));Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usage_onboarding),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold))
            }
            Spacer(Modifier.weight(1f).heightIn(min=8.dp))
            failure?.let {WdNotice(it,icon=Icons.Outlined.ErrorOutline,warning=true)}
            val startError=stringResource(R.string.onboarding_start_error)
            val saveError=stringResource(R.string.onboarding_save_error)
            val placeError=stringResource(R.string.onboarding_stamp_error)
            val chosenLabel=placeName?.let {stringResource(it.label)}
            fun next() {page=(page+1).coerceAtMost(OnboardingStep.entries.lastIndex);failure=null}
            val finish:()->Unit=finish@{
                val decision=OnboardingFinish.decide(finale,startImmediately,canStart,nightly,notificationsAllowed,authorization,analytics?.capture()) ?: return@finish
                busy=true;failure=null
                scope.launch {
                    try {complete(decision)}
                    catch(error:CancellationException) {throw error}
                    catch(_:OnboardingStartFailed) {failure=startError}
                    catch(_:Exception) {failure=saveError}
                    finally {busy=false}
                }
            }
            val savePlace:()->Unit={
                val coordinate=fix!!.coordinate
                val name=chosenLabel!!
                val expected=savedName?.let {old->SavedPlace(placeId,old,Coordinate(savedLatitude ?: coordinate.latitude,savedLongitude ?: coordinate.longitude))}
                busy=true;failure=null
                scope.launch {
                    try {
                        services.savePlace(SavedPlace(placeId,name,coordinate),expected)
                        savedName=name;savedLatitude=coordinate.latitude;savedLongitude=coordinate.longitude
                        next()
                    }
                    catch(error:CancellationException) {throw error}
                    catch(_:Exception) {failure=placeError}
                    finally {busy=false}
                }
            }
            // Choices are written on leaving their step, before any recording can start on the last one.
            val primary:Pair<String,()->Unit> = when(step) {
                OnboardingStep.cover->stringResource(R.string.onboarding_cover_start) to ::next
                OnboardingStep.intent->stringResource(if(intents.isEmpty()) R.string.onboarding_skip else R.string.common_continue) to {
                    val chosen=intents
                    scope.launch {try {services.saveIntents(chosen)} catch(error:CancellationException) {throw error} catch(_:Exception) {}}
                    next()
                }
                OnboardingStep.scrub->stringResource(R.string.onboarding_scrub_done) to ::next
                OnboardingStep.pen->stringResource(if(preset==null) R.string.common_continue else R.string.onboarding_pen_use) to {preset?.let(choosePreset);next()}
                OnboardingStep.perm->if(locationGranted) stringResource(R.string.common_continue) to ::next
                    else stringResource(R.string.onboarding_allow_location) to {locationRequested=true;requestLocation()}
                OnboardingStep.stamp->when {
                    fix!=null && placeName?.saves==true->stringResource(R.string.onboarding_stamp_save,chosenLabel!!) to savePlace
                    placeName==OnboardingPlaceName.other->stringResource(R.string.common_continue) to ::next
                    else->stringResource(R.string.onboarding_stamp_skip) to ::next
                }
                OnboardingStep.backfill->stringResource(R.string.common_continue) to ::next
                OnboardingStep.glance->if(notificationsAllowed || !notificationRequestAvailable) stringResource(R.string.common_continue) to ::next
                    else stringResource(R.string.onboarding_allow_notifications) to {requestNotifications();next()}
                OnboardingStep.export->stringResource(R.string.common_continue) to {OnboardingFormat.savedDefault(intents)?.let(services.saveExportFormat);next()}
                OnboardingStep.tonight->when(finale) {
                    OnboardingFinale.tonight->stringResource(if(startImmediately && canStart) R.string.onboarding_tonight_start else R.string.onboarding_finish) to finish
                    OnboardingFinale.done->stringResource(R.string.onboarding_done_open) to finish
                    OnboardingFinale.freeWeek->(chosenProduct?.let {freeWeekCta(it)} ?: stringResource(R.string.onboarding_trial_start)) to {
                        chosenPlan?.let {plan->checkedOut=true;boughtKey=plan.name;offer?.buy(plan)}
                    }
                }
            }
            val secondary:Pair<String,()->Unit>? = when(step) {
                OnboardingStep.perm->if(locationGranted) null else stringResource(R.string.onboarding_not_now) to ::next
                OnboardingStep.glance->if(notificationsAllowed || !notificationRequestAvailable) null else stringResource(R.string.onboarding_not_now) to ::next
                OnboardingStep.export->stringResource(R.string.onboarding_export_later) to ::next
                else->null
            }
            val selling=step==OnboardingStep.tonight && finale==OnboardingFinale.freeWeek
            val continueButton=@Composable {mod:Modifier->
                if(selling) {
                    val checkout=offer?.checkout==true || offer?.state?.busy==true
                    WdButton(primary.first,{if(!busy) primary.second()},mod.testTag("onboarding-start-trial"),emphasis=WdEmphasis.primary,
                        enabled=chosenProduct!=null && offer?.state?.access!=com.bontecou.whereday.billing.AccessState.pending,busy=checkout)
                } else WdButton(primary.first,{if(!busy) primary.second()},mod.testTag("onboarding-continue"),emphasis=WdEmphasis.primary,
                    busy=busy,trailingIcon=if(busy) null else if(step!=OnboardingStep.tonight) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Outlined.Check)
            }
            val back={if(!busy && page>0) {page--;failure=null}}
            // "You're in" follows a purchase, so there is nothing to go back to.
            val showBack=step!=OnboardingStep.cover && !(step==OnboardingStep.tonight && finale==OnboardingFinale.done)
            if(showBack && !large) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                OnboardingBack(!busy,onClick=back)
                continueButton(Modifier.weight(1f))
            } else {
                continueButton(Modifier)
                if(showBack) OnboardingBack(!busy,Modifier.align(Alignment.CenterHorizontally),onClick=back)
            }
            if(selling && offer!=null) FreeWeekFooter(offer,chosenProduct,today,enabled=!busy && !offer.checkout,onAllPlans={plansOpen=true},
                onRestore={checkedOut=false;boughtKey=null;offer.restore()})
            secondary?.let {(label,action)->OnboardingQuiet(label,!busy,Modifier.align(Alignment.CenterHorizontally).testTag("onboarding-skip")) {action()}}
            if(step==OnboardingStep.cover && importLauncher!=null) OnboardingQuiet(stringResource(R.string.onboarding_cover_import),!busy,
                Modifier.align(Alignment.CenterHorizontally).testTag("onboarding-import")) {importLauncher.launch(arrayOf("application/json","text/*","application/octet-stream"))}
        }}}
    }
}

/** The quiet "‹ Back" link beside the ink primary, as on iOS. */
@Composable private fun OnboardingBack(enabled:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Row(modifier.testTag("onboarding-back").heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        val ink=if(enabled) wd.accent else wd.textMuted
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft,null,Modifier.size(20.dp),tint=ink)
        Text(stringResource(R.string.common_back),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium),color=ink)
    }
}

/** A muted text action under the primary: skips, "Not now" and the import link. */
@Composable private fun OnboardingQuiet(label:String,enabled:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Box(modifier.heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=12.dp),contentAlignment=Alignment.Center) {
        Text(label,style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium),color=if(enabled) wd.textMuted else wd.textMuted.copy(alpha=0.5f),textAlign=TextAlign.Center)
    }
}
