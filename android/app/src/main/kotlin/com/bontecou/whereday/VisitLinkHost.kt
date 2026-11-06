package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.time.Instant
import java.util.UUID

/** A bounded UI request, not an automation command or a persisted journal row. */
internal data class VisitLinkRequest(val url:String,val id:String=UUID.randomUUID().toString(),val receivedAt:Instant=Instant.now(),val generation:String?=null) {
    val route=runCatching {ManualVisitLink.parse(url,receivedAt)}.getOrNull()
    fun saved()=arrayListOf(id,url,receivedAt.toString(),generation ?: "__checking__")
    companion object {
        fun receive(url:String,generation:String?=null)=VisitLinkRequest(url.take(ManualVisitLink.MAX_URL_LENGTH+1),generation=generation)
        fun restore(parts:List<String>?):VisitLinkRequest?=runCatching {
            require(parts?.size==4 && parts[1].length<=ManualVisitLink.MAX_URL_LENGTH+1 && parts[3]!="__checking__")
            UUID.fromString(parts[0]);VisitLinkRequest(parts[1],parts[0],Instant.parse(parts[2]),parts[3])
        }.getOrNull()
    }
}
private val visitLinkRequestSaver=Saver<VisitLinkRequest?,ArrayList<String>>(save={it?.saved()},restore={VisitLinkRequest.restore(it)})
internal class VisitLinkPresentations {
    private val tokens=mutableStateMapOf<Any,Unit>()
    val blocked:Boolean get()=tokens.isNotEmpty()
    fun add(token:Any) {tokens[token]=Unit}
    fun remove(token:Any) {tokens.remove(token)}
}
internal val LocalVisitLinkPresentations=staticCompositionLocalOf<VisitLinkPresentations?> {null}
/** Keep an incoming link pending until the current editor/browser/filter is dismissed. */
@Composable internal fun BlockVisitLinks() {
    val owner=LocalVisitLinkPresentations.current
    val token=remember {Any()}
    DisposableEffect(owner,token) {owner?.add(token);onDispose {owner?.remove(token)}}
}

@Composable internal fun VisitLinkHost(graph:AppGraph,pending:VisitLinkRequest?,consumed:(String)->Unit,windowFocused:Boolean=true,showVisitOnMap:((Visit)->Unit)?=null,openMap:()->Unit={}) {
    val presentations=LocalVisitLinkPresentations.current
    var active by rememberSaveable(stateSaver=visitLinkRequestSaver) {mutableStateOf<VisitLinkRequest?>(null)}
    var original by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<Visit?>(null)}
    var openedAt by rememberSaveable {mutableLongStateOf(0)}
    var error by rememberSaveable {mutableStateOf<Int?>(null)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    val latestWindowFocused by rememberUpdatedState(windowFocused)
    val latestPending by rememberUpdatedState(pending)
    val latestConsumed by rememberUpdatedState(consumed)
    val latestOpenMap by rememberUpdatedState(openMap)
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,_->foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(pending?.id,foreground,windowFocused,presentations?.blocked,active?.id,error) {
        val request=pending ?: return@LaunchedEffect
        if(!foreground || !windowFocused || presentations?.blocked==true || active!=null || error!=null) return@LaunchedEffect
        // All current child Dialog effects must register before changing navigation.
        withFrameNanos { }
        if(latestPending?.id!=request.id || !latestWindowFocused || presentations?.blocked==true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val generation=graph.journalClear.state.value.generation.orEmpty()
        if(request.generation!=null && request.generation!=generation) {latestConsumed(request.id);return@LaunchedEffect}
        try {
            val route=request.route
            if(route==null) error=R.string.visit_link_invalid
            else {
                latestOpenMap()
                withFrameNanos { };withFrameNanos { }
                if(latestPending?.id!=request.id || !latestWindowFocused || presentations?.blocked==true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
                val stored=if(route is ManualVisitLink.Existing) graph.history.linkedVisit(route.id) else null
                coroutineContext.ensureActive()
                if(latestPending?.id!=request.id || !latestWindowFocused || presentations?.blocked==true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
                if(route is ManualVisitLink.Existing && stored==null) error=R.string.visit_link_missing
                else {original=stored;openedAt=System.currentTimeMillis();active=request.copy(generation=generation)}
            }
            latestConsumed(request.id)
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {if(latestPending?.id==request.id) {error=R.string.visit_link_load_error;latestConsumed(request.id)}}
    }
    active?.let {request->key(request.id) {
        val route=request.route
        val snapshot=original
        if(route==null || (route is ManualVisitLink.Existing && (snapshot==null || !snapshot.id.equals(route.id,ignoreCase=true)))) {
            LaunchedEffect(request.id) {active=null;original=null;error=R.string.visit_link_load_error}
        } else {
        val seed=(route as? ManualVisitLink.Form)?.seed(Instant.ofEpochMilli(openedAt))
        VisitEditor(graph,snapshot,{if(active?.id==request.id) {active=null;original=null}},
            {value,reuse,remember,overlap->graph.history.saveVisit(value,snapshot,reuse,remember,overlap)},
            snapshot?.let {stored->{graph.history.deleteVisit(stored)}},mode=seed?.mode ?: ManualVisitMode.past,seed=seed,onShowOnMap=showVisitOnMap?.let {show->{stored->
                if(active?.id==request.id) {show(stored);active=null;original=null}
            }})
        }
    }}
    error?.let {message->
        BlockVisitLinks()
        WorkspaceAlertDialog(onDismissRequest={error=null},title={Text(stringResource(R.string.visit_link_title))},text={Text(stringResource(message))},
            confirmButton={TextButton(onClick={error=null},modifier=Modifier.testTag("visit-link-dismiss")) {Text(stringResource(R.string.visit_link_ok))}})
    }
}
