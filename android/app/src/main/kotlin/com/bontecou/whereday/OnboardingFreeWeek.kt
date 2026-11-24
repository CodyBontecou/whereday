@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.billing.AccessState
import com.bontecou.whereday.billing.StorePlan
import com.bontecou.whereday.billing.StoreProduct
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable private fun uiLocale():Locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()

@Composable internal fun trialDate(date:LocalDate):String {
    val locale=uiLocale()
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"MMMd"),locale).format(date)
}

/** The recap time on the phone's 12/24-hour clock: "9:00 PM", "21:00". */
@Composable internal fun recapClock(time:java.time.LocalTime):String=DateTimeFormatter.ofPattern(if(android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "H:mm" else "h:mm a",uiLocale())
    .format(time)

/** The last step's "Evening recap" switch, on the tonight step and on "You're in" (tag `onboarding-recap-toggle`). */
@Composable internal fun RecapToggleCard(on:Boolean,enabled:Boolean,time:java.time.LocalTime,tomorrow:Boolean,onChange:(Boolean)->Unit) {
    WdCard(radius=16.dp) {
        WdToggleRow(stringResource(R.string.onboarding_recap_toggle),on,onChange,Modifier.testTag("onboarding-recap-toggle"),enabled=enabled,divider=false,
            detail=stringResource(if(tomorrow) R.string.onboarding_recap_detail_tomorrow else R.string.onboarding_recap_detail,recapClock(time)))
    }
}

@Composable private fun Kicker(text:String) {
    Text(text.uppercase(uiLocale()),style=WdType.eyebrow.copy(letterSpacing=0.08.em),color=wd.inkRust)
}

/** The selected plan's billing words, all from Play's product: "$29.99 per year, automatically renewing until canceled." */
@Composable private fun renewal(product:StoreProduct)=stringResource(if(product.plan==StorePlan.weekly) R.string.paywall_disclosure_weekly else R.string.paywall_disclosure_yearly,product.formattedPrice)

@Composable private fun todayText(today:TrialToday):String {
    val parts=listOfNotNull(
        today.place?.let {stringResource(R.string.onboarding_trial_today_place,it)},
        today.photoDays?.let {pluralStringResource(R.plurals.onboarding_trial_today_photos,it,it)},
        stringResource(if(today.recording) R.string.onboarding_trial_today_recording else R.string.onboarding_trial_today_no_location))
    return parts.joinToString(" ")
}

/** "When" and "what" for each timeline row. */
@Composable private fun timelineRow(step:TrialStep,product:StoreProduct,today:TrialToday,recapTime:java.time.LocalTime):Pair<String,String> {
    val time=recapClock(recapTime)
    return when(step.kind) {
        TrialStepKind.today->stringResource(R.string.paywall_trial_today) to todayText(today)
        TrialStepKind.evening->stringResource(R.string.onboarding_trial_when_evening,time) to stringResource(R.string.onboarding_trial_evening_body)
        TrialStepKind.reminder->stringResource(R.string.onboarding_trial_when_day,step.day!!,trialDate(step.date!!)) to stringResource(R.string.paywall_trial_reminder_body)
        TrialStepKind.begins->stringResource(R.string.onboarding_trial_when_day,step.day!!,trialDate(step.date!!)) to
            stringResource(if(product.plan==StorePlan.yearly) R.string.paywall_trial_begins_yearly else R.string.paywall_trial_begins_weekly,product.formattedPrice)
        TrialStepKind.renews->stringResource(R.string.onboarding_trial_when_renews,trialDate(step.date!!)) to renewal(product)
        TrialStepKind.forGood->stringResource(R.string.onboarding_trial_when_for_good) to stringResource(R.string.onboarding_trial_for_good_body,product.formattedPrice)
    }
}

/** The primary's label for the selected plan. */
@Composable internal fun freeWeekCta(product:StoreProduct):String=when(OnboardingCta.of(product)) {
    OnboardingCta.freeWeek->stringResource(R.string.onboarding_trial_start)
    OnboardingCta.trial->stringResource(R.string.paywall_cta_trial,product.freeTrialDays)
    OnboardingCta.subscribe->stringResource(R.string.paywall_cta_subscribe)
    OnboardingCta.lifetime->stringResource(R.string.onboarding_trial_buy_lifetime,product.formattedPrice)
}

// MARK: The free-week step

@Composable internal fun FreeWeekStep(offer:OnboardingOffer,selected:StorePlan?,onSelect:(StorePlan)->Unit,now:ZonedDateTime,recapTime:java.time.LocalTime,today:TrialToday,enabled:Boolean) {
    val state=offer.state
    val product=selected?.let {state.product(it)}
    val cta=product?.let {OnboardingCta.of(it)}
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Kicker(stringResource(when(cta) {
                OnboardingCta.freeWeek,null->R.string.onboarding_trial_kicker
                OnboardingCta.trial->R.string.onboarding_trial_kicker_trial
                else->R.string.onboarding_trial_kicker_plan
            }))
            Text(stringResource(when(cta) {OnboardingCta.lifetime->R.string.onboarding_trial_title_lifetime;OnboardingCta.subscribe->R.string.onboarding_trial_title_plan;else->R.string.onboarding_trial_title}),
                Modifier.semantics {heading()},style=WdType.largeTitle.copy(fontWeight=FontWeight.Normal,lineHeight=38.sp),color=wd.textPrimary)
        }
        if(product==null) {
            if(state.busy || state.access==AccessState.checking) Row(Modifier.testTag("onboarding-trial-loading"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.dp)
                Text(stringResource(R.string.paywall_loading),style=WdType.caption,color=wd.textMuted)
            } else WdNotice(stringResource(R.string.onboarding_trial_plans_failed),Modifier.testTag("onboarding-trial-failed"),icon=Icons.Outlined.ErrorOutline,warning=true) {
                TextButton(offer.retry,Modifier.heightIn(min=48.dp).testTag("onboarding-trial-retry"),colors=ButtonDefaults.textButtonColors(contentColor=wd.onSecondaryContainer)) {
                    Text(stringResource(R.string.onboarding_trial_retry),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium))
                }
            }
        } else {
            TrialTimeline(OnboardingTrialTimeline.steps(product,now),product,today,recapTime)
            QuickPlans(state.products,selected,enabled,onSelect)
        }
        when {
            state.access==AccessState.pending->PaywallStatus(stringResource(R.string.paywall_pending),wd.accent,Icons.Outlined.Schedule,Modifier.testTag("paywall-pending"))
            else->state.notice?.takeIf {it.isPaywallWarning}?.let {
                Text(billingNoticeText(it),Modifier.testTag("paywall-notice").semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)
            }
        }
    }
}

@Composable private fun TrialTimeline(steps:List<TrialStep>,product:StoreProduct,today:TrialToday,recapTime:java.time.LocalTime) {
    val rows=steps.map {timelineRow(it,product,today,recapTime)}
    val context=LocalContext.current
    val summary=stringResource(R.string.onboarding_trial_timeline,rows.joinToString(" ") {(time,text)->context.getString(R.string.onboarding_trial_timeline_entry,time,text)})
    val shape=RoundedCornerShape(16.dp)
    val locale=uiLocale()
    Column(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).padding(16.dp).testTag("onboarding-trial-timeline")
        .clearAndSetSemantics {contentDescription=summary}) {
        rows.forEachIndexed {index,(time,text)->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(14.dp).fillMaxHeight(),horizontalAlignment=Alignment.CenterHorizontally) {
                    if(index==0) Box(Modifier.padding(top=3.dp).size(14.dp).clip(CircleShape).background(wd.inkRust))
                    else Box(Modifier.padding(top=4.dp).size(12.dp).clip(CircleShape).border(2.dp,wd.accent,CircleShape))
                    if(index<rows.lastIndex) Box(Modifier.padding(vertical=3.dp).width(2.dp).weight(1f).heightIn(min=12.dp).background(wd.border))
                }
                Column(Modifier.weight(1f).padding(bottom=if(index<rows.lastIndex) 12.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                    Text(time.uppercase(locale),style=WdType.caption2.copy(letterSpacing=0.06.em),color=wd.textMuted)
                    Text(text,style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),color=wd.textPrimary)
                }
            }
        }
    }
}

@Composable private fun QuickPlans(products:List<StoreProduct>,selected:StorePlan?,enabled:Boolean,onSelect:(StorePlan)->Unit) {
    val label=stringResource(R.string.onboarding_trial_plans)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup().semantics {contentDescription=label},horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OnboardingPlans.order.mapNotNull {plan->products.firstOrNull {it.plan==plan}}.forEach {product->
            val on=product.plan==selected
            val shape=RoundedCornerShape(14.dp)
            Column(Modifier.weight(1f).fillMaxHeight().heightIn(min=64.dp).clip(shape).background(wd.card).border(if(on) 2.dp else 1.dp,if(on) wd.accent else wd.border,shape)
                .selectable(on,enabled=enabled,role=Role.RadioButton) {onSelect(product.plan)}.padding(10.dp).testTag("onboarding-plan-${product.plan.name}"),
                verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(planName(product.plan),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp,fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                Text(when(product.plan) {
                    StorePlan.weekly->stringResource(R.string.onboarding_plan_price_weekly,product.formattedPrice)
                    StorePlan.yearly->stringResource(R.string.onboarding_plan_price_yearly,product.formattedPrice)
                    StorePlan.lifetime->product.formattedPrice
                },style=TextStyle(fontFamily=WdFonts.mono,fontSize=12.sp),color=wd.textPrimary)
                val tag=when {
                    product.plan==StorePlan.lifetime->stringResource(R.string.paywall_badge_once)
                    product.freeTrialDays>0->stringResource(R.string.paywall_badge_trial,product.freeTrialDays)
                    else->null
                }
                if(tag!=null) Text(tag,style=MaterialTheme.typography.bodySmall.copy(fontSize=11.sp,fontWeight=FontWeight.SemiBold),color=wd.inkRust)
            }
        }
    }
}

/** Play's subscription disclosure for the selected plan: price, period, auto-renewal, the trial's end and how to cancel. */
@Composable internal fun freeWeekDisclosure(product:StoreProduct,today:LocalDate):String {
    if(product.plan==StorePlan.lifetime) return stringResource(R.string.paywall_disclosure_lifetime,product.formattedPrice)
    val ends=OnboardingTrialTimeline.trialEnds(product,today)
    return listOfNotNull(paywallDisclosure(product),ends?.let {stringResource(R.string.onboarding_trial_free_until,trialDate(it))},
        stringResource(if(ends!=null) R.string.onboarding_trial_cancel else R.string.onboarding_trial_cancel_plan)).joinToString(" ")
}

/** Under the primary: All plans, the disclosure, then Restore · Terms · Privacy. */
@Composable internal fun FreeWeekFooter(offer:OnboardingOffer,product:StoreProduct?,today:LocalDate,enabled:Boolean,onAllPlans:()->Unit,onRestore:()->Unit,modifier:Modifier=Modifier) {
    val uriHandler=LocalUriHandler.current
    var linkError by remember {mutableStateOf(false)}
    fun open(link:SupportLink) {try {uriHandler.openUri(link.uri);linkError=false} catch(_:Exception) {linkError=true}}
    Column(modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(offer.state.products.isNotEmpty()) PaywallLink(stringResource(R.string.onboarding_trial_all_plans),onAllPlans,Modifier.testTag("onboarding-all-plans"),enabled=enabled)
        if(product!=null) Text(freeWeekDisclosure(product,today),Modifier.fillMaxWidth().testTag("onboarding-trial-disclosure"),style=MaterialTheme.typography.bodySmall.copy(fontSize=11.sp),
            color=wd.textMuted,textAlign=TextAlign.Center)
        if(linkError) Text(stringResource(R.string.support_no_handler),style=MaterialTheme.typography.bodySmall,color=wd.inkRust,textAlign=TextAlign.Center)
        FlowRow(horizontalArrangement=Arrangement.Center) {
            PaywallLink(stringResource(R.string.paywall_restore),onRestore,Modifier.testTag("paywall-restore"),enabled=enabled)
            PaywallLink(stringResource(R.string.paywall_terms),{open(SupportLink.terms)},Modifier.testTag("paywall-terms"))
            PaywallLink(stringResource(R.string.paywall_privacy),{open(SupportLink.privacy)},Modifier.testTag("paywall-privacy"))
        }
    }
}

// MARK: All plans

@Composable internal fun AllPlansSheet(offer:OnboardingOffer,selected:StorePlan?,intents:Set<OnboardingIntent>,today:LocalDate,enabled:Boolean,busy:Boolean,
    onSelect:(StorePlan)->Unit,onBuy:()->Unit,dismiss:()->Unit) {
    val state=offer.state
    val product=selected?.let {state.product(it)}
    val lead=OnboardingLead.of(intents)
    WorkspaceModalBottomSheet(dismiss,fullHeight=true) {
        Column(Modifier.fillMaxWidth().semantics {testTagsAsResourceId=true}.testTag("paywall-sheet").verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(start=24.dp,end=24.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.onboarding_trial_all_plans),Modifier.weight(1f).semantics {heading()},
                    style=WdType.title.copy(fontWeight=FontWeight.Normal,fontSize=28.sp),color=wd.textPrimary)
                WdIconButton(Icons.Outlined.Close,stringResource(R.string.paywall_close),dismiss,Modifier.testTag("paywall-sheet-close"))
            }
            val shape=RoundedCornerShape(16.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(wd.inkCard).padding(horizontal=16.dp,vertical=14.dp).testTag("paywall-lead-${lead.name}")
                .semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.onboarding_lead_kicker,stringResource(lead.intent.title)).uppercase(uiLocale()),style=WdType.caption2.copy(letterSpacing=0.06.em),color=wd.onInkCardMuted)
                Text(stringResource(lead.title),style=WdType.title3.copy(fontWeight=FontWeight.Normal,fontSize=20.sp,lineHeight=24.sp),color=wd.onInkCard)
                Text(stringResource(lead.detail),style=MaterialTheme.typography.bodyMedium.copy(fontSize=13.sp),color=wd.onInkCardMuted)
            }
            Column(Modifier.selectableGroup(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OnboardingPlans.order.mapNotNull {plan->state.products.firstOrNull {it.plan==plan}}.forEach {plan->
                    PaywallPlanCard(plan,plan.plan==selected) {if(enabled) onSelect(plan.plan)}
                }
            }
            PaywallIncludedCard()
            if(product!=null) {
                WdButton(freeWeekCta(product),onBuy,Modifier.heightIn(min=56.dp).testTag("paywall-sheet-buy"),emphasis=WdEmphasis.primary,
                    enabled=enabled && state.access!=AccessState.pending,busy=busy)
                Text(freeWeekDisclosure(product,today),Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall.copy(fontSize=11.sp),color=wd.textMuted,textAlign=TextAlign.Center)
            }
        }
    }
}

// MARK: You're in

@Composable internal fun TrialDoneStep(plan:StoreProduct?,today:LocalDate,canStart:Boolean,recap:Boolean,recapEnabled:Boolean,notificationsAllowed:Boolean,
    time:java.time.LocalTime,tomorrow:Boolean,onRecapChange:(Boolean)->Unit) {
    val ends=plan?.let {OnboardingTrialTimeline.trialEnds(it,today)}
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        val done=stringResource(R.string.onboarding_done_icon)
        // The step marker sits on the described check mark so accessibility clients (and e2e tests) can see it.
        Box(Modifier.size(56.dp).clip(CircleShape).background(wd.accent).semantics {contentDescription=done}.testTag("onboarding-trial-done"),contentAlignment=Alignment.Center) {
            Icon(Icons.Outlined.Check,null,Modifier.size(30.dp),tint=wd.surface)
        }
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Kicker(when {
                ends!=null->stringResource(R.string.onboarding_done_kicker_trial,trialDate(ends))
                plan?.plan==StorePlan.lifetime->stringResource(R.string.onboarding_done_kicker_lifetime)
                plan!=null->stringResource(R.string.onboarding_done_kicker_plan,planName(plan.plan))
                else->stringResource(R.string.onboarding_trial_kicker_plan)
            })
            Text(stringResource(R.string.onboarding_done_title),Modifier.semantics {heading()},style=WdType.largeTitle.copy(fontWeight=FontWeight.Normal,lineHeight=38.sp),color=wd.textPrimary)
        }
        Text(stringResource(if(canStart) R.string.onboarding_done_body else R.string.onboarding_done_body_no_location),style=MaterialTheme.typography.bodyLarge,color=wd.textMuted)
        RecapToggleCard(recap,recapEnabled,time,tomorrow,onRecapChange)
        when {
            !recapEnabled->Text(stringResource(R.string.onboarding_tonight_notifications_off),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
            !notificationsAllowed->Text(stringResource(R.string.onboarding_done_notifications_ask),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        }
    }
}
