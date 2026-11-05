package com.bontecou.whereday

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.data.RecordingRow
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/** The floating header card: mono kicker ("SUN · NOV 1 · DAY 305"), serif range title and the search button. */
@Composable internal fun MapHomeHeader(kicker:String,title:String,onSearch:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    Row(modifier.fillMaxWidth().wdFloating(RoundedCornerShape(22.dp),colors,8.dp).padding(start=14.dp,end=8.dp,top=8.dp,bottom=8.dp).testTag("map-header"),
        verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
            Text(kicker,style=WdType.caption2.copy(fontSize=9.5.sp,lineHeight=13.sp,letterSpacing=0.15.em,fontWeight=FontWeight.Medium),color=colors.textMuted,maxLines=1,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.testTag("map-header-kicker"))
            Text(title,style=WdType.title2.copy(fontSize=22.sp,lineHeight=25.sp),color=colors.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.testTag("map-header-title").semantics {heading()})
        }
        IconButton(onClick=onSearch,modifier=Modifier.size(44.dp).testTag("map-search")) {
            Icon(Icons.Outlined.Search,stringResource(R.string.map_home_search),Modifier.size(20.dp),tint=colors.textPrimary)
        }
    }
}

/** Range chips under the header: Today · Yesterday · 7D · 30D · All, then the calendar for a custom range (MapDatePreset). */
@Composable internal fun MapRangeChips(selected:String,onSelect:(String)->Unit,onCustom:()->Unit,modifier:Modifier=Modifier) {
    val chips=listOf("Today" to R.string.map_date_today,"Yesterday" to R.string.map_date_yesterday,"7 days" to R.string.map_home_chip_week,"30 days" to R.string.map_home_chip_month,"All" to R.string.map_date_all)
    val spoken=mapOf("7 days" to stringResource(R.string.map_date_week),"30 days" to stringResource(R.string.map_date_month))
    Row(modifier.horizontalScroll(rememberScrollState()).testTag("map-range-chips"),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
        chips.forEach {(key,label)->
            MapChip(stringResource(label),selected==key,Modifier.testTag("map-range-${key.replace(' ','-').lowercase()}"),spoken[key]) {onSelect(key)}
        }
        val custom=selected=="Custom"
        val colors=wd
        Box(Modifier.size(44.dp).selectable(custom,role=Role.Button,onClick=onCustom).testTag("map-range-custom"),contentAlignment=Alignment.Center) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(if(custom) colors.accent else colors.card).border(1.dp,if(custom) colors.accent else colors.border,CircleShape),contentAlignment=Alignment.Center) {
                Icon(Icons.Outlined.CalendarMonth,stringResource(R.string.map_home_custom_range),Modifier.size(16.dp),tint=if(custom) colors.surface else colors.textPrimary)
            }
        }
    }
}

/** A 34dp capsule inside a 44dp touch target: navy when selected, paper with a hairline otherwise. */
@Composable internal fun MapChip(text:String,selected:Boolean,modifier:Modifier=Modifier,spoken:String?=null,onClick:()->Unit) {
    val colors=wd
    Box(modifier.heightIn(min=44.dp).selectable(selected,role=Role.Tab,onClick=onClick).then(if(spoken!=null) Modifier.semantics {contentDescription=spoken} else Modifier),contentAlignment=Alignment.Center) {
        Box(Modifier.heightIn(min=34.dp).clip(CircleShape).background(if(selected) colors.accent else colors.card).border(1.dp,if(selected) colors.accent else colors.border,CircleShape)
            .padding(horizontal=12.dp),contentAlignment=Alignment.Center) {
            Text(text,style=MaterialTheme.typography.bodySmall.copy(fontWeight=FontWeight.Medium,fontSize=12.sp),color=if(selected) colors.surface else colors.textPrimary,maxLines=1)
        }
    }
}

/** The right rail: a 56dp live mini dial that opens Replay, then Layers, Fit (long-press: fit the live session) and My location. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun MapRail(dial:@Composable ()->Unit,onLayers:()->Unit,onFit:()->Unit,onFitLive:(()->Unit)?,following:Boolean,onFollow:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    var fitMenu by remember {mutableStateOf(false)}
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        dial()
        Column(Modifier.wdFloating(RoundedCornerShape(16.dp),colors,8.dp).padding(2.dp)) {
            MapRailButton(Icons.Outlined.Layers,stringResource(R.string.map_layers),Modifier.testTag("map-layers"),onClick=onLayers)
            Box {
                val fitLabel=stringResource(R.string.map_home_fit)
                val liveLabel=stringResource(R.string.active_recording_map_fit)
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(role=Role.Button,onClickLabel=fitLabel,onLongClickLabel=if(onFitLive!=null) liveLabel else null,
                    onLongClick=if(onFitLive!=null) ({fitMenu=true}) else null,onClick=onFit).semantics {
                        contentDescription=fitLabel
                        if(onFitLive!=null) customActions=listOf(CustomAccessibilityAction(liveLabel) {onFitLive();true})
                    }.testTag("map-fit"),contentAlignment=Alignment.Center) {
                    Icon(Icons.Outlined.CropFree,null,Modifier.size(20.dp),tint=colors.textPrimary)
                }
                WorkspaceDropdownMenu(fitMenu,{fitMenu=false}) {
                    DropdownMenuItem(text={Text(fitLabel)},leadingIcon={Icon(Icons.Outlined.CropFree,null)},onClick={fitMenu=false;onFit()})
                    if(onFitLive!=null) DropdownMenuItem(text={Text(liveLabel)},leadingIcon={Icon(Icons.Outlined.CenterFocusWeak,null)},onClick={fitMenu=false;onFitLive()},modifier=Modifier.testTag("fit-active-recording"))
                }
            }
            MapRailButton(if(following) Icons.Filled.NearMe else Icons.Outlined.NearMe,stringResource(if(following) R.string.map_following else R.string.map_follow),
                Modifier.testTag("map-follow-location"),tint=if(following) colors.inkRust else colors.textPrimary,selected=following,onClick=onFollow)
        }
    }
}

@Composable private fun MapRailButton(icon:ImageVector,label:String,modifier:Modifier=Modifier,tint:Color=wd.textPrimary,selected:Boolean?=null,onClick:()->Unit) {
    Box(modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).then(if(selected!=null) Modifier.selectable(selected,role=Role.Button,onClick=onClick) else Modifier.clickable(role=Role.Button,onClick=onClick))
        .semantics {contentDescription=label},contentAlignment=Alignment.Center) {
        Icon(icon,null,Modifier.size(20.dp),tint=tint)
    }
}

/** The live mini dial (56dp paper button with a 44dp ring) that opens Replay. */
@Composable internal fun MapMiniDial(segments:List<WdDialSegment>,now:Float?,label:String,enabled:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    Box(modifier.size(56.dp).wdFloating(CircleShape,colors,8.dp).clickable(enabled=enabled,role=Role.Button,onClickLabel=label,onClick=onClick)
        .semantics {contentDescription=label}.testTag("map-replay-dial"),contentAlignment=Alignment.Center) {
        WdDayDial(segments,Modifier.alpha(if(enabled) 1f else 0.5f),now=now,size=46.dp,ring=0.13f)
    }
}


/** The merged recording pill (old MapTrackingControlPill + MapAutoOffPill): ink capsule with a terracotta dot while recording, paper standby otherwise. */
@Composable internal fun MapRecordingPill(active:Boolean,recording:RecordingRow?,points:Long?,onRecord:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    val shape=CircleShape
    val deadline=recording?.deadline?.takeIf {active}
    var now by remember {mutableStateOf(Instant.now())}
    LaunchedEffect(deadline) {while(deadline!=null) {now=Instant.now();kotlinx.coroutines.delay(1000)}}
    val label=stringResource(if(active) R.string.shortcut_stop else R.string.shortcut_start)
    val primary=if(active) {
        val count=points?.let {java.text.NumberFormat.getIntegerInstance(locale).format(it)}
        if(count!=null) stringResource(R.string.map_home_recording_points,count) else stringResource(R.string.map_redesign_recording)
    } else stringResource(R.string.map_redesign_standby)
    val secondary=if(active) deadline?.let {
        val left=Duration.between(now,it).coerceAtLeast(Duration.ZERO)
        val minutes=ceil(left.seconds/60.0).toLong()
        stringResource(R.string.map_home_auto_off,"%d:%02d".format(minutes/60,minutes%60))
    } else stringResource(R.string.map_home_not_recording)
    val ink=if(active) colors.inkCard else colors.card
    val text=if(active) colors.onInkCard else colors.textPrimary
    Row(modifier.heightIn(min=48.dp).wdFloating(shape,colors,8.dp).background(ink).padding(start=16.dp,end=5.dp,top=5.dp,bottom=5.dp).testTag("map-recording-pill")
        .semantics(mergeDescendants=true) {liveRegion=LiveRegionMode.Polite},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(17.dp),contentAlignment=Alignment.Center) {
            if(active) {Box(Modifier.size(17.dp).clip(CircleShape).background(colors.liveDot.copy(alpha=0.3f)));Box(Modifier.size(9.dp).clip(CircleShape).background(colors.placeInks[1]))}
            else Box(Modifier.size(9.dp).clip(CircleShape).background(colors.textMuted.copy(alpha=0.4f)))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
            Text(primary.uppercase(locale),style=WdType.caption2.copy(fontSize=10.sp,lineHeight=13.sp,fontWeight=FontWeight.SemiBold,letterSpacing=0.12.em),color=text,maxLines=2,modifier=Modifier.testTag("map-recording-status"))
            secondary?.let {Text(it.uppercase(locale),style=WdType.caption2.copy(fontSize=9.sp,lineHeight=12.sp,letterSpacing=0.12.em),color=if(active) colors.onInkCard else colors.textMuted,maxLines=2,modifier=Modifier.testTag("map-auto-off"))}
        }
        Box(Modifier.size(38.dp).clip(CircleShape).background(if(active) colors.placeInks[1] else colors.accent).clickable(role=Role.Button,onClickLabel=label,onClick=onRecord)
            .semantics {contentDescription=label}.testTag(if(active) "recording-stop" else "recording-start"),contentAlignment=Alignment.Center) {
            if(active) Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(if(colors.dark) colors.card else colors.card))
            else Icon(Icons.Filled.FiberManualRecord,null,Modifier.size(16.dp),tint=colors.surface)
        }
    }
}

/** The round + beside the recording pill, opening the add menu (old MapAddPlaceMenu): Save where I am, Log a past stop, Drop a pin. */
@Composable internal fun MapAddButton(enabled:Boolean,onSaveHere:()->Unit,onPastStop:()->Unit,onDropPin:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    var open by remember {mutableStateOf(false)}
    Box(modifier) {
        Box(Modifier.size(48.dp).wdFloating(CircleShape,colors,8.dp).clickable(enabled=enabled,role=Role.Button) {open=true}
            .testTag("map-add"),contentAlignment=Alignment.Center) {
            Icon(Icons.Outlined.Add,stringResource(R.string.map_home_add_place),Modifier.size(22.dp),tint=if(enabled) colors.textPrimary else colors.textMuted)
        }
        DropdownMenu(open,{open=false},shape=RoundedCornerShape(18.dp),containerColor=colors.inkCard,modifier=Modifier.widthIn(min=250.dp)) {
            @Composable fun item(text:Int,icon:ImageVector,tag:String,action:()->Unit) {
                DropdownMenuItem(text={Text(stringResource(text),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium),color=colors.onInkCard)},
                    leadingIcon={Icon(icon,null,Modifier.size(18.dp),tint=colors.onInkCard)},onClick={open=false;action()},modifier=Modifier.heightIn(min=48.dp).testTag(tag))
            }
            item(R.string.map_home_save_here,Icons.Outlined.MyLocation,"map-add-current",onSaveHere)
            item(R.string.map_home_log_past,Icons.Outlined.History,"map-add-past",onPastStop)
            item(R.string.map_home_drop_pin,Icons.Outlined.PinDrop,"map-add-pin",onDropPin)
        }
    }
}

/** M7 "Are you here?": the old CurrentPlaceCard as a paper card with Somewhere else / Save this place. */
@Composable internal fun MapHereCard(minutes:Long?,name:String,detail:String,onSomewhereElse:()->Unit,onSave:()->Unit,onDismiss:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    Column(modifier.fillMaxWidth().wdFloating(RoundedCornerShape(22.dp),colors,8.dp).padding(14.dp).testTag("map-here-card").semantics {liveRegion=LiveRegionMode.Polite},
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.Top) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                val kicker=stringResource(R.string.map_home_here_question)+(minutes?.let {" · "+stringResource(R.string.map_home_minutes,it)} ?: "")
                Text(kicker.uppercase(),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.12.em),color=colors.inkRust)
                Text(name,style=WdType.title2.copy(fontSize=24.sp,lineHeight=28.sp),color=colors.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(detail.uppercase(),style=WdType.caption2.copy(letterSpacing=0.1.em),color=colors.textMuted,maxLines=2)
            }
            IconButton(onClick=onDismiss,modifier=Modifier.size(44.dp).testTag("map-here-dismiss")) {Icon(Icons.Outlined.Close,stringResource(R.string.map_home_here_dismiss),tint=colors.textMuted)}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            WdButton(stringResource(R.string.map_home_somewhere_else),onSomewhereElse,Modifier.weight(1f).testTag("map-here-else"))
            WdButton(stringResource(R.string.map_home_save_this_place),onSave,Modifier.weight(1.4f).testTag("map-here-save"),emphasis=WdEmphasis.primary)
        }
    }
}

/** The follow status slip under the rail: finding / accuracy, and Hide my location. */
@Composable internal fun MapFollowSlip(follow:MapFollowUi,metric:Boolean,modifier:Modifier=Modifier) {
    val colors=wd
    if(!follow.showing && follow.message==null) return
    Column(modifier.wdFloating(RoundedCornerShape(14.dp),colors,6.dp).padding(horizontal=10.dp,vertical=6.dp).widthIn(max=220.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        follow.message?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=colors.textMuted,modifier=Modifier.testTag("map-follow-status"))}
        follow.fix?.let {fix->
            val accuracy=ceil(if(metric) fix.accuracyMeters else fix.accuracyMeters/0.3048).toInt().coerceAtLeast(1)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.liveDot))
                Text(stringResource(R.string.map_follow_accuracy,accuracy,if(metric) "m" else "ft"),style=WdType.caption2,color=colors.textPrimary,modifier=Modifier.testTag("map-location-accuracy"))
            }
        }
        if(follow.showing) Text(stringResource(R.string.map_hide_location),Modifier.heightIn(min=32.dp).clickable(role=Role.Button,onClick=follow.hide).testTag("map-hide-location").wrapContentHeight(),
            style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=colors.accent)
    }
}
