package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.MapLayerSettings
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.launch

/** M4 "Layers": every old map layer with its help text (LayerToggleHelp), persisted in app preferences. */
@Composable internal fun MapLayersSheet(graph:AppGraph,settings:MapLayerSettings,onSettings:(MapLayerSettings)->Unit,showOutliers:Boolean,onOutliers:(Boolean)->Unit,
    photoLayer:PhotoMapLayer?,liveAvailable:Boolean,dismiss:()->Unit) {
    val colors=wd
    val app by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    var roads by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    BlockVisitLinks()
    WorkspaceDialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false),fillRegion=true) {
        Box(Modifier.fillMaxSize().background(colors.textPrimary.copy(alpha=if(colors.dark) 0.5f else 0.28f))
            .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,onClick=dismiss)) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.84f).clip(RoundedCornerShape(topStart=26.dp,topEnd=26.dp)).background(colors.surface)
                .clickable(interactionSource=remember {MutableInteractionSource()},indication=null) {}
                .semantics {testTagsAsResourceId=true}.testTag("map-layers-content")) {
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=16.dp,top=18.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.map_home_layers_kicker).uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.15.em,fontWeight=FontWeight.Medium),color=colors.textMuted)
                        Text(stringResource(R.string.map_layers),style=WdType.title.copy(fontSize=28.sp),color=colors.textPrimary,modifier=Modifier.semantics {heading()})
                    }
                    Box(Modifier.heightIn(min=44.dp).clip(CircleShape).background(colors.accent).clickable(role=Role.Button,onClick=dismiss).padding(horizontal=18.dp).testTag("map-layers-done"),
                        contentAlignment=Alignment.Center) {Text(stringResource(R.string.map_layers_done),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold,fontSize=14.sp),color=colors.surface)}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=30.dp).navigationBarsPadding()) {
                    LayerRow(Icons.Outlined.Place,R.string.map_home_layer_visits,R.string.map_home_layer_visits_help,settings.visits,"map-layer-visits") {onSettings(settings.copy(visits=it))}
                    LayerRow(Icons.Outlined.Timeline,R.string.map_home_layer_path,R.string.map_home_layer_path_help,settings.path,"map-layer-path") {onSettings(settings.copy(path=it))}
                    Row(Modifier.padding(start=48.dp,bottom=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        LayerSubChip(stringResource(R.string.map_home_layer_match_roads),app?.roadDisplayEnabled==true,"map-layer-roads") {roads=true}
                        LayerSubChip(stringResource(R.string.map_home_layer_straight),settings.straightGaps,"map-layer-straight") {onSettings(settings.copy(straightGaps=!settings.straightGaps))}
                    }
                    LayerDivider()
                    LayerRow(Icons.Outlined.ScatterPlot,R.string.map_home_layer_points,R.string.map_home_layer_points_help,settings.points,"map-layer-points") {onSettings(settings.copy(points=it))}
                    LayerRow(Icons.Outlined.Flag,R.string.map_home_layer_endpoints,R.string.map_home_layer_endpoints_help,settings.endpoints,"map-layer-endpoints") {onSettings(settings.copy(endpoints=it))}
                    LayerRow(Icons.Outlined.FiberManualRecord,R.string.map_home_layer_live,R.string.map_home_layer_live_help,settings.liveSession,"map-layer-active-recording",enabled=liveAvailable || settings.liveSession) {onSettings(settings.copy(liveSession=it))}
                    val photosOn=photoLayer?.enabled ?: (app?.showPhotoMarkers==true)
                    LayerRow(Icons.Outlined.PhotoLibrary,R.string.map_home_layer_photos,R.string.map_home_layer_photos_help,photosOn,"map-layer-photos",divider=!photosOn) {value->
                        photoLayer?.enable?.invoke(value) ?: scope.launch {graph.preferences.update {it.copy(showPhotoMarkers=value)}}
                    }
                    if(photosOn) {
                        // Images / Pins / Dots map onto the stored image and dot preferences.
                        val dots=app?.showPhotoDots==true;val images=app?.showPhotoImages!=false
                        val current=if(dots) 2 else if(images) 0 else 1
                        Row(Modifier.padding(start=48.dp,bottom=10.dp).testTag("map-layer-photo-style"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf(R.string.map_home_photo_images to "map-layer-photo-images",R.string.map_home_photo_pins to "map-layer-photo-pins",R.string.map_home_photo_dots to "map-layer-photo-dots").forEachIndexed {index,(label,tag)->
                                LayerSubChip(stringResource(label),current==index,tag,radio=true) {
                                    scope.launch {graph.preferences.update {it.copy(showPhotoDots=index==2,showPhotoImages=if(index==2) it.showPhotoImages else index==0)}}
                                }
                            }
                        }
                        LayerDivider()
                    }
                    LayerRow(Icons.Outlined.Help,R.string.map_home_layer_suggestions,R.string.map_home_layer_suggestions_help,settings.visitSuggestions,"map-layer-suggestions") {onSettings(settings.copy(visitSuggestions=it))}
                    LayerRow(Icons.Outlined.ReportGmailerrorred,R.string.map_layer_outliers,R.string.map_outliers_main,showOutliers,"map-layer-outliers",change=onOutliers)
                    LayerRow(Icons.Outlined.Public,R.string.map_home_layer_online,R.string.map_tiles_disclosure,settings.online,"map-layer-online",divider=false) {onSettings(settings.copy(online=it))}
                }
            }
        }
    }
    if(roads) WorkspaceDialog(onDismissRequest={roads=false},properties=DialogProperties(usePlatformDefaultWidth=false),fillRegion=true) {
        Surface(Modifier.fillMaxSize(),color=colors.surface) {RoadSettingsScreen(graph) {roads=false}}
    }
}

@Composable private fun LayerRow(icon:ImageVector,title:Int,help:Int,checked:Boolean,tag:String,enabled:Boolean=true,divider:Boolean=true,change:(Boolean)->Unit) {
    val colors=wd
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(checked,enabled=enabled,role=Role.Switch,onValueChange=change).padding(vertical=10.dp).testTag(tag),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if(colors.dark) colors.card else Color(0xFFECE6D8)),contentAlignment=Alignment.Center) {
                Icon(icon,null,Modifier.size(18.dp),tint=if(checked) colors.textPrimary else colors.textMuted)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(stringResource(title),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium,fontSize=15.sp),color=if(enabled) colors.textPrimary else colors.textMuted)
                Text(stringResource(help),style=MaterialTheme.typography.bodySmall.copy(fontSize=12.sp,lineHeight=16.sp),color=colors.textMuted)
            }
            Switch(checked,null,enabled=enabled,colors=wdSwitchColors())
        }
        if(divider) LayerDivider()
    }
}

@Composable private fun LayerDivider()=HorizontalDivider(color=if(wd.dark) wd.border else Color(0xFFE4DED0),thickness=1.dp)

@Composable private fun LayerSubChip(text:String,selected:Boolean,tag:String,radio:Boolean=false,onClick:()->Unit) {
    val colors=wd
    Box(Modifier.heightIn(min=44.dp).then(if(radio) Modifier.selectable(selected,role=Role.RadioButton,onClick=onClick) else Modifier.toggleable(selected,role=Role.Checkbox) {onClick()}).testTag(tag),
        contentAlignment=Alignment.Center) {
        Box(Modifier.heightIn(min=32.dp).clip(CircleShape).background(if(selected) colors.accent else colors.card).border(1.dp,if(selected) colors.accent else colors.border,CircleShape).padding(horizontal=12.dp),
            contentAlignment=Alignment.Center) {
            Text(text,style=MaterialTheme.typography.bodySmall.copy(fontWeight=FontWeight.Medium),color=if(selected) colors.surface else colors.textPrimary,maxLines=1)
        }
    }
}
