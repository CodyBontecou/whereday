package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.whereday.domain.SavedPlace
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

@Composable internal fun SavedPlaceChooser(graph:AppGraph,dismiss:()->Unit,select:(SavedPlace)->Unit) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var query by rememberSaveable {mutableStateOf("")}
    val places by produceState<List<SavedPlace>?>(null,graph) {graph.history.dao.observePlaces().collect {value=it.map {row->row.value}}}
    val matches by produceState<List<SavedPlace>?>(null,places,query,locale) {
        value=null
        val current=places ?: return@produceState
        value=withContext(Dispatchers.Default) {
            fun key(text:String)=savedPlaceSearchKey(text,locale)
            val search=key(query.trim());val collator=Collator.getInstance(locale)
            current.filter {ensureActive();search.isEmpty() || search in key(it.name) || search in key(it.address.orEmpty())}
                .sortedWith {a,b->collator.compare(a.name,b.name).takeIf {it!=0} ?: a.id.compareTo(b.id)}
        }
    }
    WorkspaceDialog(fillRegion=true,onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack=dismiss)
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("saved-chooser-content").systemBarsPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            VisitPickerBackChip(stringResource(R.string.visit_saved_cancel),dismiss)
            Text(stringResource(R.string.visit_choose_saved),style=WdType.title,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
            Text(stringResource(R.string.visit_saved_description),style=WdType.caption2,color=wd.textMuted)
            OutlinedTextField(query,{query=it},label={Text(stringResource(R.string.visit_saved_search))},singleLine=true,colors=wdTextFieldColors(),leadingIcon={Icon(Icons.Outlined.Search,null,tint=wd.textMuted)},modifier=Modifier.fillMaxWidth().testTag("saved-chooser-search"),trailingIcon={if(query.isNotEmpty()) TextButton(onClick={query=""}) {Text(stringResource(R.string.visit_saved_clear))}})
            val found=matches
            if(found==null) LinearProgressIndicator(Modifier.fillMaxWidth(),color=wd.accent,trackColor=wd.dialTrack)
            else if(found.isEmpty()) VisitPickerNote(stringResource(if(places.isNullOrEmpty()) R.string.visit_saved_empty else R.string.visit_saved_no_match),if(places.isNullOrEmpty()) Icons.Outlined.BookmarkBorder else Icons.Outlined.SearchOff)
            else LazyColumn(Modifier.weight(1f).testTag("saved-chooser-list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(found,key={it.id}) {place->
                    val shape=RoundedCornerShape(10.dp)
                    Row(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).clickable {select(place)}.padding(16.dp),
                        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Bookmark,null,Modifier.size(20.dp),tint=wd.accent)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(place.name,style=WdType.title3,color=wd.textPrimary);place.address?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
                            Text(stringResource(R.string.visit_saved_use),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.accent)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.textMuted)
                    }
                }
            }
        }}
    }
}
