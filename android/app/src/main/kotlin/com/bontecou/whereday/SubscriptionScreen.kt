package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.billing.SubscriptionStatus
import com.bontecou.whereday.billing.SubscriptionSummary

/** Settings › Subscription: the plan, its status, and the only path to cancel, which goes through Before you go. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun SubscriptionScreen(summary:SubscriptionSummary,trialAvailable:Boolean,manageUri:String,back:()->Unit,seePlans:()->Unit,
    openManagement:(()->Unit)?=null) {
    val uriHandler=LocalUriHandler.current
    var leaving by rememberSaveable {mutableStateOf(false)}
    // Back on the page returns to Settings; the sheet handles its own dismissal first.
    BackHandler(enabled=!leaving,onBack=back)
    fun openPlay() {if(openManagement!=null) openManagement() else runCatching {uriHandler.openUri(manageUri)}}
    val card=Modifier.padding(horizontal=16.dp)
    LazyColumn(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("subscription-screen"),contentPadding=PaddingValues(bottom=32.dp)) {
        item {SettingsBackHeader(stringResource(R.string.subscription_title),stringResource(R.string.subscription_back),back,backTag="subscription-back")}
        if(summary.status==SubscriptionStatus.lapsed) {
            item {Box(card.padding(top=8.dp)) {SubscriptionEndedCard()}}
            item {WdSectionHeader(stringResource(R.string.subscription_ended_works))}
            item {SubscriptionList(card,Icons.Outlined.CheckCircle,wd.success,listOf(R.string.subscription_ended_work_browse,R.string.subscription_ended_work_import,R.string.subscription_ended_work_preview))}
            item {WdSectionHeader(stringResource(R.string.subscription_ended_needs))}
            item {SubscriptionList(card,Icons.Outlined.Lock,wd.textMuted,listOf(R.string.retention_stop_recording,R.string.retention_stop_export,R.string.retention_stop_automatic))}
            item {Column(card.padding(top=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                WdButton(stringResource(R.string.subscription_ended_see_plans),seePlans,Modifier.testTag("subscription-see-plans"),emphasis=WdEmphasis.primary)
                Text(stringResource(SubscriptionText.endedFootnote(trialAvailable)),Modifier.padding(horizontal=4.dp),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
            }}
            return@LazyColumn
        }
        val text=SubscriptionText.page(summary) ?: return@LazyColumn
        item {Box(card.padding(top=8.dp)) {WdInkCard {Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(stringResource(text.kicker).uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.12.em),color=wd.onInkCardMuted)
            Text(stringResource(text.title),Modifier.testTag("subscription-plan-title"),style=WdType.title2.copy(fontWeight=FontWeight.Normal),color=wd.onInkCard)
            Text(stringResource(text.body),style=MaterialTheme.typography.bodyMedium,color=wd.onInkCardMuted)
        }}}}
        item {WdSectionHeader(stringResource(R.string.subscription_details))}
        item {WdCard(card) {
            SubscriptionDetail(R.string.subscription_detail_plan,stringResource(text.planValue))
            SubscriptionDetail(R.string.subscription_detail_status,stringResource(text.statusValue))
            if(text.showsRenewal) SubscriptionDetail(R.string.subscription_detail_renewal,stringResource(R.string.subscription_value_in_play))
            SubscriptionDetail(R.string.subscription_detail_billed,stringResource(R.string.subscription_value_google_play),divider=false)
        }}
        item {WdSectionHeader(stringResource(R.string.subscription_included))}
        item {Text(stringResource(R.string.subscription_included_body),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)}
        if(summary.subscriptionAlsoRenewing) item {SettingsNotice(card.padding(top=20.dp),warning=true) {Text(stringResource(R.string.subscription_lifetime_also_renewing))}}
        if(summary.managesInPlay) item {Column(card.padding(top=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(summary.offersCancel) {
                WdButton(stringResource(R.string.subscription_manage_or_cancel),{leaving=true},Modifier.testTag("subscription-manage-or-cancel"),emphasis=WdEmphasis.primary)
                WdButton(stringResource(R.string.subscription_change_plan),::openPlay,Modifier.testTag("subscription-change-plan"),trailingIcon=Icons.AutoMirrored.Outlined.OpenInNew)
            } else WdButton(stringResource(R.string.subscription_manage_play),::openPlay,Modifier.testTag("subscription-manage-play"),emphasis=WdEmphasis.primary,
                trailingIcon=Icons.AutoMirrored.Outlined.OpenInNew)
            text.footnote?.let {Text(stringResource(it),Modifier.padding(horizontal=4.dp),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)}
        }}
    }
    if(leaving) BeforeYouGoSheet({leaving=false}) {leaving=false;openPlay()}
}

@Composable private fun SubscriptionEndedCard() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).border(1.dp,wd.outline,RoundedCornerShape(28.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_plan_kicker_none).uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.12.em),color=wd.textMuted)
        Text(stringResource(R.string.subscription_ended_title),Modifier.testTag("subscription-ended-title"),style=WdType.title2.copy(fontWeight=FontWeight.Normal),color=wd.textPrimary)
        Text(stringResource(R.string.subscription_ended_body),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
    }
}

@Composable private fun SubscriptionDetail(label:Int,value:String,divider:Boolean=true) {
    WdRow(divider=divider) {
        Text(stringResource(label),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
        Text(value,Modifier.weight(1f,fill=false),style=WdType.caption,color=wd.textMuted,textAlign=TextAlign.End)
    }
}

@Composable private fun SubscriptionList(modifier:Modifier,icon:ImageVector,tint:Color,lines:List<Int>) {
    WdCard(modifier) {Column(Modifier.padding(vertical=4.dp)) {lines.forEach {SubscriptionLine(icon,tint,stringResource(it),Modifier.padding(horizontal=20.dp,vertical=8.dp))}}}
}

@Composable private fun SubscriptionLine(icon:ImageVector,tint:Color,text:String,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
        Icon(icon,null,Modifier.size(20.dp),tint=tint)
        Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp,lineHeight=20.sp),color=wd.textPrimary)
    }
}

/**
 * Before you go: a factual summary between "Manage or cancel" and Google Play. Both actions are the same size, there is no
 * timer or required choice, and Back or the scrim keeps the plan. "Continue to cancel" opens Play's subscription page in one tap.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun BeforeYouGoSheet(keep:()->Unit,continueToCancel:()->Unit) {
    // Opens fully expanded so both actions are on screen without dragging or scrolling.
    WorkspaceModalBottomSheet(keep,fullHeight=true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).semantics {testTagsAsResourceId=true}.testTag("before-you-go")
            .padding(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.retention_title),Modifier.semantics {heading()},style=WdType.title.copy(fontWeight=FontWeight.Normal),color=wd.textPrimary)
            Text(stringResource(R.string.retention_lead),style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp,lineHeight=21.sp),color=wd.textMuted)
            BeforeYouGoGroup(R.string.retention_stops,Icons.Outlined.RemoveCircleOutline,wd.inkRust,listOf(R.string.retention_stop_recording,R.string.retention_stop_export,R.string.retention_stop_automatic))
            BeforeYouGoGroup(R.string.retention_keeps,Icons.Outlined.CheckCircle,wd.success,listOf(R.string.retention_keep_journal,R.string.retention_keep_browse,R.string.retention_keep_free))
            // Equal weight: two 56dp pills side by side, stacked at large text so neither label is cut.
            val stacked=LocalDensity.current.fontScale>=1.3f
            val keepButton:@Composable (Modifier)->Unit={SheetButton(stringResource(R.string.retention_keep_plan),keep,it.testTag("before-you-go-keep"),filled=false)}
            val cancelButton:@Composable (Modifier)->Unit={SheetButton(stringResource(R.string.retention_continue),continueToCancel,it.testTag("before-you-go-continue"),filled=true,
                trailing=Icons.AutoMirrored.Outlined.OpenInNew)}
            if(stacked) Column(Modifier.padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {keepButton(Modifier.fillMaxWidth());cancelButton(Modifier.fillMaxWidth())}
            else Row(Modifier.padding(top=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {keepButton(Modifier.weight(1f));cancelButton(Modifier.weight(1f))}
            Text(stringResource(R.string.retention_footnote),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
        }
    }
}

@Composable private fun BeforeYouGoGroup(title:Int,icon:ImageVector,tint:Color,lines:List<Int>) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        val text=stringResource(title)
        val cased=eyebrowUsesCase(text,rtl=LocalLayoutDirection.current==LayoutDirection.Rtl)
        Text(if(cased) text.uppercase() else text,Modifier.semantics {heading()},
            style=if(cased) WdType.eyebrow.copy(fontWeight=FontWeight.Medium,letterSpacing=0.14.em)
                else WdType.eyebrow.copy(fontFamily=FontFamily.Default,fontWeight=FontWeight.Medium,letterSpacing=0.em),color=wd.textMuted)
        lines.forEach {SubscriptionLine(icon,tint,stringResource(it))}
    }
}

/**
 * Uppercase plus tracking reads as an eyebrow only in scripts with letter case. Arabic, Devanagari, Bangla
 * and CJK have no case, and tracking pulls joined or dense glyphs apart, so those headings stay as written. The mono
 * eyebrow font has no glyphs for them either: only its wide space would apply, so they use the default family.
 */
internal fun eyebrowUsesCase(text:String,rtl:Boolean):Boolean {
    if(rtl) return false
    val firstLetter=text.codePoints().filter {Character.isLetter(it)}.findFirst()
    if(!firstLetter.isPresent) return false
    return Character.UnicodeScript.of(firstLetter.asInt) in setOf(Character.UnicodeScript.LATIN,Character.UnicodeScript.CYRILLIC,Character.UnicodeScript.GREEK)
}

/** The sheet's pair: a tonal pill and a filled ink pill of identical size. */
@Composable private fun SheetButton(text:String,onClick:()->Unit,modifier:Modifier,filled:Boolean,trailing:ImageVector?=null) {
    val background=if(filled) wd.accent else wd.secondaryContainer
    val content=if(filled) wd.surface else wd.onSecondaryContainer
    Row(modifier.heightIn(min=56.dp).clip(CircleShape).background(background).clickable(role=Role.Button,onClick=onClick)
        .padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        Text(text,style=MaterialTheme.typography.labelLarge.copy(fontSize=16.sp),color=content,textAlign=TextAlign.Center)
        if(trailing!=null) {Spacer(Modifier.width(6.dp));Icon(trailing,null,Modifier.size(16.dp),tint=content)}
    }
}
