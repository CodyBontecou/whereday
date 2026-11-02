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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
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
    val card=Modifier.padding(horizontal=16.dp)
    LazyColumn(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("support-screen"),contentPadding=PaddingValues(bottom=32.dp)) {
        item {SettingsBackHeader(stringResource(R.string.support_title),stringResource(R.string.support_back),dismiss)}
        item {WdCard(card) {WdRow(divider=false) {
            Icon(Icons.Outlined.Info,null,Modifier.size(20.dp),tint=wd.textMuted)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.support_version,info.version),style=WdType.monoBody,color=wd.textPrimary)
                Text(stringResource(R.string.support_platform,info.api),style=WdType.caption,color=wd.textMuted)
            }
        }}}
        item {Spacer(Modifier.height(16.dp))}
        item {WdCard(card) {
            WdNavRow(stringResource(R.string.support_view_logs),icon=Icons.AutoMirrored.Outlined.ReceiptLong,onClick=logs)
            WdNavRow(stringResource(R.string.support_release_notes),icon=Icons.Outlined.NewReleases,onClick=releases)
            WdNavRow(stringResource(R.string.support_rate_app),Modifier.testTag("support-rate-app"),icon=Icons.Outlined.StarOutline,divider=false,enabled=reviewAvailable,onClick=review)
        }}
        if(!reviewAvailable) item {WdSectionFooter(stringResource(R.string.support_review_unavailable))}
        item {WdSectionHeader(stringResource(R.string.redesign_settings_feedback))}
        item {WdCard(card) {
            WdNavRow(stringResource(R.string.support_send_feedback),icon=Icons.Outlined.MailOutline,onClick=feedback)
            WdRow(divider=false,onClick={copyEmail();copied=true}) {
                Icon(Icons.Outlined.ContentCopy,null,Modifier.size(20.dp),tint=wd.textMuted)
                Text(stringResource(R.string.support_copy_email),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
            }
        }}
        item {WdSectionFooter(stringResource(R.string.support_feedback_description))}
        if(copied) item {SettingsNotice(card.padding(top=12.dp),icon=Icons.Outlined.CheckCircle) {Text(stringResource(R.string.support_email_copied),modifier=Modifier.testTag("support-email-copied"))}}
        if(error) item {SettingsNotice(card.padding(top=12.dp),warning=true) {Text(stringResource(R.string.support_no_handler))}}
        item {WdSectionHeader(stringResource(R.string.redesign_settings_links))}
        item {WdCard(card) {
            WdNavRow(stringResource(R.string.support_community),icon=Icons.Outlined.Forum,onClick={link(SupportLink.community)})
            WdNavRow(stringResource(R.string.support_website),icon=Icons.Outlined.Language,onClick={link(SupportLink.website)})
            WdNavRow(stringResource(R.string.support_privacy),icon=Icons.Outlined.PrivacyTip,onClick={link(SupportLink.privacy)})
            WdNavRow(stringResource(R.string.support_terms),icon=Icons.Outlined.Gavel,divider=false,onClick={link(SupportLink.terms)})
        }}
    }
}

@Composable internal fun ActivityLogsScreen(entries:List<ActivityLogEntry>,info:SupportInfo,dismiss:()->Unit,clear:()->Unit,copy:(String)->Unit) {
    BackHandler(onBack=dismiss)
    var clearing by remember {mutableStateOf(false)}
    var copied by remember {mutableStateOf(false)}
    val card=Modifier.padding(horizontal=16.dp)
    LazyColumn(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("activity-logs-screen"),contentPadding=PaddingValues(bottom=32.dp)) {
        item {SettingsBackHeader(stringResource(R.string.support_logs_title),stringResource(R.string.support_back),dismiss)}
        item {Text(stringResource(R.string.support_logs_privacy),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
        item {Row(card.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            WdButton(stringResource(R.string.support_copy_logs),{copy(info.reportHeader+"\n\n"+entries.joinToString("\n") {it.display});copied=true},Modifier.weight(1f),enabled=entries.isNotEmpty(),icon=Icons.Outlined.ContentCopy)
            WdButton(stringResource(R.string.support_clear_logs),{clearing=true},Modifier.weight(1f),emphasis=WdEmphasis.destructive,enabled=entries.isNotEmpty(),icon=Icons.Outlined.DeleteOutline)
        }}
        if(copied) item {SettingsNotice(card.padding(top=12.dp),icon=Icons.Outlined.CheckCircle) {Text(stringResource(R.string.support_logs_copied))}}
        item {WdSectionHeader(stringResource(R.string.support_logs_title))}
        if(entries.isEmpty()) item {WdCard(card) {WdEmptyState(stringResource(R.string.support_logs_empty),null,icon=Icons.AutoMirrored.Outlined.ReceiptLong)}}
        val ordered=entries.asReversed()
        itemsIndexed(ordered,key={_,it->it.id}) {index,entry->
            val error=entry.event.level=="ERROR"
            SelectionContainer(card.settingsSegment(index==0,index==ordered.lastIndex)) {
                Text(entry.display,Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),style=WdType.caption2,color=if(error) wd.inkRust else wd.textPrimary)
            }
        }
    }
    if(clearing) WorkspaceAlertDialog(onDismissRequest={clearing=false},title={Text(stringResource(R.string.support_clear_logs_question))},text={Text(stringResource(R.string.support_clear_logs_description))},confirmButton={TextButton(onClick={clear();clearing=false;copied=false},colors=ButtonDefaults.textButtonColors(contentColor=wd.inkRust)) {Text(stringResource(R.string.support_clear_logs))}},dismissButton={TextButton(onClick={clearing=false}) {Text(stringResource(R.string.support_cancel))}})
}

@Composable internal fun AndroidReleaseNotes(dismiss:()->Unit) {
    BackHandler(onBack=dismiss)
    LazyColumn(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("android-release-notes"),contentPadding=PaddingValues(bottom=32.dp)) {
        item {SettingsBackHeader(stringResource(R.string.support_release_notes),stringResource(R.string.support_back),dismiss)}
        item {WdCard(Modifier.padding(horizontal=16.dp)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.support_preview_title),style=WdType.title3,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
            Text(stringResource(R.string.support_preview_notice),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
            listOf(R.string.support_preview_journal,R.string.support_preview_portability,R.string.support_preview_automation).forEach {
                WdDivider()
                Text(stringResource(it),style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)
            }
        }}}
    }
}
