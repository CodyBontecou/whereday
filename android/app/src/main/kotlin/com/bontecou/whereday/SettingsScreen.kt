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

internal enum class SettingsDestination {usage,support,countries,licenses,software,exports,automation,webhooks,photos,places,addPlace,roads,visitsOnly}

@Composable fun SettingsScreen(graph:AppGraph,settings:AppSettings,places:List<SavedPlace>,count:Long) {
    val context=LocalContext.current
    // The overview owns its position while a child destination is shown.
    val scroll=rememberLazyListState()
    var destination by rememberSaveable {mutableStateOf<SettingsDestination?>(null)}
    var addingPlace by rememberSaveable {mutableStateOf(false)}
    val clear by graph.journalClear.state.collectAsStateWithLifecycle()
    val commands by graph.settingsActions.state.collectAsStateWithLifecycle()
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->if(uri!=null) graph.settingsActions.folder(uri.toString())}
    val back={destination=null}
    when(destination) {
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
        SettingsDestination.photos->{PhotosScreen(graph,back);return}
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
    SettingsContent(settings,places.size,count,commands,clear,actions,scroll)
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

/** A pushed settings page's paper back capsule over a Newsreader title; it scrolls with the page, as the iOS back pages do. */
@Composable internal fun SettingsBackHeader(title:String,backLabel:String,onBack:()->Unit,modifier:Modifier=Modifier,backTag:String?=null,eyebrow:String?=null) {
    Column(modifier.fillMaxWidth().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        WdBackChip(backLabel,onBack,Modifier.padding(start=16.dp,top=8.dp).then(if(backTag!=null) Modifier.testTag(backTag) else Modifier))
        WdPageHeader(title,eyebrow=eyebrow,compact=true)
    }
}

/** WdNotice's band with a content slot, so tagged status text keeps its own semantics node. */
@Composable internal fun SettingsNotice(modifier:Modifier=Modifier,warning:Boolean=false,icon:ImageVector?=if(warning) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
    content:@Composable ColumnScope.()->Unit) {
    val ink=if(warning) wd.inkRust else wd.accent
    val shape=RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(wd.card).background(ink.copy(alpha=if(wd.dark) 0.12f else 0.06f))
        .border(1.dp,ink.copy(alpha=0.35f),shape).padding(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        if(icon!=null) Icon(icon,null,Modifier.size(20.dp),tint=ink)
        CompositionLocalProvider(LocalContentColor provides wd.textPrimary) {
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
@Composable internal fun Modifier.settingsSegment(first:Boolean,last:Boolean,radius:Dp=10.dp):Modifier {
    val shape=RoundedCornerShape(topStart=if(first) radius else 0.dp,topEnd=if(first) radius else 0.dp,bottomStart=if(last) radius else 0.dp,bottomEnd=if(last) radius else 0.dp)
    val line=wd.border
    return fillMaxWidth().clip(shape).background(wd.card).drawWithContent {
        drawContent()
        val stroke=1.dp.toPx()
        clipRect(top=if(first) 0f else stroke,bottom=if(last) size.height else size.height-stroke) {
            drawOutline(shape.createOutline(size,layoutDirection,this),line,style=Stroke(stroke*2))
        }
        if(!first) drawLine(line,Offset(16.dp.toPx(),stroke/2),Offset(size.width,stroke/2),stroke)
    }
}
