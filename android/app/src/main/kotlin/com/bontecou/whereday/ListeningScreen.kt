package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Webhook
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.data.AppSettings
import java.text.NumberFormat

/** "How Whereday listens" (iOS ListeningView, the Recording board): what is recording now, how much it keeps,
 *  the recording settings in effect, and what stays on the phone versus what leaves it. Values are the real settings. */
@Composable internal fun ListeningScreen(settings:AppSettings,active:Boolean,back:()->Unit,onRecord:(()->Unit)?,openVisitsOnly:()->Unit,
    openSends:()->Unit,openPermissions:()->Unit,setGeocoding:(Boolean)->Unit) {
    BackHandler(onBack=back)
    val number=NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("listening-screen")) {
        WdTopBar(stringResource(R.string.listening_bar),back,backLabel=stringResource(R.string.common_back))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom=28.dp)) {
            Text(stringResource(R.string.listening_title),Modifier.padding(horizontal=20.dp).padding(top=4.dp).semantics {heading()},style=WdType.pageTitle.copy(fontSize=34.sp,lineHeight=40.sp),color=wd.textPrimary)
            // Status: navy ink card with the live dot while recording.
            Row(Modifier.padding(horizontal=16.dp).padding(top=16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(wd.inkCard).padding(start=20.dp,end=12.dp,top=16.dp,bottom=16.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if(active) wd.liveDot else wd.onInkCardMuted))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(if(active) R.string.map_redesign_recording else R.string.map_redesign_standby).uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.12.em),color=wd.onInkCardMuted)
                    Text(stringResource(if(active) R.string.listening_status_on else R.string.listening_status_off),style=WdType.title3.copy(fontWeight=FontWeight.Normal),color=wd.onInkCard)
                }
                if(onRecord!=null) Box(Modifier.heightIn(min=40.dp).clip(CircleShape).background(if(active) wd.onInkCard else Color.Transparent)
                    .then(if(active) Modifier else Modifier.border(1.dp,wd.onInkCardMuted,CircleShape)).clickable(role=Role.Button,onClick=onRecord).padding(horizontal=16.dp).testTag("listening-toggle"),contentAlignment=Alignment.Center) {
                    Text(stringResource(if(active) R.string.shortcut_stop else R.string.shortcut_start),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=if(active) wd.inkCard else wd.onInkCard)
                }
            }
            WdSectionHeader(stringResource(R.string.listening_remember))
            Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                ListeningMode(stringResource(R.string.listening_mode_routes),stringResource(R.string.listening_mode_routes_body),selected=active,onClick=null)
                ListeningMode(stringResource(R.string.visits_title),stringResource(R.string.listening_mode_visits_body),selected=false,onClick=openVisitsOnly)
            }
            WdCard(Modifier.padding(horizontal=16.dp).padding(top=12.dp)) {
                WdRow {Text(stringResource(R.string.settings_distance),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge);Text(stringResource(R.string.settings_meters,number.format(settings.distanceFilter)),style=WdType.caption,color=wd.textMuted)}
                WdRow(divider=false) {Text(stringResource(R.string.settings_stop_after),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                    Text(if(settings.stopAfterHours==0) stringResource(R.string.settings_never) else stringResource(R.string.settings_hours,number.format(settings.stopAfterHours)),style=WdType.caption,color=wd.textMuted)}
                Text(stringResource(R.string.settings_recording_help),Modifier.padding(start=20.dp,end=20.dp,bottom=12.dp),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
            }
            WdSectionHeader(stringResource(R.string.listening_stays_leaves))
            WdCard(Modifier.padding(horizontal=16.dp)) {
                WdRow {Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.listening_journal),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                    Text(stringResource(R.string.listening_journal_where),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                }}
                WdToggleRow(stringResource(R.string.listening_place_names),settings.networkGeocoding,setGeocoding,Modifier.testTag("listening-geocoding"),detail=stringResource(R.string.listening_place_names_body))
                WdNavRow(stringResource(R.string.listening_sends),icon=Icons.Outlined.Webhook,onClick=openSends)
                WdNavRow(stringResource(R.string.settings_permissions),icon=Icons.Outlined.LocationOn,divider=false,onClick=openPermissions)
            }
            Text(stringResource(R.string.listening_footer),Modifier.padding(horizontal=24.dp).padding(top=20.dp),style=WdType.serifBody.copy(fontStyle=FontStyle.Italic),color=wd.textMuted)
        }
    }
}

@Composable private fun ListeningMode(title:String,body:String,selected:Boolean,onClick:(()->Unit)?) {
    val shape=RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if(selected) wd.secondaryContainer else wd.containerLow)
        .then(if(onClick!=null) Modifier.clickable(role=Role.Button,onClick=onClick) else Modifier).semantics {this.selected=selected}
        .padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.padding(top=3.dp).size(20.dp).clip(CircleShape).border(2.dp,if(selected) wd.inkRust else wd.outline,CircleShape),contentAlignment=Alignment.Center) {
            if(selected) Box(Modifier.size(10.dp).clip(CircleShape).background(wd.inkRust))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(title,style=WdType.title3.copy(fontWeight=FontWeight.Normal),color=wd.textPrimary)
            Text(body,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        }
    }
}
