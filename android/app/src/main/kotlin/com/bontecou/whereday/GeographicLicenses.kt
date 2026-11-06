package com.bontecou.whereday

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val card=Modifier.padding(horizontal=16.dp)
    Column(Modifier.fillMaxSize().background(wd.surface)) {
        SettingsBackHeader(if(document!=null) stringResource(document.label) else stringResource(R.string.geo_license_title),
            stringResource(if(document!=null) R.string.geo_license_back_overview else R.string.geo_license_back_settings),{leave()},backTag="geo-license-back")
        if(document!=null) {
            val loaded=result?.takeIf {it.document==document}
            when {
                loaded==null->SettingsProgress(stringResource(R.string.geo_license_loading),card.padding(top=8.dp))
                loaded.failed->SettingsNotice(card,warning=true) {
                    Text(stringResource(R.string.geo_license_load_failed),Modifier.testTag("geo-license-error"))
                    SettingsTextAction(stringResource(R.string.geo_license_retry),{if(interactive() && currentDocument==document) retry++},Modifier.testTag("geo-license-retry"))
                }
                else->key(document) {
                    val shape=RoundedCornerShape(10.dp)
                    Text(requireNotNull(loaded.text),card.padding(bottom=24.dp).weight(1f).fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape)
                        .verticalScroll(rememberScrollState()).testTag("geo-license-text").padding(16.dp),style=WdType.caption2,color=wd.textPrimary)
                }
            }
        } else Column(Modifier.weight(1f).verticalScroll(overviewScroll).testTag("geo-license-overview").padding(bottom=32.dp)) {
            Text(stringResource(R.string.geo_license_boundary_disclosure),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
            Text(stringResource(R.string.geo_license_versions),Modifier.padding(start=20.dp,end=20.dp,top=8.dp),style=WdType.caption,color=wd.textMuted)
            WdSectionHeader(stringResource(R.string.redesign_settings_licenses))
            WdCard(card) {
                GeographicDocument.entries.forEachIndexed {index,item->WdNavRow(stringResource(item.label),Modifier.testTag("geo-license-document-${item.name}"),icon=Icons.Outlined.Description,
                    divider=index<GeographicDocument.entries.lastIndex,onClick={if(interactive() && currentDocument==null) {selection=item.name;linkFailed=false}})}
            }
            WdSectionHeader(stringResource(R.string.redesign_settings_sources))
            WdCard(card) {
                GeographicLink.entries.take(2).forEachIndexed {index,item->LicenseLink(item,index<1,interactive={interactive() && currentDocument==null},open={try {latestOpen(it);linkFailed=false} catch(_:Exception) {linkFailed=true}})}
            }
            WdSectionFooter(stringResource(R.string.network_geocoding_disclosure))
            WdCard(card.padding(top=16.dp)) {
                GeographicLink.entries.drop(2).forEachIndexed {index,item->LicenseLink(item,index<GeographicLink.entries.size-3,interactive={interactive() && currentDocument==null},open={try {latestOpen(it);linkFailed=false} catch(_:Exception) {linkFailed=true}})}
            }
            WdSectionFooter(stringResource(R.string.geo_license_geocoder_disclosure))
            if(linkFailed) SettingsNotice(card.padding(top=12.dp),warning=true) {Text(stringResource(R.string.geo_license_link_failed),Modifier.testTag("geo-license-link-error"))}
        }
    }
}
@Composable private fun LicenseLink(link:GeographicLink,divider:Boolean,interactive:()->Boolean,open:(GeographicLink)->Unit) {
    WdNavRow(stringResource(link.label),Modifier.testTag("geo-license-link-${link.name}"),icon=Icons.AutoMirrored.Outlined.OpenInNew,divider=divider,onClick={if(interactive()) open(link)})
}
