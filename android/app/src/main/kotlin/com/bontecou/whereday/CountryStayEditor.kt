package com.bontecou.whereday

import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.ui.unit.em
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.CountryStay
import com.bontecou.whereday.domain.CountryRegions
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

internal val CountryStayStateSaver=listSaver<CountryStay?,String>(
    save={it?.let {value->listOf(value.id,value.countryCode,value.firstDay.toString(),value.lastDay.toString())}.orEmpty()},
    restore={if(it.size==4) CountryStay(it[0],it[1],LocalDate.parse(it[2]),LocalDate.parse(it[3])) else null}
)
private fun stayCountryName(code:String,locale:Locale)=CountryRegions.displayName(code,locale)

@Composable internal fun CountryStayEditor(
    stay:CountryStay?,dismiss:()->Unit,save:suspend (CountryStay,CountryStay?)->Unit,
    delete:suspend (CountryStay)->Unit,today:LocalDate=LocalDate.now(),recorded:Set<LocalDate> = emptySet()
) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val date=remember(locale) {DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)}
    val initialCode=rememberSaveable(stay?.id) {stay?.countryCode ?: locale.country.takeIf {it in CountryRegions.picker} ?: "US"}
    val initialFirst=LocalDate.parse(rememberSaveable(stay?.id) {(stay?.firstDay ?: today).toString()})
    val initialLast=LocalDate.parse(rememberSaveable(stay?.id) {(stay?.lastDay ?: today).toString()})
    var id by rememberSaveable(stay?.id) {mutableStateOf(stay?.id ?: UUID.randomUUID().toString())}
    var code by rememberSaveable(stay?.id) {mutableStateOf(initialCode)}
    var firstText by rememberSaveable(stay?.id) {mutableStateOf(initialFirst.toString())}
    var lastText by rememberSaveable(stay?.id) {mutableStateOf(initialLast.toString())}
    var calendarAnchorText by rememberSaveable(stay?.id) {mutableStateOf<String?>(null)}
    val first=LocalDate.parse(firstText);val last=LocalDate.parse(lastText)
    var chooseCountry by rememberSaveable {mutableStateOf(false)}
    var chooseDates by rememberSaveable {mutableStateOf(false)}
    var confirmDelete by rememberSaveable {mutableStateOf(false)}
    var discard by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    val scope=rememberCoroutineScope()
    val valid=code in CountryRegions.picker && first<=last && last<=today
    val dirty=code!=initialCode || first!=initialFirst || last!=initialLast
    fun close() {if(!busy) {if(dirty) discard=true else dismiss()}}
    fun perform(removing:Boolean) {
        if(busy || (!removing && !valid)) return
        busy=true;error=null
        scope.launch {
            try {
                if(removing) {delete(checkNotNull(stay))} else save(CountryStay(id,code,first,last),stay)
                ensureActive();dismiss()
            } catch(e:CancellationException) {throw e}
            catch(_:CountryStayChanged) {error=R.string.stay_changed}
            catch(_:CountryStayFuture) {error=R.string.stay_future}
            catch(_:Exception) {error=if(removing) R.string.stay_delete_error else R.string.stay_save_error}
            finally {busy=false}
        }
    }
    // Country stay board: a full-screen form with the explanation, country, an inline month calendar and Cancel / Save.
    WorkspaceDialog(fillRegion=true,onDismissRequest={close()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler {close()}
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                WdFormTopBar(stringResource(if(stay==null) R.string.stay_add else R.string.stay_edit),{close()},stringResource(R.string.stay_cancel))
                Column(Modifier.weight(1f).semantics {testTagsAsResourceId=true}.testTag("stay-editor-content").verticalScroll(rememberScrollState()).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.stay_explanation),Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(wd.noticeContainer).padding(16.dp),style=MaterialTheme.typography.bodyMedium,color=wd.onNoticeContainer)
                    WdButton(stringResource(R.string.stay_country,stayCountryName(code,locale),code),{chooseCountry=true},Modifier.testTag("stay-country"),enabled=!busy,icon=Icons.Outlined.Public,trailingIcon=Icons.Filled.UnfoldMore)
                    StayCalendar(first,last,today,recorded,enabled=!busy,
                        anchor=calendarAnchorText?.let(LocalDate::parse),anchorChanged={calendarAnchorText=it?.toString()}
                    ) {a,b2->firstText=a.toString();lastText=b2.toString()}
                    WdCard {
                        WdRow {Text(stringResource(R.string.stay_first,date.format(first)),Modifier.weight(1f).testTag("stay-first"),style=WdType.caption,color=if(first>last) wd.inkRust else wd.textPrimary)}
                        WdRow(divider=false) {Text(stringResource(R.string.stay_last,date.format(last)),Modifier.weight(1f).testTag("stay-last"),style=WdType.caption,color=if(last>today || first>last) wd.inkRust else wd.textPrimary)}
                    }
                    WdButton(stringResource(R.string.stay_dates),{chooseDates=true},Modifier.testTag("stay-dates"),enabled=!busy,icon=Icons.Outlined.DateRange)
                    if(first>last) Text(stringResource(R.string.stay_order),style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)
                    if(last>today) Text(stringResource(R.string.stay_future),style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)
                    error?.let {Text(stringResource(it),Modifier.testTag("stay-error"),style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)}
                    if(busy) SettingsProgress(null)
                    if(stay!=null) SettingsTextAction(stringResource(R.string.stay_delete),{confirmDelete=true},Modifier.testTag("stay-delete"),enabled=!busy,destructive=true)
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp).semantics {testTagsAsResourceId=true},horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    WdButton(stringResource(R.string.stay_cancel),{close()},Modifier.weight(1f).testTag("stay-close"),enabled=!busy)
                    WdButton(stringResource(R.string.stay_save),{perform(false)},Modifier.weight(1f).testTag("stay-save"),emphasis=WdEmphasis.primary,enabled=valid && !busy)
                }
            }
        }
    }
    if(chooseCountry) StayCountrySelector(code,{chooseCountry=false}) {code=it;chooseCountry=false}
    // Country stays store country-local civil dates, independent of this phone's time zone.
    if(chooseDates) CustomDateRange(first,last,{chooseDates=false},latestDate=today,zone=null) {a,b->firstText=a.toString();lastText=b.toString();calendarAnchorText=null;chooseDates=false}
    if(confirmDelete) WorkspaceAlertDialog(onDismissRequest={if(!busy) confirmDelete=false},title={Text(stringResource(R.string.stay_delete_title))},text={Text(stringResource(R.string.stay_delete_body))},confirmButton={TextButton(enabled=!busy,onClick={confirmDelete=false;perform(true)},modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("stay-delete-confirm"),colors=ButtonDefaults.textButtonColors(contentColor=wd.inkRust)) {Text(stringResource(R.string.stay_delete_confirm))}},dismissButton={TextButton(enabled=!busy,onClick={confirmDelete=false},modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("stay-delete-cancel")) {Text(stringResource(R.string.stay_cancel))}})
    if(discard) WorkspaceAlertDialog(onDismissRequest={discard=false},title={Text(stringResource(R.string.stay_discard_title))},text={Text(stringResource(R.string.stay_discard_body))},confirmButton={TextButton(onClick=dismiss,modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("stay-discard-confirm"),colors=ButtonDefaults.textButtonColors(contentColor=wd.inkRust)) {Text(stringResource(R.string.stay_discard))}},dismissButton={TextButton(onClick={discard=false}) {Text(stringResource(R.string.stay_keep))}})
}

@Composable internal fun StayCountrySelector(selected:String,dismiss:()->Unit,choose:(String)->Unit) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var query by rememberSaveable {mutableStateOf("")}
    val countries=remember(locale) {CountryRegions.picker.map {it to stayCountryName(it,locale)}.sortedWith(compareBy(java.text.Collator.getInstance(locale)) {it.second})}
    val filtered=remember(query,countries,locale) {val key=savedPlaceSearchKey(query.trim(),locale);countries.filter {savedPlaceSearchKey(it.first,locale).contains(key) || savedPlaceSearchKey(it.second,locale).contains(key)}}
    WorkspaceAlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.stay_choose_country))},text={Column(Modifier.semantics {testTagsAsResourceId=true}) {
        OutlinedTextField(query,{query=it},label={Text(stringResource(R.string.stay_search))},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null,tint=wd.textMuted)},
            shape=RoundedCornerShape(12.dp),colors=wdTextFieldColors(),modifier=Modifier.fillMaxWidth().testTag("stay-country-search"))
        if(filtered.isEmpty()) Text(stringResource(R.string.stay_no_countries),Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        LazyColumn(Modifier.padding(top=8.dp).heightIn(max=320.dp).testTag("stay-country-list")) {
            itemsIndexed(filtered,key={_,it->it.first}) {index,(code,name)->
                WdRow(Modifier.settingsSegment(index==0,index==filtered.lastIndex).testTag("stay-country-$code"),divider=false,onClick={choose(code)}) {
                    Text(stringResource(if(code==selected) R.string.stay_country_selected else R.string.stay_country_option,name,code),Modifier.weight(1f),
                        style=MaterialTheme.typography.bodyLarge.copy(fontWeight=if(code==selected) FontWeight.SemiBold else FontWeight.Normal),color=wd.textPrimary)
                    if(code==selected) Icon(Icons.Filled.Check,null,Modifier.size(18.dp),tint=wd.accent)
                }
            }
        }
    }},confirmButton={TextButton(onClick=dismiss) {Text(stringResource(R.string.stay_cancel))}})
}

/** The board's inline range calendar: one month at a time, tap the first then the last day; recorded days are marked and future days disabled. */
@Composable private fun StayCalendar(
    first:LocalDate,last:LocalDate,today:LocalDate,recorded:Set<LocalDate>,enabled:Boolean,
    anchor:LocalDate?,anchorChanged:(LocalDate?)->Unit,change:(LocalDate,LocalDate)->Unit
) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var month by rememberSaveable {mutableStateOf(java.time.YearMonth.from(first).toString())}
    val shown=java.time.YearMonth.parse(month)
    val title=shown.atDay(1).format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"MMMMyyyy"),locale))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(wd.containerLow).padding(16.dp).testTag("stay-calendar"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.stay_calendar_title),style=WdType.caption2.copy(letterSpacing=0.1.em),color=wd.textMuted)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),style=WdType.title3.copy(fontWeight=FontWeight.Normal),color=wd.textPrimary)
            WdIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,stringResource(R.string.recap_previous),{month=shown.minusMonths(1).toString()})
            WdIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight,stringResource(R.string.recap_next),{month=shown.plusMonths(1).toString()},enabled=shown<java.time.YearMonth.from(today))
        }
        val firstDow=java.time.temporal.WeekFields.of(locale).firstDayOfWeek
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach {i->Text(firstDow.plus(i.toLong()).getDisplayName(java.time.format.TextStyle.NARROW,locale),Modifier.weight(1f),style=WdType.caption2,color=wd.textMuted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
        }
        val lead=((shown.atDay(1).dayOfWeek.value-firstDow.value)+7)%7
        val cells=List(lead) {null}+(1..shown.lengthOfMonth()).map {shown.atDay(it)}
        cells.chunked(7).forEach {week->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach {i->
                    val day=week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp),contentAlignment=Alignment.Center) {
                        if(day!=null) {
                            val inRange=day in first..last
                            val future=day.isAfter(today)
                            val shape=CircleShape
                            Box(Modifier.fillMaxSize().clip(shape).background(if(inRange) wd.accent else Color.Transparent)
                                .then(if(!inRange && day in recorded) Modifier.border(1.5.dp,wd.placeInks[3],shape) else Modifier)
                                .clickable(enabled=enabled && !future,role=Role.Button,onClickLabel=day.toString()) {
                                    val start=anchor
                                    if(start==null) {anchorChanged(day);change(day,day)} else {anchorChanged(null);if(day<start) change(day,start) else change(start,day)}
                                },contentAlignment=Alignment.Center) {
                                Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.bodyMedium,color=when {inRange->wd.surface;future->wd.textMuted.copy(alpha=0.4f);else->wd.textPrimary})
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {Box(Modifier.size(10.dp).clip(CircleShape).border(1.dp,wd.outline,CircleShape));Text(stringResource(R.string.atlas_legend_none),style=WdType.caption2,color=wd.textMuted)}
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp,wd.placeInks[3],CircleShape));Text(stringResource(R.string.stay_calendar_recorded),style=WdType.caption2,color=wd.textMuted)}
        }
    }
}
