@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.whereday.billing.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** A Google Play review quoted with the reviewer's permission. */
internal data class PaywallReview(val quote:String,val reviewer:String,val rating:Int)

/** Reviews shown on the first page. Empty until real, permitted quotes exist; the card is hidden while empty. */
internal val paywallReviews:List<PaywallReview> = emptyList()

/** Where the paywall opened: export entry points lead with formats; [gate] follows onboarding and cannot be dismissed. */
internal enum class PaywallEntry {tracking,export,gate}

private enum class PaywallPage {promise,freeWeek,plans}

/** The billing notices that explain a failure or an unavailable store, as opposed to progress. */
internal val BillingNotice.isPaywallWarning:Boolean get()=this in setOf(BillingNotice.unavailable,BillingNotice.verificationFailed,
    BillingNotice.storeUnavailable,BillingNotice.notFound,BillingNotice.notEligible,BillingNotice.canceled)

/** The free trial the carousel can promise: only one Play actually offers this account. */
internal val BillingState.trialProduct:StoreProduct? get()=(product(StorePlan.yearly)?.takeIf {it.freeTrialDays>0}) ?: product(StorePlan.weekly)?.takeIf {it.freeTrialDays>0}

/** The full-screen plan carousel opened from tracking and export entry points. */
@Composable internal fun PaywallDialog(purchase:BillingState,checkout:Boolean,buy:(StorePlan)->Unit,restore:()->Unit,dismiss:()->Unit,
    entry:PaywallEntry=PaywallEntry.tracking,reviews:List<PaywallReview> = paywallReviews) {
    Dialog(dismiss,DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Paywall(purchase,checkout,buy,restore,dismiss,entry=entry,reviews=reviews)
    }
}

@Composable internal fun Paywall(purchase:BillingState,checkout:Boolean,buy:(StorePlan)->Unit,restore:()->Unit,dismiss:()->Unit,
    modifier:Modifier=Modifier,entry:PaywallEntry=PaywallEntry.tracking,reviews:List<PaywallReview> = paywallReviews,initialPage:Int=0) {
    val active=purchase.access==AccessState.active
    LaunchedEffect(active) {if(active) dismiss()}
    val dismissible=entry!=PaywallEntry.gate
    if(!dismissible) BackHandler {}
    val trial=purchase.trialProduct
    // The free-week page appears only while Play offers this account a trial, so the carousel never promises one it cannot start.
    val pages=listOfNotNull(PaywallPage.promise,PaywallPage.freeWeek.takeIf {trial!=null},PaywallPage.plans)
    val pager=rememberPagerState(initialPage.coerceAtMost(pages.lastIndex)) {pages.size}
    var selectedName by rememberSaveable {mutableStateOf<String?>(null)}
    val available=purchase.products.map {it.plan}
    val selected=selectedName?.let {name->StorePlan.entries.firstOrNull {it.name==name}}?.takeIf {it in available}
        ?: listOf(StorePlan.yearly,StorePlan.weekly,StorePlan.lifetime).firstOrNull {it in available}
    val largeText=LocalDensity.current.fontScale>=1.5f
    Column(modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("paywall")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=8.dp,end=20.dp,top=16.dp).heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(dismissible) WdIconButton(Icons.Outlined.Close,stringResource(R.string.paywall_close),dismiss,Modifier.testTag("paywall-close"))
            Text(stringResource(R.string.paywall_eyebrow).uppercase(),style=WdType.eyebrow.copy(fontWeight=FontWeight.Normal,letterSpacing=0.145.em),color=wd.textMuted)
        }
        HorizontalPager(pager,Modifier.weight(1f).fillMaxWidth().testTag("paywall-pager"),verticalAlignment=Alignment.Top) {index->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(top=20.dp,bottom=24.dp)
                .testTag("paywall-page-${index+1}"),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                when(pages[index]) {
                    PaywallPage.promise->if(entry==PaywallEntry.export) PaywallExportPage() else PaywallPromisePage(reviews)
                    PaywallPage.freeWeek->PaywallFreeWeekPage(trial!!)
                    PaywallPage.plans->PaywallPlansPage(purchase,selected) {selectedName=it.name}
                }
                // At large text sizes the disclosure scrolls with each page so the pinned footer keeps room for the actions.
                if(largeText) PaywallCaption(purchase,purchase.product(selected ?: StorePlan.yearly),Modifier.fillMaxWidth())
            }
        }
        PaywallFooter(pager,pages,purchase,checkout,selected,buy,restore,caption=!largeText)
    }
}

// MARK: Pages

/** Text with one `*emphasis*` span set in italics, as the iOS titles are. */
@Composable private fun markedUp(@StringRes text:Int):AnnotatedString {
    val parts=stringResource(text).split('*')
    return buildAnnotatedString {parts.forEachIndexed {index,part->if(index%2==1) withStyle(SpanStyle(fontStyle=FontStyle.Italic)) {append(part)} else append(part)}}
}

@Composable private fun PaywallTitle(text:AnnotatedString,size:Int=34) {
    Text(text,Modifier.semantics {heading()},style=TextStyle(fontFamily=WdFonts.serif,fontWeight=FontWeight.Normal,fontSize=size.sp,lineHeight=(size*1.1).sp,
        letterSpacing=(-0.4).sp),color=wd.textPrimary)
}

@Composable private fun PaywallTitle(@StringRes lead:Int,@StringRes emphasis:Int,size:Int=34) {
    val leadText=stringResource(lead);val emphasisText=stringResource(emphasis)
    PaywallTitle(buildAnnotatedString {append(leadText);append("\n");withStyle(SpanStyle(fontStyle=FontStyle.Italic)) {append(emphasisText)}},size)
}

@Composable private fun ColumnScope.PaywallPromisePage(reviews:List<PaywallReview>) {
    reviews.firstOrNull()?.let {PaywallReviewCard(it)}
    PaywallTitle(R.string.paywall_promise_title_lead,R.string.paywall_promise_title_emphasis,size=36)
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        PaywallBenefit(Icons.Outlined.Route,R.string.paywall_benefit_daily_title,R.string.paywall_benefit_daily_body)
        PaywallBenefit(Icons.Outlined.CalendarMonth,R.string.paywall_benefit_revisit_title,R.string.paywall_benefit_revisit_body)
        PaywallBenefit(Icons.Outlined.Lock,R.string.paywall_benefit_private_title,R.string.paywall_benefit_private_body)
    }
}

private data class FormatChip(val name:String,val ink:Int)
private val formatChips=listOf(FormatChip("GPX",0),FormatChip("GeoJSON",1),FormatChip("KML",0),FormatChip("CSV",3),
    FormatChip("JSON",3),FormatChip("Markdown",0),FormatChip("OwnTracks",2),FormatChip("Overland",2))

@Composable private fun formatInk(index:Int):Pair<Color,Color> {
    val border=wd.placeInks[index]
    val text=when {index==0->wd.textPrimary;index==1->wd.inkRust;wd.dark->border;else->lerp(border,Color.Black,0.33f)}
    return border to text
}

@Composable private fun ColumnScope.PaywallExportPage() {
    val columns=if(LocalDensity.current.fontScale>=1.5f) 2 else 4
    val summary=stringResource(R.string.paywall_export_formats,formatChips.joinToString {it.name})
    WdCard(Modifier.testTag("paywall-formats").clearAndSetSemantics {contentDescription=summary}) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            formatChips.chunked(columns).forEach {row->
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach {chip->
                        val (border,text)=formatInk(chip.ink)
                        val shape=RoundedCornerShape(8.dp)
                        Box(Modifier.weight(1f).heightIn(min=36.dp).clip(shape).border(1.dp,border,shape).padding(horizontal=2.dp,vertical=6.dp),
                            contentAlignment=Alignment.Center) {
                            Text(chip.name,style=WdType.caption2.copy(fontWeight=FontWeight.Medium),color=text,maxLines=1,softWrap=false)
                        }
                    }
                }
            }
        }
    }
    PaywallTitle(R.string.paywall_export_title_lead,R.string.paywall_export_title_emphasis,size=36)
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        PaywallBenefit(Icons.Outlined.Download,R.string.paywall_benefit_zip_title,R.string.paywall_benefit_zip_body)
        PaywallBenefit(Icons.Outlined.FolderOpen,R.string.paywall_benefit_automatic_title,R.string.paywall_benefit_automatic_body)
        PaywallBenefit(Icons.Outlined.Link,R.string.paywall_benefit_webhooks_title,R.string.paywall_benefit_webhooks_body)
    }
}

@Composable private fun PaywallBenefit(icon:ImageVector,@StringRes title:Int,@StringRes body:Int) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {},horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        // Board: a 40dp secondary-container disc per perk.
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(wd.secondaryContainer),contentAlignment=Alignment.Center) {
            Icon(icon,null,Modifier.size(20.dp),tint=wd.onSecondaryContainer)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
            Text(stringResource(body),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        }
    }
}

@Composable private fun PaywallReviewCard(review:PaywallReview) {
    val rating=review.rating.coerceIn(0,5)
    WdCard(Modifier.testTag("paywall-review")) {
        Column(Modifier.padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            val stars=pluralStringResource(R.plurals.paywall_review_rating,rating,rating)
            Row(Modifier.clearAndSetSemantics {contentDescription=stars},horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                repeat(rating) {Icon(Icons.Filled.Star,null,Modifier.size(13.dp),tint=wd.placeInks[2])}
            }
            Text("“${review.quote}”",style=WdType.serifBody.copy(fontStyle=FontStyle.Italic,lineHeight=22.sp),color=wd.textPrimary)
            Text(stringResource(R.string.paywall_review_source,review.reviewer).uppercase(),style=WdType.caption2.copy(letterSpacing=0.05.em),color=wd.textMuted)
        }
    }
}

@Composable private fun ColumnScope.PaywallFreeWeekPage(trial:StoreProduct) {
    PaywallTitle(markedUp(R.string.paywall_trial_title))
    val days=trial.freeTrialDays
    val ends=stringResource(if(trial.plan==StorePlan.yearly) R.string.paywall_trial_begins_yearly else R.string.paywall_trial_begins_weekly,trial.formattedPrice)
    Column(Modifier.testTag("paywall-trial-steps")) {
        // Board inks: rust, sage, ochre.
        PaywallTrialStep(Icons.Outlined.LockOpen,stringResource(R.string.paywall_trial_today),stringResource(R.string.paywall_trial_today_body),wd.inkRust,last=false)
        PaywallTrialStep(Icons.Outlined.NotificationsActive,stringResource(R.string.paywall_trial_reminder,days-2),stringResource(R.string.paywall_trial_reminder_body),if(wd.dark) wd.placeInks[3] else Color(0xFF3F5C3C),last=false)
        PaywallTrialStep(Icons.Outlined.Star,stringResource(R.string.paywall_trial_begins,days),ends,if(wd.dark) wd.placeInks[2] else Color(0xFF7A5A1C),last=true)
    }
}

@Composable private fun PaywallTrialStep(icon:ImageVector,title:String,body:String,ink:Color,last:Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants=true) {},horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(ink),contentAlignment=Alignment.Center) {
                Icon(icon,null,Modifier.size(20.dp),tint=wd.surface)
            }
            if(!last) Box(Modifier.width(2.dp).weight(1f).heightIn(min=24.dp).background(wd.border))
        }
        Column(Modifier.weight(1f).padding(top=8.dp,bottom=if(last) 0.dp else 28.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
            Text(body,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        }
    }
}

/** A yearly price divided into months, in the store's own currency. */
@Composable internal fun monthlyEquivalent(product:StoreProduct):String? {
    if(product.priceMicros<=0 || product.currencyCode.isEmpty()) return null
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return runCatching {NumberFormat.getCurrencyInstance(locale).apply {currency=Currency.getInstance(product.currencyCode)}.format(product.priceMicros/12.0/1_000_000)}.getOrNull()
}

@Composable private fun planName(plan:StorePlan)=stringResource(when(plan) {StorePlan.weekly->R.string.paywall_plan_weekly;StorePlan.yearly->R.string.paywall_plan_yearly;StorePlan.lifetime->R.string.paywall_plan_lifetime})

@Composable private fun planDetail(product:StoreProduct)=when(product.plan) {
    StorePlan.weekly->stringResource(R.string.paywall_plan_weekly_detail)
    StorePlan.yearly->monthlyEquivalent(product)?.let {stringResource(R.string.paywall_plan_yearly_detail,it)} ?: stringResource(R.string.paywall_plan_yearly_renews)
    StorePlan.lifetime->stringResource(R.string.paywall_plan_lifetime_detail)
}

private val includedRows=listOf(R.string.paywall_included_recording,R.string.paywall_included_formats,R.string.paywall_included_automatic,R.string.paywall_included_webhooks)

@Composable private fun ColumnScope.PaywallPlansPage(purchase:BillingState,selected:StorePlan?,select:(StorePlan)->Unit) {
    PaywallTitle(markedUp(R.string.paywall_plans_title))
    val products=purchase.products
    when {
        products.isNotEmpty()->Column(Modifier.selectableGroup(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            products.forEach {product->PaywallPlanCard(product,product.plan==selected) {select(product.plan)}}
        }
        purchase.access==AccessState.checking || purchase.busy->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.dp)
            Text(stringResource(R.string.paywall_loading),style=WdType.caption,color=wd.textMuted)
        }
        else->Text(stringResource(R.string.paywall_buy_unavailable),Modifier.testTag("paywall-no-plans"),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
    }
    WdCard {
        Text(stringResource(R.string.paywall_includes).uppercase(),Modifier.padding(start=16.dp,end=16.dp,top=12.dp,bottom=4.dp),
            style=WdType.caption2.copy(letterSpacing=0.13.em),color=wd.textMuted)
        includedRows.forEachIndexed {index,label->
            Row(Modifier.fillMaxWidth().heightIn(min=44.dp).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Check,null,Modifier.size(16.dp),tint=wd.placeInks[3])
                Text(stringResource(label),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),color=wd.textPrimary)
            }
            if(index<includedRows.lastIndex) WdDivider()
        }
    }
    when(purchase.access) {
        AccessState.pending->PaywallStatus(stringResource(R.string.paywall_pending),wd.accent,Icons.Outlined.Schedule,Modifier.testTag("paywall-pending"))
        AccessState.active->PaywallStatus(stringResource(R.string.paywall_verified),wd.success,Icons.Outlined.CheckCircle,Modifier.testTag("paywall-verified"),strong=true)
        else->Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.paywall_records_note),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
            Text(stringResource(R.string.paywall_cancel_note),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
        }
    }
}

@Composable private fun PaywallPlanCard(product:StoreProduct,selected:Boolean,onSelect:()->Unit) {
    val shape=RoundedCornerShape(16.dp)
    val name=planName(product.plan);val detail=planDetail(product)
    val ring=wd.inkRust
    val badge=when {
        product.freeTrialDays>0->stringResource(R.string.paywall_badge_trial,product.freeTrialDays)
        product.plan==StorePlan.lifetime->stringResource(R.string.paywall_badge_once)
        else->null
    }
    Row(Modifier.fillMaxWidth().heightIn(min=68.dp).clip(shape).background(if(selected) ring.copy(alpha=if(wd.dark) 0.12f else 0.08f).compositeOverWd(wd.surface) else wd.containerLow)
        .border(2.dp,if(selected) ring else Color.Transparent,shape)
        .selectable(selected,role=Role.RadioButton,onClick=onSelect).padding(horizontal=16.dp,vertical=12.dp)
        .testTag("paywall-plan-${product.plan.name}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).border(2.dp,if(selected) ring else wd.outline,RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {
            if(selected) Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(ring))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                Text(name,style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                if(badge!=null) Text(badge,Modifier.clip(RoundedCornerShape(8.dp)).background(if(product.plan==StorePlan.lifetime) wd.primaryContainer else wd.secondaryContainer)
                    .padding(horizontal=8.dp,vertical=2.dp),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.04.em,fontWeight=FontWeight.Medium),
                    color=if(product.plan==StorePlan.lifetime) wd.onPrimaryContainer else wd.onSecondaryContainer)
            }
            Text(detail,style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
        }
        Text(product.formattedPrice,Modifier.testTag("paywall-price-${product.plan.name}"),style=TextStyle(fontFamily=WdFonts.mono,fontWeight=FontWeight.Medium,fontSize=17.sp),color=wd.textPrimary)
    }
}

@Composable private fun PaywallStatus(text:String,ink:Color,icon:ImageVector,modifier:Modifier=Modifier,strong:Boolean=false) {
    // Board: pending sits on the primary container, verified on a sage container, both without outlines.
    val shape=RoundedCornerShape(16.dp)
    val container=if(strong) (if(wd.dark) Color(0xFF2E4230) else Color(0xFFD9E6D4)) else wd.primaryContainer
    val content=if(strong) (if(wd.dark) Color(0xFFD5EBD0) else Color(0xFF22351F)) else wd.onPrimaryContainer
    Row(modifier.fillMaxWidth().clip(shape).background(container)
        .padding(horizontal=16.dp,vertical=12.dp).semantics(mergeDescendants=true) {liveRegion=LiveRegionMode.Polite},horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,Modifier.size(18.dp),tint=content)
        Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=if(strong) FontWeight.SemiBold else FontWeight.Normal),color=content)
    }
}

// MARK: Footer

@Composable private fun PaywallFooter(pager:PagerState,pages:List<PaywallPage>,purchase:BillingState,checkout:Boolean,selected:StorePlan?,
    buy:(StorePlan)->Unit,restore:()->Unit,caption:Boolean) {
    val scope=rememberCoroutineScope()
    val uriHandler=LocalUriHandler.current
    var linkError by remember {mutableStateOf(false)}
    fun open(link:SupportLink) {try {uriHandler.openUri(link.uri);linkError=false} catch(_:Exception) {linkError=true}}
    val product=selected?.let {purchase.product(it)}
    Column(Modifier.fillMaxWidth().background(wd.surface).navigationBarsPadding().padding(start=16.dp,end=16.dp,top=12.dp,bottom=8.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.testTag("paywall-dots"),verticalAlignment=Alignment.CenterVertically) {
            repeat(pages.size) {page->
                val current=pager.currentPage==page
                val label=stringResource(R.string.paywall_page,page+1,pages.size)
                Box(Modifier.size(32.dp).clickable(role=Role.Tab) {scope.launch {pager.animateScrollToPage(page)}}
                    .semantics {contentDescription=label;this.selected=current}.testTag("paywall-dot-${page+1}"),
                    contentAlignment=Alignment.Center) {
                    Box(Modifier.size(width=if(current) 18.dp else 6.dp,height=6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if(current) wd.inkRust else wd.containerHighest))
                }
            }
        }
        if(caption) PaywallCaption(purchase,product)
        if(linkError) Text(stringResource(R.string.support_no_handler),Modifier.testTag("paywall-link-error"),style=MaterialTheme.typography.bodySmall,
            color=wd.inkRust,textAlign=TextAlign.Center)
        val busy=purchase.busy || checkout
        when {
            purchase.access==AccessState.active->{}
            purchase.access==AccessState.pending->WdButton(stringResource(R.string.paywall_buy_pending),{},Modifier.heightIn(min=56.dp).testTag("paywall-buy"),
                emphasis=WdEmphasis.primary,enabled=false,icon=Icons.Outlined.Schedule)
            product!=null->WdButton(when {
                product.freeTrialDays>0->stringResource(R.string.paywall_cta_trial,product.freeTrialDays)
                product.isSubscription->stringResource(R.string.paywall_cta_subscribe)
                else->stringResource(R.string.paywall_cta_lifetime)
            },{buy(product.plan)},Modifier.heightIn(min=56.dp).testTag("paywall-buy"),emphasis=WdEmphasis.primary,busy=busy)
            purchase.access==AccessState.checking->WdButton(stringResource(R.string.export_access_checking),{},Modifier.heightIn(min=56.dp).testTag("paywall-buy"),
                emphasis=WdEmphasis.primary,enabled=false,busy=true)
            else->WdButton(stringResource(R.string.paywall_buy_unavailable),{},Modifier.heightIn(min=56.dp).testTag("paywall-buy"),emphasis=WdEmphasis.primary,
                enabled=false,icon=Icons.Outlined.Lock)
        }
        FlowRow(horizontalArrangement=Arrangement.Center) {
            PaywallLink(stringResource(R.string.paywall_restore),restore,Modifier.testTag("paywall-restore"),enabled=!busy)
            PaywallLink(stringResource(R.string.paywall_privacy),{open(SupportLink.privacy)},Modifier.testTag("paywall-privacy"))
            PaywallLink(stringResource(R.string.paywall_terms),{open(SupportLink.terms)},Modifier.testTag("paywall-terms"))
        }
    }
}

/** The selected plan's renewal disclosure, replaced by the current billing warning (canceled, unavailable, failed) when there is one. */
@Composable private fun PaywallCaption(purchase:BillingState,product:StoreProduct?,modifier:Modifier=Modifier) {
    val notice=purchase.notice?.takeIf {it.isPaywallWarning}
    if(notice!=null) Text(billingNoticeText(notice),modifier.testTag("paywall-notice").semantics {liveRegion=LiveRegionMode.Polite},
        style=MaterialTheme.typography.bodySmall,color=wd.inkRust,textAlign=TextAlign.Center)
    else if(product!=null) Text(paywallDisclosure(product),modifier.testTag("paywall-disclosure"),style=MaterialTheme.typography.bodySmall,color=wd.textMuted,textAlign=TextAlign.Center)
}

@Composable internal fun paywallDisclosure(product:StoreProduct):String {
    val price=product.formattedPrice;val days=product.freeTrialDays
    return when(product.plan) {
        StorePlan.weekly->if(days>0) stringResource(R.string.paywall_disclosure_weekly_trial,days,price) else stringResource(R.string.paywall_disclosure_weekly,price)
        StorePlan.yearly->if(days>0) stringResource(R.string.paywall_disclosure_yearly_trial,days,price) else stringResource(R.string.paywall_disclosure_yearly,price)
        StorePlan.lifetime->stringResource(R.string.paywall_disclosure_lifetime,price)
    }
}

@Composable private fun PaywallLink(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Box(modifier.heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=12.dp),
        contentAlignment=Alignment.Center) {
        Text(text,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium),color=if(enabled) wd.textPrimary else wd.textMuted)
    }
}

private fun Color.compositeOverWd(background:Color):Color {
    val a=alpha
    return Color(red*a+background.red*(1-a),green*a+background.green*(1-a),blue*a+background.blue*(1-a),1f)
}
