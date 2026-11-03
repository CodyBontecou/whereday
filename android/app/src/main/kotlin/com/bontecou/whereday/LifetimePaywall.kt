@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bontecou.whereday

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
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

/** A Google Play review quoted with the reviewer's permission. */
internal data class PaywallReview(val quote:String,val reviewer:String,val rating:Int)

/** Reviews shown on the "Recording stays free" page. Empty until real, permitted quotes exist; the card is hidden while empty. */
internal val paywallReviews:List<PaywallReview> = emptyList()

private const val PAYWALL_PAGES=3

/** The billing notices that explain a failure or an unavailable store, as opposed to progress. */
internal val BillingNotice.isPaywallWarning:Boolean get()=this in setOf(BillingNotice.unavailable,BillingNotice.verificationFailed,
    BillingNotice.storeUnavailable,BillingNotice.notFound,BillingNotice.notEligible,BillingNotice.canceled)

/** The full-screen three-page Lifetime carousel opened from the export access card. */
@Composable internal fun LifetimePaywallDialog(purchase:BillingState,checkout:Boolean,buy:()->Unit,restore:()->Unit,dismiss:()->Unit,
    reviews:List<PaywallReview> = paywallReviews) {
    Dialog(dismiss,DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        LifetimePaywall(purchase,checkout,buy,restore,dismiss,reviews=reviews)
    }
}

@Composable internal fun LifetimePaywall(purchase:BillingState,checkout:Boolean,buy:()->Unit,restore:()->Unit,dismiss:()->Unit,
    modifier:Modifier=Modifier,reviews:List<PaywallReview> = paywallReviews,initialPage:Int=0) {
    val active=purchase.access==AccessState.active
    LaunchedEffect(active) {if(active) dismiss()}
    val pager=rememberPagerState(initialPage) {PAYWALL_PAGES}
    val price=purchase.product?.formattedPrice
    val largeText=LocalDensity.current.fontScale>=1.5f
    Column(modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("paywall")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=16.dp,end=20.dp,top=12.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            WdCircleButton(Icons.Outlined.Close,stringResource(R.string.paywall_close),dismiss,Modifier.testTag("paywall-close"))
            Text(stringResource(R.string.paywall_eyebrow).uppercase(),style=WdType.eyebrow.copy(fontWeight=FontWeight.Normal,letterSpacing=0.145.em),color=wd.textMuted)
        }
        HorizontalPager(pager,Modifier.weight(1f).fillMaxWidth().testTag("paywall-pager"),verticalAlignment=Alignment.Top) {page->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(top=20.dp,bottom=24.dp)
                .testTag("paywall-page-${page+1}"),verticalArrangement=Arrangement.spacedBy(if(page==0) 20.dp else 18.dp)) {
                when(page) {
                    0->PaywallExportPage()
                    1->PaywallFreePage(reviews)
                    else->PaywallBuyPage(purchase,price)
                }
                // At large text sizes the caption scrolls with each page so the pinned footer keeps room for the actions.
                if(largeText) PaywallCaption(purchase,Modifier.fillMaxWidth())
            }
        }
        PaywallFooter(pager,purchase,checkout,price,buy,restore,caption=!largeText)
    }
}

// MARK: Pages

@Composable private fun PaywallTitle(@StringRes lead:Int,@StringRes emphasis:Int,lineBreak:Boolean,size:Int=34) {
    val leadText=stringResource(lead);val emphasisText=stringResource(emphasis)
    Text(buildAnnotatedString {
        append(leadText);append(if(lineBreak) "\n" else " ")
        withStyle(SpanStyle(fontStyle=FontStyle.Italic)) {append(emphasisText)}
    },Modifier.semantics {heading()},style=TextStyle(fontFamily=WdFonts.serif,fontWeight=FontWeight.Normal,fontSize=size.sp,lineHeight=(size*1.1).sp,
        letterSpacing=(-0.4).sp),color=wd.textPrimary)
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
    PaywallTitle(R.string.paywall_export_title_lead,R.string.paywall_export_title_emphasis,lineBreak=true,size=36)
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        PaywallBenefit(Icons.Outlined.Download,R.string.paywall_benefit_zip_title,R.string.paywall_benefit_zip_body)
        PaywallBenefit(Icons.Outlined.FolderOpen,R.string.paywall_benefit_automatic_title,R.string.paywall_benefit_automatic_body)
        PaywallBenefit(Icons.Outlined.Link,R.string.paywall_benefit_webhooks_title,R.string.paywall_benefit_webhooks_body)
    }
}

@Composable private fun PaywallBenefit(icon:ImageVector,@StringRes title:Int,@StringRes body:Int) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {},horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        val shape=RoundedCornerShape(10.dp)
        Box(Modifier.size(36.dp).clip(shape).background(wd.card).border(1.dp,wd.border,shape),contentAlignment=Alignment.Center) {
            Icon(icon,null,Modifier.size(18.dp),tint=wd.textPrimary)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
            Text(stringResource(body),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        }
    }
}

private data class FreeRow(@StringRes val label:Int,val free:Boolean)
private val freeRows=listOf(FreeRow(R.string.paywall_free_recording,true),FreeRow(R.string.paywall_free_map,true),
    FreeRow(R.string.paywall_free_import,true),FreeRow(R.string.paywall_free_preview,true),FreeRow(R.string.paywall_free_files,false),
    FreeRow(R.string.paywall_free_automatic,false),FreeRow(R.string.paywall_free_webhooks,false))

@Composable private fun ColumnScope.PaywallFreePage(reviews:List<PaywallReview>) {
    reviews.firstOrNull()?.let {PaywallReviewCard(it)}
    PaywallTitle(R.string.paywall_free_title_lead,R.string.paywall_free_title_emphasis,lineBreak=false)
    // At large text sizes the two mark columns move under each label so labels keep the full width.
    val stacked=LocalDensity.current.fontScale>=1.5f
    val freeWidth=64.dp;val lifetimeWidth=76.dp
    val included=stringResource(R.string.paywall_included);val notIncluded=stringResource(R.string.paywall_not_included)
    val freeColumn=stringResource(R.string.paywall_free_column).uppercase();val lifetimeColumn=stringResource(R.string.paywall_lifetime_column).uppercase()
    val header=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.12.em)
    @Composable fun FreeMark(free:Boolean) {
        if(free) Icon(Icons.Outlined.Check,null,Modifier.size(16.dp),tint=wd.placeInks[3])
        else Box(Modifier.size(width=10.dp,height=1.5.dp).background(wd.textMuted.copy(alpha=0.55f)))
    }
    @Composable fun LifetimeMark() {Icon(Icons.Outlined.Check,null,Modifier.size(16.dp),tint=wd.inkRust)}
    WdCard(Modifier.testTag("paywall-compare")) {
        if(!stacked) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp).clearAndSetSemantics {},verticalAlignment=Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(freeColumn,Modifier.width(freeWidth),style=header,color=wd.textMuted,textAlign=TextAlign.Center,maxLines=1)
                Text(lifetimeColumn,Modifier.width(lifetimeWidth),style=header,color=wd.inkRust,textAlign=TextAlign.Center,maxLines=1)
            }
            WdDivider()
        }
        freeRows.forEachIndexed {index,row->
            val label=stringResource(row.label)
            val summary=stringResource(R.string.paywall_row_summary,label,if(row.free) included else notIncluded,included)
            val labelStyle=MaterialTheme.typography.bodyMedium.copy(fontWeight=if(row.free) FontWeight.Normal else FontWeight.SemiBold)
            if(stacked) Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp).clearAndSetSemantics {contentDescription=summary},
                verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(label,style=labelStyle,color=wd.textPrimary)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {Text(freeColumn,style=header,color=wd.textMuted);FreeMark(row.free)}
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {Text(lifetimeColumn,style=header,color=wd.inkRust);LifetimeMark()}
                }
            }
            else Row(Modifier.fillMaxWidth().heightIn(min=44.dp).padding(horizontal=16.dp,vertical=8.dp).clearAndSetSemantics {contentDescription=summary},
                verticalAlignment=Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f),style=labelStyle,color=wd.textPrimary)
                Box(Modifier.width(freeWidth),contentAlignment=Alignment.Center) {FreeMark(row.free)}
                Box(Modifier.width(lifetimeWidth),contentAlignment=Alignment.Center) {LifetimeMark()}
            }
            if(index<freeRows.lastIndex) WdDivider(Modifier.padding(horizontal=16.dp))
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

private val includedRows=listOf(R.string.paywall_included_formats,R.string.paywall_included_automatic,R.string.paywall_included_webhooks,R.string.paywall_included_restore)

@Composable private fun ColumnScope.PaywallBuyPage(purchase:BillingState,price:String?) {
    PaywallTitle(R.string.paywall_buy_title_lead,R.string.paywall_buy_title_emphasis,lineBreak=true)
    val shape=RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(wd.inkCard).padding(start=18.dp,end=18.dp,top=18.dp,bottom=20.dp)
        .semantics(mergeDescendants=true) {}.testTag("paywall-plan"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.paywall_plan).uppercase(),style=WdType.caption2.copy(letterSpacing=0.13.em),color=wd.onInkCardMuted)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.Bottom) {
            // The store-formatted price only; an unconfigured store shows a dash rather than an invented figure.
            val unavailable=stringResource(R.string.paywall_buy_unavailable)
            Text(price ?: "—",Modifier.alignByBaseline().testTag("paywall-price").then(if(price==null) Modifier.semantics {contentDescription=unavailable} else Modifier),
                style=TextStyle(fontFamily=WdFonts.mono,fontWeight=FontWeight.Medium,fontSize=44.sp,lineHeight=50.sp,letterSpacing=(-1).sp),color=wd.onInkCard)
            if(price!=null) Text(stringResource(R.string.paywall_once),Modifier.alignByBaseline(),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),color=wd.onInkCardMuted)
        }
        Text(stringResource(R.string.paywall_plan_body),style=MaterialTheme.typography.bodyMedium,color=wd.onInkCardMuted)
    }
    WdCard {
        includedRows.forEachIndexed {index,label->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Check,null,Modifier.size(16.dp),tint=wd.placeInks[3])
                Text(stringResource(label),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),color=wd.textPrimary)
            }
            if(index<includedRows.lastIndex) WdDivider()
        }
    }
    when {
        purchase.access==AccessState.pending->PaywallStatus(stringResource(R.string.paywall_pending),wd.accent,Icons.Outlined.Schedule,Modifier.testTag("paywall-pending"))
        purchase.access==AccessState.active->PaywallStatus(stringResource(R.string.paywall_verified),wd.success,Icons.Outlined.CheckCircle,Modifier.testTag("paywall-verified"),strong=true)
        else->Text(stringResource(R.string.paywall_stays_free),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
    }
}

@Composable private fun PaywallStatus(text:String,ink:Color,icon:ImageVector,modifier:Modifier=Modifier,strong:Boolean=false) {
    val shape=RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(ink.copy(alpha=if(wd.dark) 0.12f else 0.06f)).border(1.dp,ink.copy(alpha=0.35f),shape)
        .padding(12.dp).semantics(mergeDescendants=true) {liveRegion=LiveRegionMode.Polite},horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,Modifier.size(18.dp),tint=ink)
        Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium.copy(fontSize=13.sp,fontWeight=if(strong) FontWeight.SemiBold else FontWeight.Normal),
            color=if(strong) ink else wd.textPrimary)
    }
}

// MARK: Footer

@Composable private fun PaywallFooter(pager:PagerState,purchase:BillingState,checkout:Boolean,price:String?,buy:()->Unit,restore:()->Unit,caption:Boolean) {
    val scope=rememberCoroutineScope()
    val uriHandler=LocalUriHandler.current
    var linkError by remember {mutableStateOf(false)}
    fun open(link:SupportLink) {try {uriHandler.openUri(link.uri);linkError=false} catch(_:Exception) {linkError=true}}
    Column(Modifier.fillMaxWidth().background(wd.surface).navigationBarsPadding().padding(start=16.dp,end=16.dp,top=12.dp,bottom=8.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.testTag("paywall-dots"),verticalAlignment=Alignment.CenterVertically) {
            repeat(PAYWALL_PAGES) {page->
                val selected=pager.currentPage==page
                val label=stringResource(R.string.paywall_page,page+1,PAYWALL_PAGES)
                Box(Modifier.size(width=if(selected) 30.dp else 18.dp,height=32.dp).clickable(role=Role.Tab) {scope.launch {pager.animateScrollToPage(page)}}
                    .semantics {contentDescription=label;this.selected=selected}.testTag("paywall-dot-${page+1}"),
                    contentAlignment=Alignment.Center) {
                    Box(Modifier.size(width=if(selected) 18.dp else 6.dp,height=6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if(selected) wd.accent else wd.border))
                }
            }
        }
        if(caption) PaywallCaption(purchase)
        if(linkError) Text(stringResource(R.string.support_no_handler),Modifier.testTag("paywall-link-error"),style=MaterialTheme.typography.bodySmall,
            color=wd.inkRust,textAlign=TextAlign.Center)
        val busy=purchase.busy || checkout
        when {
            purchase.access==AccessState.active->{}
            purchase.access==AccessState.pending->WdButton(stringResource(R.string.paywall_buy_pending),{},Modifier.testTag("paywall-buy"),
                emphasis=WdEmphasis.primary,enabled=false,icon=Icons.Outlined.Schedule)
            price!=null->WdButton(stringResource(R.string.paywall_buy,price),buy,Modifier.testTag("paywall-buy"),emphasis=WdEmphasis.primary,
                icon=Icons.Outlined.Lock,busy=busy)
            purchase.access==AccessState.checking->WdButton(stringResource(R.string.export_access_checking),{},Modifier.testTag("paywall-buy"),
                emphasis=WdEmphasis.primary,enabled=false,busy=true)
            else->WdButton(stringResource(R.string.paywall_buy_unavailable),{},Modifier.testTag("paywall-buy"),emphasis=WdEmphasis.primary,
                enabled=false,icon=Icons.Outlined.Lock)
        }
        FlowRow(horizontalArrangement=Arrangement.Center) {
            PaywallLink(stringResource(R.string.paywall_restore),restore,Modifier.testTag("paywall-restore"),enabled=!busy)
            PaywallLink(stringResource(R.string.paywall_privacy),{open(SupportLink.privacy)},Modifier.testTag("paywall-privacy"))
            PaywallLink(stringResource(R.string.paywall_terms),{open(SupportLink.terms)},Modifier.testTag("paywall-terms"))
        }
    }
}

/** The purchase caption, replaced by the current billing warning (canceled, unavailable, failed) when there is one. */
@Composable private fun PaywallCaption(purchase:BillingState,modifier:Modifier=Modifier) {
    val notice=purchase.notice?.takeIf {it.isPaywallWarning}
    if(notice!=null) Text(billingNoticeText(notice),modifier.testTag("paywall-notice").semantics {liveRegion=LiveRegionMode.Polite},
        style=MaterialTheme.typography.bodySmall,color=wd.inkRust,textAlign=TextAlign.Center)
    else Text(stringResource(R.string.paywall_caption),modifier,style=MaterialTheme.typography.bodySmall,color=wd.textMuted,textAlign=TextAlign.Center)
}

@Composable private fun PaywallLink(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Box(modifier.heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=12.dp),
        contentAlignment=Alignment.Center) {
        Text(text,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium),color=if(enabled) wd.textPrimary else wd.textMuted)
    }
}

