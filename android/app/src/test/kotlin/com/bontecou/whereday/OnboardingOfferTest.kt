package com.bontecou.whereday

import com.bontecou.whereday.billing.*
import com.bontecou.whereday.platform.UsageAuthorization
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

/** Free week as the last onboarding step, with synthetic Play products standing in for BillingSession's state. */
class OnboardingOfferTest {
    private val zone=ZoneId.of("America/Los_Angeles")
    private val morning=ZonedDateTime.of(2026,11,23,10,0,0,0,zone)
    private val today=morning.toLocalDate()
    private fun weekly(trial:Int=7)=StoreProduct("whereday_individual",StorePlan.weekly,"$0.99",990_000,"USD",trial)
    private fun yearly(trial:Int=7)=StoreProduct("whereday_individual",StorePlan.yearly,"$29.99",29_990_000,"USD",trial)
    private val lifetime=StoreProduct("whereday_export_lifetime",StorePlan.lifetime,"$49.99",49_990_000,"USD")
    private fun billing(access:AccessState,enforced:Boolean=true,products:List<StoreProduct> = listOf(weekly(),yearly(),lifetime))=BillingState(access,products,enforced=enforced)

    @Test fun nothingToSellKeepsTheTonightStep() {
        assertEquals(OnboardingFinale.tonight,OnboardingFinale.of(null,checkedOut=false))
        // Today's real state: the verifier isn't deployed, so BillingSession reports unavailable and does not enforce.
        assertEquals(OnboardingFinale.tonight,OnboardingFinale.of(BillingState(AccessState.unavailable,notice=BillingNotice.unavailable,enforced=false),checkedOut=false))
        assertEquals(OnboardingFinale.tonight,OnboardingFinale.of(BillingState(enforced=false),checkedOut=false))
        // Legacy, purchased or restored access.
        assertEquals(OnboardingFinale.tonight,OnboardingFinale.of(billing(AccessState.active),checkedOut=false))
    }
    @Test fun configuredBillingWithoutAccessSellsTheFreeWeek() {
        AccessState.entries.filter {it!=AccessState.active}.forEach {access->
            assertEquals(access.name,OnboardingFinale.freeWeek,OnboardingFinale.of(billing(access),checkedOut=false))
            assertEquals(access.name,OnboardingFinale.freeWeek,OnboardingFinale.of(billing(access),checkedOut=true))
        }
        assertEquals(OnboardingFinale.freeWeek,OnboardingFinale.of(billing(AccessState.free,products=emptyList()),checkedOut=false))
    }
    @Test fun aVerifiedCheckoutFromTheStepOpensYoureIn() {
        assertEquals(OnboardingFinale.done,OnboardingFinale.of(billing(AccessState.active),checkedOut=true))
        // Without a checkout here (a restore), access lands on the tonight step instead.
        assertEquals(OnboardingFinale.tonight,OnboardingFinale.of(billing(AccessState.active),checkedOut=false))
    }
    @Test fun yearlyIsPreselectedAndChoicesStayWithinWhatPlayOffers() {
        assertEquals(StorePlan.yearly,OnboardingPlans.selected(listOf(weekly(),yearly(),lifetime),null))
        assertEquals(StorePlan.lifetime,OnboardingPlans.selected(listOf(weekly(),yearly(),lifetime),StorePlan.lifetime))
        assertEquals(StorePlan.weekly,OnboardingPlans.selected(listOf(weekly(),lifetime),null))
        assertEquals(StorePlan.weekly,OnboardingPlans.selected(listOf(weekly(),lifetime),StorePlan.yearly))
        assertNull(OnboardingPlans.selected(emptyList(),StorePlan.yearly))
    }
    @Test fun leadCardFollowsNotesRoutesCountriesThenDays() {
        assertEquals(OnboardingLead.days,OnboardingLead.of(emptySet()))
        assertEquals(OnboardingLead.days,OnboardingLead.of(setOf(OnboardingIntent.days)))
        assertEquals(OnboardingLead.countries,OnboardingLead.of(setOf(OnboardingIntent.days,OnboardingIntent.countries)))
        assertEquals(OnboardingLead.routes,OnboardingLead.of(setOf(OnboardingIntent.countries,OnboardingIntent.routes)))
        assertEquals(OnboardingLead.notes,OnboardingLead.of(OnboardingIntent.entries.toSet()))
        assertEquals(OnboardingLead.entries.map {it.intent},listOf(OnboardingIntent.notes,OnboardingIntent.routes,OnboardingIntent.countries,OnboardingIntent.days))
    }
    @Test fun trialTimelineUsesRealDatesFromToday() {
        val steps=OnboardingTrialTimeline.steps(yearly(),morning)
        assertEquals(listOf(TrialStepKind.today,TrialStepKind.tonight,TrialStepKind.reminder,TrialStepKind.begins),steps.map {it.kind})
        assertEquals(today,steps[0].date)
        assertEquals(today,steps[1].date);assertFalse(steps[1].tomorrow)
        assertEquals(TrialStep(TrialStepKind.reminder,LocalDate.of(2026,11,28),day=5),steps[2])
        assertEquals(TrialStep(TrialStepKind.begins,LocalDate.of(2026,11,30),day=7),steps[3])
        assertEquals(LocalDate.of(2026,11,30),OnboardingTrialTimeline.trialEnds(yearly(),today))
        // Month boundaries use calendar days.
        assertEquals(LocalDate.of(2026,12,5),OnboardingTrialTimeline.steps(weekly(),ZonedDateTime.of(2026,11,28,9,0,0,0,zone)).last().date)
    }
    @Test fun lateEveningMovesTonightsPageToTomorrow() {
        val late=ZonedDateTime.of(2026,11,23,20,45,0,0,zone)
        val tonight=OnboardingTrialTimeline.steps(yearly(),late)[1]
        assertTrue(tonight.tomorrow);assertEquals(LocalDate.of(2026,11,24),tonight.date)
    }
    @Test fun lifetimeAndNoTrialTimelinesPromiseNoTrial() {
        assertEquals(listOf(TrialStepKind.today,TrialStepKind.tonight,TrialStepKind.forGood),OnboardingTrialTimeline.steps(lifetime,morning).map {it.kind})
        assertNull(OnboardingTrialTimeline.trialEnds(lifetime,today))
        val noTrial=OnboardingTrialTimeline.steps(yearly(trial=0),morning)
        assertEquals(listOf(TrialStepKind.today,TrialStepKind.tonight,TrialStepKind.renews),noTrial.map {it.kind})
        assertEquals(LocalDate.of(2027,11,23),noTrial.last().date)
        assertEquals(LocalDate.of(2026,11,30),OnboardingTrialTimeline.steps(weekly(trial=0),morning).last().date)
        assertNull(OnboardingTrialTimeline.trialEnds(weekly(trial=0),today))
        // A trial too short for the two-day reminder shows no reminder row.
        assertEquals(listOf(TrialStepKind.today,TrialStepKind.tonight,TrialStepKind.begins),OnboardingTrialTimeline.steps(weekly(trial=2),morning).map {it.kind})
    }
    @Test fun todayDropsWhatIsMissing() {
        assertEquals(TrialToday("Café",75,true),TrialToday.of("Café",75,true))
        assertEquals(TrialToday(null,75,true),TrialToday.of(null,75,true))
        assertEquals(TrialToday(null,75,true),TrialToday.of("  ",75,true))
        assertEquals(TrialToday("Home",null,false),TrialToday.of("Home",0,false))
    }
    @Test fun trialCopyOnlyWhenPlayOffersTheTrial() {
        assertEquals(OnboardingCta.freeWeek,OnboardingCta.of(yearly()))
        assertEquals(OnboardingCta.freeWeek,OnboardingCta.of(weekly()))
        assertEquals(OnboardingCta.subscribe,OnboardingCta.of(yearly(trial=0)))
        assertEquals(OnboardingCta.subscribe,OnboardingCta.of(weekly(trial=0)))
        assertEquals(OnboardingCta.trial,OnboardingCta.of(weekly(trial=3)))
        assertEquals(OnboardingCta.lifetime,OnboardingCta.of(lifetime))
    }
    @Test fun nightlyPageIsScheduledOnlyAfterThePurchase() {
        fun decide(finale:OnboardingFinale,start:Boolean=false,canStart:Boolean=true,nightly:Boolean=true,allowed:Boolean=true)=
            OnboardingFinish.decide(finale,start,canStart,nightly,allowed,UsageAuthorization.foreground,null)
        // Before a verified checkout the free-week step cannot finish, so nothing is scheduled or started.
        assertNull(decide(OnboardingFinale.freeWeek))
        val done=decide(OnboardingFinale.done)!!
        assertTrue(done.trial);assertTrue(done.nightly);assertTrue(done.start)
        assertFalse(decide(OnboardingFinale.done,nightly=false)!!.nightly)
        assertFalse(decide(OnboardingFinale.done,allowed=false)!!.nightly)
        assertFalse(decide(OnboardingFinale.done,canStart=false)!!.start)
        // PR #166's tonight step keeps its semantics: start only on an explicit choice.
        val tonight=decide(OnboardingFinale.tonight)!!
        assertFalse(tonight.trial);assertFalse(tonight.start);assertTrue(tonight.nightly)
        assertTrue(decide(OnboardingFinale.tonight,start=true)!!.start)
        assertFalse(decide(OnboardingFinale.tonight,start=true,canStart=false)!!.start)
        assertFalse(decide(OnboardingFinale.tonight,allowed=false)!!.nightly)
    }
}
