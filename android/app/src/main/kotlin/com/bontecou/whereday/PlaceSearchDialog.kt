package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.text.NumberFormat

@Composable internal fun PlaceSearchDialog(service:PlaceLookupService,networkAllowed:Flow<Boolean>,dismiss:()->Unit,select:(PlaceLookupResult)->Unit) {
    val allowed by networkAllowed.collectAsState(initial=false)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val locale=LocalConfiguration.current.locales[0]
    val number=remember(locale) {NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=6;maximumFractionDigits=6}}
    var query by rememberSaveable {mutableStateOf("")}
    var results by remember {mutableStateOf<List<PlaceLookupResult>>(emptyList())}
    var busy by remember {mutableStateOf(false)}
    var attempted by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    var version by remember {mutableIntStateOf(0)}
    var request by remember {mutableStateOf<Job?>(null)}
    val scope=rememberCoroutineScope()
    fun invalidate() {version++;request?.cancel();request=null;busy=false;attempted=false;results=emptyList();error=null}
    fun close() {invalidate();dismiss()}
    val normalized=query.trim()
    val valid=normalized.codePointCount(0,normalized.length)>=2 && normalized.length<=256
    fun search() {
        if(!allowed || !valid) return
        invalidate();val generation=version;val submitted=query;busy=true;attempted=true
        request=scope.launch {
            try {
                if(!networkAllowed.first() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                val found=service.search(submitted);ensureActive()
                if(version==generation && networkAllowed.first() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) results=found
            } catch(e:Exception) {
                if(e is CancellationException && e !is TimeoutCancellationException) throw e
                if(version==generation) error=when(e) {
                    is PlaceLookupDisabled->R.string.place_search_disabled
                    is TimeoutCancellationException->R.string.place_search_timeout
                    else->R.string.place_search_error
                }
            } finally {if(version==generation) {busy=false;request=null}}
        }
    }
    LaunchedEffect(allowed) {if(!allowed) invalidate()}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) invalidate()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);request?.cancel()}
    }
    WorkspaceDialog(fillRegion=true,onDismissRequest={close()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack={close()})
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().semantics {testTagsAsResourceId=true}.testTag("place-search-content").padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                VisitPickerBackChip(stringResource(R.string.place_search_cancel),{close()},Modifier.testTag("place-search-close"))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.place_search_title),style=WdType.title,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
                    Text(stringResource(R.string.place_search_disclosure),style=WdType.caption2,color=wd.textMuted)
                    if(!allowed) WdNotice(stringResource(R.string.place_search_disabled),icon=Icons.Outlined.CloudOff,warning=true)
                    OutlinedTextField(query,{if(it.length<=256) {invalidate();query=it}},label={Text(stringResource(R.string.place_search_query))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("place-search-query"),
                        colors=wdTextFieldColors(),leadingIcon={Icon(Icons.Outlined.Search,null,tint=wd.textMuted)},
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),
                        trailingIcon={if(query.isNotEmpty()) TextButton(onClick={invalidate();query=""},modifier=Modifier.testTag("place-search-clear")) {Text(stringResource(R.string.place_search_clear))}})
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        WdButton(stringResource(R.string.place_search_submit),{search()},Modifier.weight(1f).testTag("place-search-submit"),emphasis=WdEmphasis.primary,enabled=allowed&&valid&&!busy,icon=Icons.Outlined.Search)
                        if(busy) TextButton(onClick={invalidate()},modifier=Modifier.testTag("place-search-stop")) {Text(stringResource(R.string.place_search_stop))}
                    }
                    if(busy) VisitPickerProgress(stringResource(R.string.place_search_loading))
                    error?.let {WdNotice(stringResource(it),icon=Icons.Outlined.ErrorOutline,warning=true)}
                    if(!busy && error==null && attempted && results.isEmpty()) VisitPickerNote(stringResource(R.string.place_search_empty),Icons.Outlined.SearchOff)
                    if(!attempted && !busy) Text(stringResource(R.string.place_search_hint),style=WdType.caption2,color=wd.textMuted)
                    if(allowed && results.isNotEmpty()) WdCard {
                        results.forEachIndexed {index,result->
                            val generation=version
                            WdRow(Modifier.testTag("place-search-result-${result.id}"),divider=index<results.lastIndex,onClick={
                                scope.launch {
                                    if(networkAllowed.first() && version==generation && result in results && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                        invalidate();select(result)
                                    }
                                }
                            }) {
                                Icon(Icons.Outlined.Place,null,Modifier.size(20.dp),tint=wd.accent)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Text(result.name,style=MaterialTheme.typography.titleMedium,color=wd.textPrimary)
                                    result.address?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
                                    Text(stringResource(R.string.place_search_coordinate,number.format(result.coordinate.latitude),number.format(result.coordinate.longitude)),style=WdType.caption,color=wd.textMuted)
                                    Text(stringResource(R.string.place_search_select),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.accent)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A paper capsule that closes a full-pane picker; unlike WdBackChip it wraps long localized labels. */
@Composable internal fun VisitPickerBackChip(label:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    Row(modifier.heightIn(min=44.dp).clip(RoundedCornerShape(22.dp)).background(wd.card).border(1.dp,wd.border,RoundedCornerShape(22.dp))
        .clickable(role=Role.Button,onClick=onClick).padding(start=10.dp,end=14.dp,top=8.dp,bottom=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack,null,Modifier.size(14.dp),tint=wd.textPrimary)
        Text(label,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

/** A quiet glyph beside muted guidance for empty or no-match lists. */
@Composable internal fun VisitPickerNote(text:String,icon:ImageVector,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(wd.card).border(1.dp,wd.border,CircleShape),contentAlignment=Alignment.Center) {
            Icon(icon,null,Modifier.size(20.dp),tint=wd.accent)
        }
        Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
    }
}

/** An ink progress bar with a mono caption, used while a lookup runs. */
@Composable internal fun VisitPickerProgress(caption:String) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth(),color=wd.accent,trackColor=wd.dialTrack)
        Text(caption,style=WdType.caption,color=wd.textMuted)
    }
}
