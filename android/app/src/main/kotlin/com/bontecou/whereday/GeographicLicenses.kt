package com.bontecou.whereday

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class GeographicDocument(val asset:String,val label:Int) {
    timeshape("timeshape-MIT.txt",R.string.geo_license_timeshape),
    boundaries("timezone-boundaries-ODbL.txt",R.string.geo_license_boundaries),
    geometry("esri-Apache.txt",R.string.geo_license_geometry)
}
internal enum class GeographicLink(val url:String,val label:Int) {
    boundaries("https://github.com/evansiroky/timezone-boundary-builder",R.string.geo_license_boundary_source),
    timeshape("https://github.com/RomanIakovlev/timeshape",R.string.geo_license_timeshape_source),
    geoapify("https://www.geoapify.com/",R.string.nearby_attribution),
    osm("https://www.openstreetmap.org/copyright",R.string.nearby_osm)
}
private data class GeographicDocumentResult(val document:GeographicDocument,val text:String?=null,val failed:Boolean=false)

internal suspend fun readGeographicLicense(context:Context,document:GeographicDocument):String=withContext(Dispatchers.IO) {
    context.assets.open("licenses/${document.asset}").bufferedReader(Charsets.UTF_8).use {it.readText()}
}
@Composable fun GeographicLicenses(back:()->Unit) {
    val context=LocalContext.current
    GeographicLicensesContent(back,{readGeographicLicense(context,it)}) {
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it.url)))
    }
}

/** Only enum-selected bundled documents can be read; neither state nor a link supplies an asset path. */
@Composable internal fun GeographicLicensesContent(back:()->Unit,read:suspend(GeographicDocument)->String,open:(GeographicLink)->Unit) {
    var selection by rememberSaveable {mutableStateOf<String?>(null)}
    val document=GeographicDocument.entries.firstOrNull {it.name==selection}
    val currentDocument by rememberUpdatedState(document)
    val latestRead by rememberUpdatedState(read)
    val latestOpen by rememberUpdatedState(open)
    val latestBack by rememberUpdatedState(back)
    var result by remember {mutableStateOf<GeographicDocumentResult?>(null)}
    var retry by remember {mutableIntStateOf(0)}
    var linkFailed by remember {mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))}
    val alive=remember {AtomicBoolean(true)}
    val reading=remember {AtomicReference<Job?>(null)}
    var generation by remember(lifecycle) {mutableLongStateOf(0)}
    val overviewScroll=rememberScrollState()
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP) {
                reading.getAndSet(null)?.cancel()
                generation++
                result=null
            }
            foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose {alive.set(false);reading.getAndSet(null)?.cancel();lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(document,retry,foreground,generation) {
        val owner=currentCoroutineContext().job
        reading.set(owner)
        try {
            result=null
            if(document!=null && foreground) {
                try {
                    val text=latestRead(document)
                    currentCoroutineContext().ensureActive()
                    result=GeographicDocumentResult(document,text)
                } catch(error:CancellationException) {throw error}
                catch(_:Exception) {
                    currentCoroutineContext().ensureActive()
                    result=GeographicDocumentResult(document,failed=true)
                }
            }
        } finally {reading.compareAndSet(owner,null)}
    }
    fun interactive()=alive.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun leave() {
        if(!interactive()) return
        if(currentDocument!=null) selection=null else latestBack()
    }
    BackHandler {leave()}
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick={leave()},modifier=Modifier.testTag("geo-license-back")) {
            Text(stringResource(if(document!=null) R.string.geo_license_back_overview else R.string.geo_license_back_settings))
        }
        if(document!=null) {
            Text(stringResource(document.label),style=MaterialTheme.typography.headlineSmall)
            val loaded=result?.takeIf {it.document==document}
            when {
                loaded==null->{CircularProgressIndicator();Text(stringResource(R.string.geo_license_loading))}
                loaded.failed->{
                    Text(stringResource(R.string.geo_license_load_failed),Modifier.testTag("geo-license-error"),color=MaterialTheme.colorScheme.error)
                    TextButton(onClick={if(interactive() && currentDocument==document) retry++},modifier=Modifier.testTag("geo-license-retry")) {Text(stringResource(R.string.geo_license_retry))}
                }
                else->key(document) {Text(requireNotNull(loaded.text),Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("geo-license-text"),style=MaterialTheme.typography.bodySmall)}
            }
        } else Column(Modifier.weight(1f).verticalScroll(overviewScroll).testTag("geo-license-overview"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.geo_license_title),style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.geo_license_boundary_disclosure))
            Text(stringResource(R.string.geo_license_versions))
            GeographicDocument.entries.forEach {item->TextButton(onClick={if(interactive() && currentDocument==null) {selection=item.name;linkFailed=false}},modifier=Modifier.testTag("geo-license-document-${item.name}")) {Text(stringResource(item.label))}}
            GeographicLink.entries.take(2).forEach {item->LicenseLink(item,interactive={interactive() && currentDocument==null},open={try {latestOpen(it);linkFailed=false} catch(_:Exception) {linkFailed=true}})}
            Text(stringResource(R.string.network_geocoding_disclosure))
            GeographicLink.entries.drop(2).forEach {item->LicenseLink(item,interactive={interactive() && currentDocument==null},open={try {latestOpen(it);linkFailed=false} catch(_:Exception) {linkFailed=true}})}
            Text(stringResource(R.string.geo_license_geocoder_disclosure))
            if(linkFailed) Text(stringResource(R.string.geo_license_link_failed),Modifier.testTag("geo-license-link-error"),color=MaterialTheme.colorScheme.error)
        }
    }
}
@Composable private fun LicenseLink(link:GeographicLink,interactive:()->Boolean,open:(GeographicLink)->Unit) {
    TextButton(onClick={if(interactive()) open(link)},modifier=Modifier.testTag("geo-license-link-${link.name}")) {Text(stringResource(link.label))}
}
