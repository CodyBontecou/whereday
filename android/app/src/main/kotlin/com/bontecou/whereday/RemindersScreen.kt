@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bontecou.whereday

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.EveningRecap
import com.bontecou.whereday.platform.RecapText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime

/** Settings › Reminders: the evening recap's days, time, quiet-day rule and lock-screen names, with a live preview. */
@Composable internal fun RemindersScreen(graph:AppGraph,settings:RecapSettings,metric:Boolean,back:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var allowed by remember {mutableStateOf(EveningRecap.allowed(context))}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) allowed=EveningRecap.allowed(context)}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    // The preview reads what the journal holds so far for the period the next recap covers.
    var facts by remember {mutableStateOf<RecapFacts?>(null)}
    LaunchedEffect(settings.frequency) {
        facts=try {
            val now=ZonedDateTime.now()
            val period=RecapSchedule.period(settings.frequency,now)
            EveningRecap.facts(graph,period,settings.frequency==RecapFrequency.weekly)
        } catch(e:CancellationException) {throw e} catch(_:Exception) {null}
    }
    RemindersContent(settings,facts,metric,allowed,ZonedDateTime.now(),back,
        openNotificationSettings={
            try {context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            catch(_:Exception) {graph.settingsActions.reportFailure(com.bontecou.whereday.platform.SettingsFailure.permissions)}
        }) {value->
        scope.launch {
            try {
                graph.preferences.update {it.withRecap(value)}
                EveningRecap.schedule(context,graph.preferences.settings.first().recap)
            } catch(e:CancellationException) {throw e} catch(_:Exception) {graph.settingsActions.reportFailure(com.bontecou.whereday.platform.SettingsFailure.preferences)}
        }
    }
}

/** The frequency rows, in the design's order. */
private val frequencyRows=listOf(
    Triple(RecapFrequency.off,R.string.reminders_off,R.string.reminders_off_hint),
    Triple(RecapFrequency.daily,R.string.reminders_daily,R.string.reminders_daily_hint),
    Triple(RecapFrequency.weekdays,R.string.reminders_weekdays,R.string.reminders_weekdays_hint),
    Triple(RecapFrequency.weekly,R.string.reminders_weekly,R.string.reminders_weekly_hint))

/** "Every evening · 9:00 PM" on the Settings row, or "Off". */
@Composable internal fun remindersSummary(settings:RecapSettings):String {
    if(!settings.enabled) return stringResource(R.string.reminders_off)
    val label=stringResource(frequencyRows.first {it.first==settings.frequency}.second)
    return stringResource(R.string.reminders_summary,label,recapClock(settings.time))
}

/** The preview's example, from the onboarding sample day, when nothing is recorded yet. */
@Composable private fun exampleContent(settings:RecapSettings):RecapContent {
    val weekly=settings.frequency==RecapFrequency.weekly
    val names=listOf(stringResource(R.string.onboarding_sample_home),stringResource(R.string.onboarding_sample_cafe),stringResource(R.string.onboarding_sample_park))
    val facts=RecapFacts(if(weekly) 11 else 4,names,if(weekly) 1_520_000.0 else 5_600.0,if(weekly) 2 else null,recorded=true,tracked=true,newPlace=true)
    return RecapContent.of(facts,weekly,settings.placeNames)
}

@Composable internal fun RemindersContent(settings:RecapSettings,facts:RecapFacts?,metric:Boolean,notificationsAllowed:Boolean,now:ZonedDateTime,back:()->Unit,
    openNotificationSettings:()->Unit,change:(RecapSettings)->Unit) {
    BackHandler(onBack=back)
    val context=LocalContext.current
    val on=settings.enabled
    var picking by remember {mutableStateOf(false)}
    val card=Modifier.padding(horizontal=16.dp)
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("reminders-screen").verticalScroll(rememberScrollState()).padding(bottom=32.dp)) {
        SettingsBackHeader(stringResource(R.string.reminders_title),stringResource(R.string.shell_settings),back,backTag="reminders-back")
        // Preview of the next notification.
        val weekly=settings.frequency==RecapFrequency.weekly
        val live=facts?.takeIf {it.recorded}
        val content=live?.let {RecapContent.of(it,weekly,settings.placeNames)} ?: exampleContent(settings)
        val shape=RoundedCornerShape(18.dp)
        Row(card.fillMaxWidth().clip(shape).background(wd.inkCard).heightIn(min=60.dp).padding(horizontal=14.dp,vertical=12.dp).testTag("reminders-preview")
            .semantics(mergeDescendants=true) {},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(on) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(wd.surface),contentAlignment=Alignment.Center) {
                    Icon(Icons.Outlined.Today,null,Modifier.size(20.dp),tint=wd.accent)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(RecapText.title(context,content),Modifier.weight(1f).testTag("reminders-preview-title"),
                            style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold),color=wd.onInkCard)
                        Text(previewWhen(settings),Modifier.testTag("reminders-preview-when"),style=WdType.caption2,color=wd.onInkCardMuted,maxLines=1)
                    }
                    Text(RecapText.body(context,content,metric),Modifier.testTag("reminders-preview-body"),style=MaterialTheme.typography.bodySmall.copy(fontSize=13.sp),color=wd.onInkCard)
                    if(live==null) Text(stringResource(R.string.reminders_preview_example),Modifier.testTag("reminders-preview-example"),style=WdType.caption2,color=wd.onInkCardMuted)
                }
            } else Text(stringResource(R.string.reminders_preview_off),Modifier.testTag("reminders-preview-off"),style=MaterialTheme.typography.bodyMedium,color=wd.onInkCardMuted)
        }
        if(!notificationsAllowed) SettingsNotice(card.padding(top=12.dp).testTag("reminders-blocked"),warning=true,icon=Icons.Outlined.NotificationsOff) {
            Text(stringResource(R.string.reminders_blocked))
            SettingsTextAction(stringResource(R.string.reminders_blocked_action),openNotificationSettings,Modifier.testTag("reminders-open-notification-settings"))
        }
        WdSectionHeader(stringResource(R.string.reminders_day_recap))
        WdCard(card.selectableGroup()) {
            frequencyRows.forEachIndexed {index,(frequency,label,hint)->
                val selected=settings.frequency==frequency
                WdRow(Modifier.selectable(selected,role=Role.RadioButton) {if(!selected) change(settings.copy(frequency=frequency))}.testTag("reminders-frequency-${frequency.key}"),
                    divider=index<frequencyRows.lastIndex) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(label),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                        Text(stringResource(hint),style=WdType.caption2,color=wd.textMuted)
                    }
                    if(selected) Icon(Icons.Filled.Check,null,Modifier.size(18.dp),tint=wd.accent)
                }
            }
        }
        WdCard(card.padding(top=16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.reminders_time),style=MaterialTheme.typography.bodyLarge,color=if(on) wd.textPrimary else wd.textMuted)
                val timeLabel=stringResource(R.string.reminders_time)
                FlowRow(Modifier.semantics {contentDescription=timeLabel}.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    RecapSettings.PRESETS.forEach {minute->
                        WdChip(recapClock(LocalTime.of(minute/60,minute%60)),settings.minuteOfDay==minute,{change(settings.copy(minuteOfDay=minute))},
                            Modifier.testTag("reminders-time-${minute/60}"),enabled=on)
                    }
                    WdChip(if(settings.preset) stringResource(R.string.reminders_time_custom) else stringResource(R.string.reminders_time_custom_value,recapClock(settings.time)),
                        !settings.preset,{picking=true},Modifier.testTag("reminders-time-custom"),enabled=on)
                }
            }
            WdDivider(Modifier.padding(start=20.dp))
            SettingsToggle(stringResource(R.string.reminders_quiet),settings.skipQuietDays,on,Modifier.testTag("reminders-quiet"),detail=stringResource(R.string.reminders_quiet_detail)) {
                change(settings.copy(skipQuietDays=it))
            }
            SettingsToggle(stringResource(R.string.reminders_names),settings.placeNames,on,Modifier.testTag("reminders-names"),detail=stringResource(R.string.reminders_names_detail),divider=false) {
                change(settings.copy(placeNames=it))
            }
        }
        // Google Play's billing state here does not expose the trial's end, so the design's read-only "Trial ending" row is left out.
        WdSectionFooter(stringResource(R.string.reminders_footer),Modifier.padding(top=4.dp).testTag("reminders-footer"))
    }
    if(picking) RecapTimeDialog(settings,{picking=false}) {minute->picking=false;change(settings.copy(minuteOfDay=minute))}
}

/** "Daily 9:00 PM", "Mon–Fri 9:00 PM", "Sun 9:00 PM". */
@Composable private fun previewWhen(settings:RecapSettings):String {
    val clock=recapClock(settings.time)
    return when(settings.frequency) {
        RecapFrequency.daily->stringResource(R.string.reminders_when_daily,clock)
        RecapFrequency.weekdays->stringResource(R.string.reminders_when_weekdays,clock)
        RecapFrequency.weekly->stringResource(R.string.reminders_when_weekly,clock)
        RecapFrequency.off->""
    }
}

/** Custom time: a dial (or fields at large text), starting half an hour after a preset so confirming picks a custom time. */
@Composable private fun RecapTimeDialog(settings:RecapSettings,dismiss:()->Unit,apply:(Int)->Unit) {
    val start=if(settings.preset) (settings.minuteOfDay+30)%(24*60) else settings.minuteOfDay
    val clock24=android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state=rememberTimePickerState(start/60,start%60,clock24)
    val large=androidx.compose.ui.platform.LocalDensity.current.fontScale>=1.3f
    WorkspaceAlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.reminders_time_dialog))},text={
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("reminders-time-dialog")) {
            if(large) TimeInput(state,colors=visitTimePickerColors()) else TimePicker(state,colors=visitTimePickerColors())
        }
    },confirmButton={TextButton(onClick={apply(state.hour*60+state.minute)},Modifier.testTag("reminders-time-confirm")) {Text(stringResource(R.string.reminders_time_confirm))}},
        dismissButton={TextButton(onClick=dismiss,Modifier.testTag("reminders-time-cancel")) {Text(stringResource(R.string.settings_cancel))}})
}
