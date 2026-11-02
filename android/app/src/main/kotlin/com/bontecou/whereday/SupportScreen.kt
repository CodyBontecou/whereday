package com.bontecou.whereday

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*

internal data class SupportInfo(val version:String,val api:Int,val model:String) {
    val reportHeader:String get()="App: Whereday $version\nPlatform: Android API $api\nDevice: $model"
    companion object {
        fun from(context:Context):SupportInfo {
            @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,0)
            fun safe(value:String?)=value.orEmpty().filter {it>=' ' && it!='\u007f'}.take(120).ifBlank {"Unknown"}
            return SupportInfo("${safe(info.versionName)} (${PackageInfoCompat.getLongVersionCode(info)})",Build.VERSION.SDK_INT,safe(Build.MODEL))
        }
    }
}
internal enum class SupportLink(val uri:String) {
    website("https://whereday.com"),privacy("https://whereday.com/privacy"),terms("https://whereday.com/terms"),
    community("https://discord.gg/RaQYS4t6gn")
}
internal object SupportIntents {
    const val EMAIL="cody@isolated.tech"
    fun feedback(info:SupportInfo)=Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:$EMAIL?subject=${Uri.encode("Whereday Android feedback")}&body=${Uri.encode("\n\n---\n${info.reportHeader}")}"))
    fun link(link:SupportLink)=Intent(Intent.ACTION_VIEW,Uri.parse(link.uri))
    fun review(web:Boolean=false)=Intent(Intent.ACTION_VIEW,Uri.parse(if(web) "https://play.google.com/store/apps/details?id=${PlayReviews.packageName}" else "market://details?id=${PlayReviews.packageName}"))
    fun copy(context:Context,text:String) {
        val clip=ClipData.newPlainText("Whereday support",text)
        if(Build.VERSION.SDK_INT>=33) clip.description.extras=PersistableBundle().apply {putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}

@Composable internal fun SupportScreen(graph:AppGraph,dismiss:()->Unit) {
    val context=LocalContext.current
    val info=remember(context) {SupportInfo.from(context)}
    var route by rememberSaveable {mutableStateOf("support")}
    var error by remember {mutableStateOf(false)}
    fun open(intent:Intent) {try {context.startActivity(intent);error=false} catch(_:android.content.ActivityNotFoundException) {error=true} catch(_:SecurityException) {error=true}}
    when(route) {
        "logs"->{val entries by graph.activityLog.entries.collectAsStateWithLifecycle();ActivityLogsScreen(entries,info,{route="support"},graph.activityLog::clear) {SupportIntents.copy(context,it)};return}
        "releases"->{AndroidReleaseNotes {route="support"};return}
    }
    BackHandler(onBack=dismiss)
    SupportForm(info,error,dismiss,{route="logs"},{route="releases"},{open(SupportIntents.link(it))},{open(SupportIntents.feedback(info))},{SupportIntents.copy(context,SupportIntents.EMAIL)},PlayReviews.available) {
        try {context.startActivity(SupportIntents.review());error=false}
        catch(_:android.content.ActivityNotFoundException) {open(SupportIntents.review(true))}
        catch(_:SecurityException) {open(SupportIntents.review(true))}
    }
}

@Composable internal fun SupportForm(info:SupportInfo,error:Boolean,dismiss:()->Unit,logs:()->Unit,releases:()->Unit,link:(SupportLink)->Unit,feedback:()->Unit,copyEmail:()->Unit,reviewAvailable:Boolean=false,review:()->Unit={}) {
    var copied by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("support-screen"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {TextButton(onClick=dismiss) {Text(stringResource(R.string.support_back))}}
        item {Text(stringResource(R.string.support_title),style=MaterialTheme.typography.headlineSmall)}
        item {Text(stringResource(R.string.support_version,info.version));Text(stringResource(R.string.support_platform,info.api))}
        item {OutlinedButton(onClick=logs,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.support_view_logs))}}
        item {OutlinedButton(onClick=releases,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.support_release_notes))}}
        item {OutlinedButton(enabled=reviewAvailable,onClick=review,modifier=Modifier.fillMaxWidth().testTag("support-rate-app")) {Text(stringResource(R.string.support_rate_app))}}
        if(!reviewAvailable) item {Text(stringResource(R.string.support_review_unavailable),style=MaterialTheme.typography.bodySmall)}
        item {HorizontalDivider()}
        item {Text(stringResource(R.string.support_feedback_description))}
        item {OutlinedButton(onClick=feedback,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.support_send_feedback))}}
        item {TextButton(onClick={copyEmail();copied=true}) {Text(stringResource(R.string.support_copy_email))}}
        if(copied) item {Text(stringResource(R.string.support_email_copied),modifier=Modifier.testTag("support-email-copied"))}
        if(error) item {Text(stringResource(R.string.support_no_handler),color=MaterialTheme.colorScheme.error)}
        item {OutlinedButton(onClick={link(SupportLink.community)},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.support_community))}}
        item {OutlinedButton(onClick={link(SupportLink.website)},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.support_website))}}
        item {TextButton(onClick={link(SupportLink.privacy)}) {Text(stringResource(R.string.support_privacy))}}
        item {TextButton(onClick={link(SupportLink.terms)}) {Text(stringResource(R.string.support_terms))}}
    }
}

@Composable internal fun ActivityLogsScreen(entries:List<ActivityLogEntry>,info:SupportInfo,dismiss:()->Unit,clear:()->Unit,copy:(String)->Unit) {
    BackHandler(onBack=dismiss)
    var clearing by remember {mutableStateOf(false)}
    var copied by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("activity-logs-screen"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=dismiss) {Text(stringResource(R.string.support_back))}}
        item {Text(stringResource(R.string.support_logs_title),style=MaterialTheme.typography.headlineSmall)}
        item {Text(stringResource(R.string.support_logs_privacy))}
        item {OutlinedButton(enabled=entries.isNotEmpty(),onClick={copy(info.reportHeader+"\n\n"+entries.joinToString("\n") {it.display});copied=true},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.support_copy_logs))}}
        item {TextButton(enabled=entries.isNotEmpty(),onClick={clearing=true}) {Text(stringResource(R.string.support_clear_logs))}}
        if(copied) item {Text(stringResource(R.string.support_logs_copied))}
        if(entries.isEmpty()) item {Text(stringResource(R.string.support_logs_empty))}
        items(entries.asReversed(),key={it.id}) {entry->SelectionContainer {Text(entry.display,style=MaterialTheme.typography.bodySmall,color=if(entry.event.level=="ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)}}
    }
    if(clearing) WorkspaceAlertDialog(onDismissRequest={clearing=false},title={Text(stringResource(R.string.support_clear_logs_question))},text={Text(stringResource(R.string.support_clear_logs_description))},confirmButton={TextButton(onClick={clear();clearing=false;copied=false}) {Text(stringResource(R.string.support_clear_logs))}},dismissButton={TextButton(onClick={clearing=false}) {Text(stringResource(R.string.support_cancel))}})
}

@Composable internal fun AndroidReleaseNotes(dismiss:()->Unit) {
    BackHandler(onBack=dismiss)
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("android-release-notes"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {TextButton(onClick=dismiss) {Text(stringResource(R.string.support_back))}}
        item {Text(stringResource(R.string.support_release_notes),style=MaterialTheme.typography.headlineSmall)}
        item {Text(stringResource(R.string.support_preview_title),style=MaterialTheme.typography.titleLarge)}
        item {Text(stringResource(R.string.support_preview_notice))}
        item {Text(stringResource(R.string.support_preview_journal))}
        item {Text(stringResource(R.string.support_preview_portability))}
        item {Text(stringResource(R.string.support_preview_automation))}
    }
}
