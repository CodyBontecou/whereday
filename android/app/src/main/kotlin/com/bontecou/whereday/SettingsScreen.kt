package com.bontecou.whereday

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.platform.UsagePaywall
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*

internal enum class SettingsDestination {listening,usage,support,countries,licenses,software,exports,automation,webhooks,photos,places,addPlace,roads,visitsOnly}

@Composable fun SettingsScreen(graph:AppGraph,settings:AppSettings,places:List<SavedPlace>,count:Long,onClose:(()->Unit)?=null) {
    val context=LocalContext.current
    // The overview owns its position while a child destination is shown.
    val scroll=rememberLazyListState()
    var destination by rememberSaveable {mutableStateOf<SettingsDestination?>(null)}
    var addingPlace by rememberSaveable {mutableStateOf(false)}
    val clear by graph.journalClear.state.collectAsStateWithLifecycle()
    val commands by graph.settingsActions.state.collectAsStateWithLifecycle()
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->if(uri!=null) graph.settingsActions.folder(uri.toString())}
    val back={destination=null}
    androidx.activity.compose.BackHandler(enabled=destination!=null) {destination=null}
    when(destination) {
        SettingsDestination.listening->{
            val active by RecordingRuntime.active.collectAsStateWithLifecycle()
            ListeningScreen(settings,active,back,null,{destination=SettingsDestination.visitsOnly},{destination=SettingsDestination.webhooks},
                {try {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))} catch(_:Exception) {graph.settingsActions.reportFailure(SettingsFailure.permissions)}},
                {value->graph.settingsActions.update {it.copy(networkGeocoding=value)}})
            return
        }
        SettingsDestination.usage->{UsageAnalyticsScreen(graph.usageAnalytics,back);return}
        SettingsDestination.roads->{RoadSettingsScreen(graph,back);return}
        SettingsDestination.visitsOnly->{VisitMonitoringScreen(graph,back);return}
        SettingsDestination.support->{SupportScreen(graph,back);return}
        SettingsDestination.countries->{CountryDaysScreen(graph,back=back);return}
        SettingsDestination.licenses->{GeographicLicenses(back);return}
        SettingsDestination.software->{SoftwareNoticesScreen(back);return}
        SettingsDestination.exports->{AutomaticExportsScreen(graph,settings.exportFolder!=null,back);return}
        SettingsDestination.automation->{AutomationScreen(graph,back);return}
        SettingsDestination.webhooks->{WebhookScreen(graph,back);return}
        SettingsDestination.photos->{PhotosScreen(graph,back=back);return}
        SettingsDestination.places->{SavedPlacesScreen(graph,places,back);return}
        else->Unit
    }
    val actions=SettingsActions(
        {graph.settingsActions.update(it)},
        {try {folder.launch(null)} catch(_:Exception) {graph.settingsActions.reportFailure(SettingsFailure.folderPicker)}},
        {try {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))} catch(_:Exception) {graph.settingsActions.reportFailure(SettingsFailure.permissions)}},
        {if(it==SettingsDestination.addPlace) addingPlace=true else destination=it},
        {graph.journalClear.clear()},graph.journalClear::dismiss,graph.settingsActions::dismiss,
    )
    // Opened from Archive on the phone tab bar, Back returns there.
    if(onClose!=null) androidx.activity.compose.BackHandler(onBack=onClose)
    val purchase by graph.purchases.state.collectAsStateWithLifecycle()
    val checkout by graph.purchases.checkoutInFlight.collectAsStateWithLifecycle()
    var plans by rememberSaveable {mutableStateOf(false)}
    val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
    val activity=androidx.activity.compose.LocalActivity.current
    SettingsContent(settings,places.size,count,commands,clear,actions,scroll,onBack=onClose,planCard=if(!purchase.enforced) null else {{
        SettingsPlanCard(purchase,graph.purchases.subscriptionId) {
            if(purchase.access==com.bontecou.whereday.billing.AccessState.active && purchase.activeProductId==graph.purchases.subscriptionId) runCatching {uriHandler.openUri(graph.purchases.manageUri())}
            else plans=true
        }
    }})
    if(plans) PaywallDialog(purchase,checkout,{plan->if(activity!=null) graph.purchases.purchase(activity,plan,UsagePaywall.settings)},{graph.purchases.restore(UsagePaywall.settings)},{plans=false})
    if(addingPlace) PlaceEditor(null,{addingPlace=false},{graph.savedPlaces.save(it,null)},null)
}

/** A label beside a mono value that opens a menu; it carries no padding of its own so it sits inside a WdRow or card. */
@Composable fun Choice(label: String, value: String, choices: List<String>, enabled:Boolean=true,labelFor:(String)->String={it.replaceFirstChar {c->c.uppercase()}},change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val ink=if(enabled) wd.accent else wd.textMuted
    val picker:@Composable ()->Unit = {
        Box {
            // The value alone is the button, as before the redesign, so the label stays its own node.
            Row(Modifier.heightIn(min=48.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled=enabled,role=Role.Button) {expanded=true}.padding(horizontal=6.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(labelFor(value),style=WdType.caption.copy(fontWeight=FontWeight.Medium),color=ink)
                Icon(Icons.Filled.UnfoldMore,null,Modifier.size(16.dp),tint=ink)
            }
            WorkspaceDropdownMenu(expanded,{expanded=false}) {choices.forEach {choice->
                DropdownMenuItem(text={Text(labelFor(choice),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=if(choice==value) FontWeight.SemiBold else FontWeight.Normal),color=wd.textPrimary)},
                    trailingIcon=if(choice==value) {{Icon(Icons.Filled.Check,null,Modifier.size(18.dp),tint=wd.accent)}} else null,onClick={expanded=false;change(choice)})
            }}
        }
    }
    val row=Modifier.fillMaxWidth().heightIn(min=36.dp)
    val text=if(enabled) wd.textPrimary else wd.textMuted
    if(LocalDensity.current.fontScale>=1.3f) {
        Column(row,verticalArrangement=Arrangement.spacedBy(4.dp,Alignment.CenterVertically)) {Text(label,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyLarge,color=text);picker()}
    } else {
        Row(row,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=text);picker()}
    }
}

// MARK: Shared settings chrome

/** A pushed settings page's Material 3 header: a back arrow in a 64dp bar over a large Newsreader title; it scrolls with the page. */
@Composable internal fun SettingsBackHeader(title:String,backLabel:String,onBack:()->Unit,modifier:Modifier=Modifier,backTag:String?=null,eyebrow:String?=null) {
    Column(modifier.fillMaxWidth().padding(bottom=8.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            WdIconButton(Icons.AutoMirrored.Outlined.ArrowBack,backLabel,onBack,Modifier.then(if(backTag!=null) Modifier.testTag(backTag) else Modifier))
        }
        Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(eyebrow!=null) Text(eyebrow.uppercase(),style=WdType.caption2.copy(letterSpacing=0.127.em),color=wd.textMuted)
            Text(title,style=WdType.pageTitle.copy(fontSize=34.sp,lineHeight=40.sp),color=wd.textPrimary,modifier=Modifier.semantics {heading()})
        }
    }
}

/** WdNotice's band with a content slot, so tagged status text keeps its own semantics node. */
@Composable internal fun SettingsNotice(modifier:Modifier=Modifier,warning:Boolean=false,icon:ImageVector?=if(warning) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
    content:@Composable ColumnScope.()->Unit) {
    val ink=if(warning) wd.onSecondaryContainer else wd.accent
    val shape=RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(if(warning) wd.secondaryContainer else wd.containerLow)
        .padding(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        if(icon!=null) Icon(icon,null,Modifier.size(20.dp),tint=ink)
        CompositionLocalProvider(LocalContentColor provides if(warning) wd.onSecondaryContainer else wd.textPrimary) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)}
        }
    }
}

/** A thin ink progress rule over a mono caption, for busy and loading states. */
@Composable internal fun SettingsProgress(text:String?,modifier:Modifier=Modifier,textModifier:Modifier=Modifier) {
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape),color=wd.accent,trackColor=wd.dialTrack)
        if(text!=null) Text(text,textModifier,style=WdType.caption,color=wd.textMuted)
    }
}

/** A compact text action inside notices and cards: ink by default, rust when destructive. */
@Composable internal fun SettingsTextAction(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,destructive:Boolean=false) {
    TextButton(onClick,modifier,enabled=enabled,contentPadding=PaddingValues(horizontal=8.dp),
        colors=ButtonDefaults.textButtonColors(contentColor=if(destructive) wd.inkRust else wd.accent,disabledContentColor=wd.textMuted)) {
        Text(text,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold))
    }
}

/** One row of a card spread across lazy items: rounded at the ends, hairlines between rows. */
@Composable internal fun Modifier.settingsSegment(first:Boolean,last:Boolean,radius:Dp=28.dp):Modifier {
    val shape=RoundedCornerShape(topStart=if(first) radius else 0.dp,topEnd=if(first) radius else 0.dp,bottomStart=if(last) radius else 0.dp,bottomEnd=if(last) radius else 0.dp)
    val line=wd.border
    // Material 3 grouped list: a surface-container-low section with inset hairlines between rows, no outline.
    return fillMaxWidth().clip(shape).background(wd.containerLow).padding(top=if(first) 4.dp else 0.dp,bottom=if(last) 4.dp else 0.dp).drawWithContent {
        drawContent()
        val stroke=1.dp.toPx()
        if(!first) drawLine(line,Offset(20.dp.toPx(),stroke/2),Offset(size.width,stroke/2),stroke)
    }
}

/** The Settings board's plan card: the current plan in ink, opening Google Play management or the plans carousel. */
@Composable internal fun SettingsPlanCard(purchase:com.bontecou.whereday.billing.BillingState,subscriptionId:String,onClick:()->Unit) {
    val active=purchase.access==com.bontecou.whereday.billing.AccessState.active
    val subscription=active && purchase.activeProductId==subscriptionId
    val kicker=stringResource(when {
        !active->R.string.settings_plan_kicker_none
        subscription && !purchase.autoRenewing->R.string.settings_plan_kicker_ending
        else->R.string.settings_plan_kicker
    })
    val title=stringResource(when {!active->R.string.settings_plan_title_none;subscription->R.string.settings_plan_title_subscription;else->R.string.paywall_plan_lifetime})
    val body=when {
        !active->stringResource(R.string.settings_plan_body_none)
        subscription->stringResource(if(purchase.autoRenewing) R.string.settings_plan_body_subscription else R.string.settings_plan_body_ending)
        else->stringResource(R.string.paywall_plan_lifetime_detail)
    }
    val shape=RoundedCornerShape(28.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(wd.inkCard).clickable(role=Role.Button,onClick=onClick).padding(start=20.dp,end=16.dp,top=18.dp,bottom=18.dp)
        .testTag("settings-plan"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(kicker.uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.12.em),color=wd.onInkCardMuted)
            Text(title,style=WdType.title2.copy(fontWeight=FontWeight.Normal),color=wd.onInkCard)
            Text(body,style=MaterialTheme.typography.bodySmall.copy(fontSize=13.sp),color=wd.onInkCardMuted)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(24.dp),tint=wd.onInkCard)
    }
}

