package com.bontecou.whereday

import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.export.ExportFormat
import com.bontecou.whereday.platform.EveningRecap
import com.bontecou.whereday.platform.UsageStep
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class OnboardingPlanTest {
    @Test fun tenStepsInTheDesignOrder() {
        assertEquals(listOf("cover","intent","scrub","pen","perm","stamp","backfill","glance","export","tonight"),OnboardingStep.entries.map {it.name})
        assertEquals(OnboardingStep.cover,OnboardingStep.at(-1));assertEquals(OnboardingStep.tonight,OnboardingStep.at(99))
    }
    @Test fun analyticsKeepsTheReviewedFiveStepVocabulary() {
        val mapped=OnboardingStep.entries.associateWith {it.usageStep}
        assertEquals(UsageStep.welcome,mapped[OnboardingStep.cover])
        assertEquals(UsageStep.permissions,mapped[OnboardingStep.perm])
        assertEquals(UsageStep.notifications,mapped[OnboardingStep.glance])
        assertEquals(UsageStep.ready,mapped[OnboardingStep.tonight])
        listOf(OnboardingStep.intent,OnboardingStep.scrub,OnboardingStep.pen,OnboardingStep.stamp,OnboardingStep.backfill,OnboardingStep.export)
            .forEach {assertEquals(it.name,UsageStep.features,mapped[it])}
        // Every reviewed step is still reachable, so the existing funnel keeps its meaning.
        assertEquals(UsageStep.entries.toSet(),mapped.values.toSet())
    }
    @Test fun intentsDefaultToDaysAndRoundTripStableKeys() {
        assertEquals(setOf(OnboardingIntent.days),OnboardingIntent.DEFAULT)
        assertEquals(OnboardingIntent.DEFAULT,OnboardingIntent.decode(null))
        assertEquals(OnboardingIntent.DEFAULT,OnboardingIntent.decode(AppSettings().onboardingIntents))
        assertEquals(emptySet<OnboardingIntent>(),OnboardingIntent.decode(emptySet()))
        val all=OnboardingIntent.entries.toSet()
        assertEquals(setOf("countries","days","notes","routes"),OnboardingIntent.encode(all))
        assertEquals(all,OnboardingIntent.decode(OnboardingIntent.encode(all)))
        assertEquals(setOf(OnboardingIntent.notes),OnboardingIntent.decode(setOf("notes","future-intent")))
        val saved=AppSettings().copy(onboardingIntents=OnboardingIntent.encode(setOf(OnboardingIntent.routes)))
        assertEquals(setOf(OnboardingIntent.routes),OnboardingIntent.decode(saved.onboardingIntents))
    }
    @Test fun exportDefaultFollowsIntentsNotesFirst() {
        fun pick(vararg intents:OnboardingIntent)=OnboardingFormat.preferred(intents.toSet())
        assertEquals(OnboardingFormat.markdown,pick())
        assertEquals(OnboardingFormat.markdown,pick(OnboardingIntent.days))
        assertEquals(OnboardingFormat.csv,pick(OnboardingIntent.days,OnboardingIntent.countries))
        assertEquals(OnboardingFormat.gpx,pick(OnboardingIntent.countries,OnboardingIntent.routes))
        assertEquals(OnboardingFormat.markdown,pick(OnboardingIntent.routes,OnboardingIntent.notes,OnboardingIntent.countries))
        assertEquals(ExportFormat.markdown,OnboardingFormat.savedDefault(setOf(OnboardingIntent.notes,OnboardingIntent.routes)))
        assertNull(OnboardingFormat.savedDefault(setOf(OnboardingIntent.routes,OnboardingIntent.countries,OnboardingIntent.days)))
        assertEquals(listOf(ExportFormat.markdown,ExportFormat.gpx,ExportFormat.csv,ExportFormat.json),OnboardingFormat.entries.map {it.exportFormat})
    }
    @Test fun sampleDayCoversEveryQuarterHourWithoutGaps() {
        val stops=OnboardingSampleDay.stops
        assertEquals(0f,stops.first().start);assertEquals(24f,stops.last().end)
        stops.zipWithNext().forEach {(a,b)->assertEquals(a.end,b.start)}
        assertEquals(R.string.onboarding_sample_cafe,OnboardingSampleDay.at(OnboardingSampleDay.START_HOUR).name)
        assertEquals(R.string.onboarding_sample_home,OnboardingSampleDay.at(OnboardingSampleDay.LAST_HOUR).name)
        assertEquals(R.string.onboarding_sample_office,OnboardingSampleDay.at(12f).name)
        assertNull(OnboardingSampleDay.at(17.75f).ink)
    }
    @Test fun heatmapIsTwelveWeeksEndingTodayAndCountsOnlyEarlierDays() {
        val today=LocalDate.of(2026,11,23)
        val days=OnboardingHeatmap.days(today)
        assertEquals(84,days.size);assertEquals(today,days.last());assertEquals(today.minusDays(83),days.first())
        assertEquals(listOf(0,1,2,2,2,3,3),listOf(0,1,2,3,4,5,40).map(OnboardingHeatmap::level))
        val counts=mapOf(today to 9,today.minusDays(1) to 2,today.minusDays(83) to 1,today.minusDays(84) to 4,today.minusDays(5) to 0)
        assertEquals(2,OnboardingHeatmap.foundDays(counts,today))
    }
    @Test fun onlyNamedChipsSaveAPlace() {
        assertEquals(listOf("home","work","cafe","gym","other"),OnboardingPlaceName.entries.map {it.key})
        assertFalse(OnboardingPlaceName.other.saves);assertTrue(OnboardingPlaceName.home.saves)
        assertNull(OnboardingPlaceName.fromKey(null));assertEquals(OnboardingPlaceName.gym,OnboardingPlaceName.fromKey("gym"))
    }
    @Test fun eveningRecapOpensTheTimelineShortcut() {
        assertEquals(LauncherAction.timeline.action,EveningRecap.OPEN_ACTION)
        assertEquals(LauncherAction.timeline,LauncherAction.fromAction(EveningRecap.OPEN_ACTION))
    }
}
