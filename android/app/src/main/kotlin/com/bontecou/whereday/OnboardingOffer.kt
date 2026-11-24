package com.bontecou.whereday

import com.bontecou.whereday.billing.AccessState
import com.bontecou.whereday.billing.BillingState
import com.bontecou.whereday.billing.StorePlan
import com.bontecou.whereday.billing.StoreProduct
import com.bontecou.whereday.domain.RecapFrequency
import com.bontecou.whereday.domain.RecapSchedule
import com.bontecou.whereday.domain.RecapSettings
import com.bontecou.whereday.platform.UsageActionPermit
import com.bontecou.whereday.platform.UsageAuthorization
import java.time.LocalDate
import java.time.ZonedDateTime

/** What the last onboarding step can sell, from the app's existing billing layer (PurchaseController) or a test double.
 *  [buy] opens Play's sheet for one plan, [restore] is "Restore purchases", [retry] queries Play's products again. */
internal class OnboardingOffer(val state:BillingState,val checkout:Boolean=false,val buy:(StorePlan)->Unit={},val restore:()->Unit={},val retry:()->Unit={})

/** Which form the tenth step takes. */
internal enum class OnboardingFinale {
    /** PR #166's last step: billing is unconfigured, or this person already has access. */
    tonight,
    /** "Start my free week": billing is configured and there is no access yet. */
    freeWeek,
    /** "You're in": a checkout started on the free-week step has been verified. */
    done;
    companion object {
        /** [checkedOut] is true once a checkout started from the free-week step (a restore clears it). */
        fun of(billing:BillingState?,checkedOut:Boolean):OnboardingFinale=when {
            billing==null || !billing.enforced->tonight
            billing.access==AccessState.active->if(checkedOut) done else tonight
            else->freeWeek
        }
    }
}

/** The inline chooser's order; Yearly is preselected when Play offers it. */
internal object OnboardingPlans {
    val order=listOf(StorePlan.yearly,StorePlan.weekly,StorePlan.lifetime)
    fun selected(products:List<StoreProduct>,chosen:StorePlan?):StorePlan?=defaultPlan(products.map {it.plan},chosen)
}

/** The primary action on the free-week step. Trial wording appears only when Play offers this account the trial. */
internal enum class OnboardingCta {
    /** A 7-day trial: "Start my free week". */
    freeWeek,
    /** A trial of another length: "Start N-day free trial". */
    trial,
    /** A subscription without an eligible trial: "Subscribe". */
    subscribe,
    /** "Buy Lifetime · price". */
    lifetime;
    companion object {
        fun of(product:StoreProduct):OnboardingCta=when {
            product.plan==StorePlan.lifetime->lifetime
            product.freeTrialDays==7->freeWeek
            product.freeTrialDays>0->trial
            else->subscribe
        }
    }
}

/** The sheet's lead card: the benefit behind the strongest intent, checked in the order notes, routes, countries, days. */
internal enum class OnboardingLead(val intent:OnboardingIntent,val title:Int,val detail:Int) {
    notes(OnboardingIntent.notes,R.string.onboarding_lead_notes_title,R.string.onboarding_lead_notes_detail),
    routes(OnboardingIntent.routes,R.string.onboarding_lead_routes_title,R.string.onboarding_lead_routes_detail),
    countries(OnboardingIntent.countries,R.string.onboarding_lead_countries_title,R.string.onboarding_lead_countries_detail),
    days(OnboardingIntent.days,R.string.onboarding_lead_days_title,R.string.onboarding_lead_days_detail);
    companion object {
        fun of(intents:Set<OnboardingIntent>):OnboardingLead=entries.firstOrNull {it.intent in intents} ?: days
    }
}

/** "Today" on the timeline: each part is dropped when missing. */
internal data class TrialToday(val place:String?,val photoDays:Int?,val recording:Boolean) {
    companion object {
        fun of(place:String?,photoDays:Int,recording:Boolean)=TrialToday(place?.trim()?.takeIf {it.isNotEmpty()},photoDays.takeIf {it>0},recording)
    }
}

internal enum class TrialStepKind {
    today,
    /** Every evening at the recap time: a short recap of the day. */
    evening,
    /** Play's trial reminder, two days before the trial ends. */
    reminder,
    /** The plan's first charge when the trial ends. */
    begins,
    /** A subscription without a trial: charged today, renewing on [TrialStep.date]. */
    renews,
    /** Lifetime: one payment, no renewals. */
    forGood,
}

internal data class TrialStep(val kind:TrialStepKind,val date:LocalDate?=null,val day:Int?=null)

/** The personalised timeline on the free-week step, with real dates from today and the plan Play offers. */
internal object OnboardingTrialTimeline {
    fun steps(product:StoreProduct,now:ZonedDateTime):List<TrialStep> {
        val today=now.toLocalDate()
        val tonight=TrialStep(TrialStepKind.evening)
        val days=product.freeTrialDays
        return when {
            product.plan==StorePlan.lifetime->listOf(TrialStep(TrialStepKind.today,today),tonight,TrialStep(TrialStepKind.forGood))
            days>0->listOfNotNull(TrialStep(TrialStepKind.today,today),tonight,
                // TrialReminder fires two days before the trial ends; a trial too short for that has no reminder row.
                (days-2).takeIf {it>0}?.let {TrialStep(TrialStepKind.reminder,today.plusDays(it.toLong()),day=it)},
                TrialStep(TrialStepKind.begins,trialEnds(product,today),day=days))
            else->listOf(TrialStep(TrialStepKind.today,today),tonight,
                TrialStep(TrialStepKind.renews,if(product.plan==StorePlan.weekly) today.plusWeeks(1) else today.plusYears(1)))
        }
    }
    /** The day the first charge happens; null without a trial. */
    fun trialEnds(product:StoreProduct,today:LocalDate):LocalDate?=product.freeTrialDays.takeIf {it>0 && product.isSubscription}?.let {today.plusDays(it.toLong())}
}

/** How the tenth step finishes. Returns null where the step cannot finish: the free-week step completes only through a verified purchase. */
internal object OnboardingFinish {
    fun decide(finale:OnboardingFinale,startImmediately:Boolean,canStart:Boolean,recap:Boolean,notificationsAllowed:Boolean,
        authorization:UsageAuthorization,permit:UsageActionPermit?):OnboardingDecision?=when(finale) {
        // PR #166 unchanged: recording only on an explicit choice; the evening recap only where notifications may post.
        OnboardingFinale.tonight->OnboardingDecision(startImmediately && canStart,authorization,permit,recap=recap && notificationsAllowed)
        OnboardingFinale.freeWeek->null
        // The trial is the choice to record: it starts now wherever location allows. Recaps are turned on only here.
        OnboardingFinale.done->OnboardingDecision(canStart,authorization,permit,recap=recap && notificationsAllowed,trial=true)
    }
}

/** The last step's "Evening recap" switch, which sets Settings › Reminders › Day recap. */
internal object OnboardingRecap {
    /** On is Every evening, off is Off. */
    fun frequency(decision:OnboardingDecision)=RecapFrequency.fromToggle(decision.recap)
    /** "starting tomorrow" once tonight's recap time has passed. */
    fun startsTomorrow(time:java.time.LocalTime,now:ZonedDateTime)=!RecapSchedule.today(RecapSettings(RecapFrequency.daily,time.hour*60+time.minute),now)
}
