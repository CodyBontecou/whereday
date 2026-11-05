package com.bontecou.whereday

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
    delete:suspend (CountryStay)->Unit,today:LocalDate=LocalDate.now()
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
    WorkspaceAlertDialog(onDismissRequest={close()},title={Text(stringResource(if(stay==null) R.string.stay_add else R.string.stay_edit))},text={
        BackHandler {close()}
        Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("stay-editor-content").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.stay_explanation),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
            WdButton(stringResource(R.string.stay_country,stayCountryName(code,locale),code),{chooseCountry=true},Modifier.testTag("stay-country"),enabled=!busy,icon=Icons.Outlined.Public,trailingIcon=Icons.Filled.UnfoldMore)
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
        }
    },confirmButton={TextButton(enabled=valid&&!busy,onClick={perform(false)},modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("stay-save")) {Text(stringResource(R.string.stay_save))}},dismissButton={TextButton(enabled=!busy,onClick={close()},modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("stay-close")) {Text(stringResource(R.string.stay_cancel))}})
    if(chooseCountry) StayCountrySelector(code,{chooseCountry=false}) {code=it;chooseCountry=false}
    // Country stays store country-local civil dates, independent of this phone's time zone.
    if(chooseDates) CustomDateRange(first,last,{chooseDates=false},latestDate=today,zone=null) {a,b->firstText=a.toString();lastText=b.toString();chooseDates=false}
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
