package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class EveningRecapTest {
    private val zone=ZoneId.of("America/Los_Angeles")
    private fun at(date:String,time:String)=ZonedDateTime.of(LocalDate.parse(date),LocalTime.parse(time),zone)
    private fun settings(frequency:RecapFrequency,minute:Int=RecapSettings.DEFAULT_MINUTE,quiet:Boolean=true,names:Boolean=false)=RecapSettings(frequency,minute,quiet,names)

    // MARK: Next run

    @Test fun offNeverRuns() {
        assertNull(RecapSchedule.next(settings(RecapFrequency.off),at("2026-11-23","08:00")))
        assertFalse(RecapSettings().enabled);assertEquals(RecapFrequency.off,RecapSettings().frequency)
        assertEquals(LocalTime.of(21,0),RecapSettings().time);assertTrue(RecapSettings().skipQuietDays);assertFalse(RecapSettings().placeNames)
    }
    @Test fun everyEveningRunsTonightUntilTheTimeThenTomorrow() {
        val daily=settings(RecapFrequency.daily)
        assertEquals(at("2026-11-23","21:00"),RecapSchedule.next(daily,at("2026-11-23","00:00")))
        assertEquals(at("2026-11-23","21:00"),RecapSchedule.next(daily,at("2026-11-23","20:59:59")))
        assertTrue(RecapSchedule.today(daily,at("2026-11-23","20:45")))
        // At or after the time it is tomorrow's: never the one that just ran.
        assertEquals(at("2026-11-24","21:00"),RecapSchedule.next(daily,at("2026-11-23","21:00")))
        assertEquals(at("2026-11-24","21:00"),RecapSchedule.next(daily,at("2026-11-23","23:59")))
        assertFalse(RecapSchedule.today(daily,at("2026-11-23","21:30")))
    }
    @Test fun acrossMidnightAndYearEnd() {
        val late=settings(RecapFrequency.daily,23*60+30)
        assertEquals(at("2026-12-31","23:30"),RecapSchedule.next(late,at("2026-12-31","23:29")))
        assertEquals(at("2027-01-01","23:30"),RecapSchedule.next(late,at("2026-12-31","23:45")))
        // A custom time just after midnight runs the same night once midnight has passed.
        val early=settings(RecapFrequency.daily,15)
        assertEquals(at("2026-11-24","00:15"),RecapSchedule.next(early,at("2026-11-23","22:00")))
        assertEquals(at("2026-11-24","00:15"),RecapSchedule.next(early,at("2026-11-24","00:00")))
    }
    @Test fun weekdaysSkipSaturdayAndSunday() {
        val weekdays=settings(RecapFrequency.weekdays)
        // 2026-11-27 is a Friday.
        assertEquals(at("2026-11-27","21:00"),RecapSchedule.next(weekdays,at("2026-11-27","12:00")))
        assertEquals(at("2026-11-30","21:00"),RecapSchedule.next(weekdays,at("2026-11-27","21:00")))
        assertEquals(at("2026-11-30","21:00"),RecapSchedule.next(weekdays,at("2026-11-28","09:00")))
        assertEquals(at("2026-11-30","21:00"),RecapSchedule.next(weekdays,at("2026-11-29","22:00")))
        assertTrue((23..27).all {RecapSchedule.firesOn(RecapFrequency.weekdays,LocalDate.of(2026,11,it))})
        assertFalse(RecapSchedule.firesOn(RecapFrequency.weekdays,LocalDate.of(2026,11,28)))
        assertFalse(RecapSchedule.firesOn(RecapFrequency.weekdays,LocalDate.of(2026,11,29)))
    }
    @Test fun weeklyRunsOnSunday() {
        val weekly=settings(RecapFrequency.weekly,22*60)
        // Monday 2026-11-23 → Sunday 2026-11-29.
        assertEquals(at("2026-11-29","22:00"),RecapSchedule.next(weekly,at("2026-11-23","10:00")))
        assertEquals(at("2026-11-29","22:00"),RecapSchedule.next(weekly,at("2026-11-29","21:59")))
        assertEquals(at("2026-12-06","22:00"),RecapSchedule.next(weekly,at("2026-11-29","22:00")))
        assertEquals(at("2026-12-06","22:00"),RecapSchedule.next(weekly,at("2026-11-30","00:00")))
    }
    @Test fun customTimes() {
        val custom=settings(RecapFrequency.daily,22*60+30)
        assertFalse(custom.preset);assertTrue(settings(RecapFrequency.daily,20*60).preset)
        assertEquals(at("2026-11-23","22:30"),RecapSchedule.next(custom,at("2026-11-23","22:00")))
        assertEquals(LocalTime.of(22,30),custom.time)
        assertThrows(IllegalArgumentException::class.java) {RecapSettings(minuteOfDay=24*60)}
        assertThrows(IllegalArgumentException::class.java) {RecapSettings(minuteOfDay=-1)}
    }
    @Test fun keepsLocalWallTimeAcrossDaylightSaving() {
        val daily=settings(RecapFrequency.daily)
        // US daylight saving time ends on 2026-11-01: 21:00 local, now at UTC-8, not a fixed 24 hours later.
        val fall=RecapSchedule.next(daily,at("2026-10-31","22:00"))!!
        assertEquals(LocalDateTime.of(2026,11,1,21,0),fall.toLocalDateTime());assertEquals(ZoneOffset.ofHours(-8),fall.offset)
        assertEquals(Duration.ofHours(24),Duration.between(at("2026-10-31","21:00"),fall).minusHours(1))
        // It starts on 2027-03-14: 21:00 at UTC-7.
        val spring=RecapSchedule.next(daily,at("2027-03-13","22:00"))!!
        assertEquals(LocalDateTime.of(2027,3,14,21,0),spring.toLocalDateTime());assertEquals(ZoneOffset.ofHours(-7),spring.offset)
        // A custom 02:30 does not exist that night and moves forward to 03:30.
        val gap=RecapSchedule.next(settings(RecapFrequency.daily,2*60+30),at("2027-03-14","00:00"))!!
        assertEquals(LocalDateTime.of(2027,3,14,3,30),gap.toLocalDateTime())
        // A weekly recap on the Sunday the clocks change keeps its wall time.
        val weekly=RecapSchedule.next(settings(RecapFrequency.weekly),at("2026-10-30","12:00"))!!
        assertEquals(LocalDateTime.of(2026,11,1,21,0),weekly.toLocalDateTime())
    }

    // MARK: Periods

    @Test fun dailyCoversTheDayUntilTheRecapAndWeeklyCoversMondayToSunday() {
        val fire=at("2026-11-29","21:00")
        val day=RecapSchedule.period(RecapFrequency.daily,fire)
        assertEquals(at("2026-11-29","00:00").toInstant(),day.from);assertEquals(fire.toInstant(),day.until)
        val week=RecapSchedule.period(RecapFrequency.weekly,fire)
        assertEquals(LocalDate.of(2026,11,23),week.firstDay);assertEquals(DayOfWeek.MONDAY,week.firstDay.dayOfWeek)
        assertEquals(at("2026-11-23","00:00").toInstant(),week.from);assertEquals(fire.toInstant(),week.until)
    }

    // MARK: Facts and weekly aggregation

    private val home=Coordinate(37.7449,-122.4156)
    private val cafe=Coordinate(37.7618,-122.4115)
    private val park=Coordinate(37.7596,-122.4269)
    private fun visit(coordinate:Coordinate,arrived:ZonedDateTime,name:String?=null,source:VisitSource=VisitSource.automatic)=
        Visit(coordinate=coordinate,arrivedAt=arrived.toInstant(),departedAt=arrived.plusMinutes(30).toInstant(),name=name,source=source)

    @Test fun factsCountPlacesLikeTodayAndListNamesInArrivalOrder() {
        val period=RecapSchedule.period(RecapFrequency.daily,at("2026-11-23","21:00"))
        val visits=listOf(visit(cafe,at("2026-11-23","09:00"),"Corner café"),visit(home,at("2026-11-23","07:00"),"Home"),
            visit(cafe,at("2026-11-23","15:00"),"Corner café"),visit(park,at("2026-11-23","18:00")))
        val facts=RecapFacts.of(period,visits,emptyList(),5_600.0,40,0)
        assertEquals(3,facts.placeCount);assertEquals(listOf("Home","Corner café"),facts.names)
        assertTrue(facts.recorded);assertTrue(facts.tracked);assertTrue(facts.newPlace)
    }
    @Test fun newPlaceMeansNoEarlierVisitWithin150Metres() {
        val period=RecapSchedule.period(RecapFrequency.daily,at("2026-11-23","21:00"))
        val today=listOf(visit(home,at("2026-11-23","07:00"),"Home"))
        val before=listOf(visit(Coordinate(home.latitude+0.001,home.longitude),at("2026-11-20","08:00")))
        assertFalse(RecapFacts.of(period,today,before,100.0,5,0).newPlace)
        val farAway=listOf(visit(Coordinate(home.latitude+0.01,home.longitude),at("2026-11-20","08:00")))
        assertTrue(RecapFacts.of(period,today,farAway,100.0,5,0).newPlace)
        // A stay that began before the period is not a new place today.
        val overnight=listOf(visit(park,at("2026-11-22","23:00")))
        assertFalse(RecapFacts.of(period,overnight,emptyList(),100.0,5,0).newPlace)
    }
    @Test fun trackingNeedsPointsARecordingOrAnAutomaticVisit() {
        val period=RecapSchedule.period(RecapFrequency.daily,at("2026-11-23","21:00"))
        val manual=listOf(visit(home,at("2026-11-23","07:00"),"Home",VisitSource.manual))
        val handOnly=RecapFacts.of(period,manual,emptyList(),0.0,0,0)
        assertTrue(handOnly.recorded);assertFalse(handOnly.tracked)
        assertTrue(RecapFacts.of(period,manual,emptyList(),0.0,0,1).tracked)
        assertTrue(RecapFacts.of(period,manual,emptyList(),0.0,3,0).tracked)
        val nothing=RecapFacts.of(period,emptyList(),emptyList(),0.0,0,0)
        assertFalse(nothing.recorded);assertFalse(nothing.tracked);assertEquals(0,nothing.placeCount)
        assertEquals(0.0,RecapFacts.of(period,emptyList(),emptyList(),Double.NaN,0,0).distanceMeters,0.0)
    }
    @Test fun weeklyCountriesComeFromCountryDaysWithinTheWeek() {
        val week=RecapSchedule.period(RecapFrequency.weekly,at("2026-11-29","21:00"))
        val presences=listOf(CountryPresence("PT",LocalDate.of(2026,11,23),true,false),CountryPresence("ES",LocalDate.of(2026,11,25),true,false),
            CountryPresence("PT",LocalDate.of(2026,11,26),true,false),CountryPresence("FR",LocalDate.of(2026,11,22),true,false),CountryPresence("FR",LocalDate.of(2026,11,30),true,false))
        assertEquals(2,RecapFacts.countries(presences,week))
        assertNull(RecapFacts.countries(emptyList(),week))
        assertNull(RecapFacts.countries(presences.filter {it.countryCode=="FR"},week))
    }

    // MARK: Skip rules

    private val fire=at("2026-11-23","21:00")
    private val busy=RecapFacts(4,listOf("Home"),5_600.0,null,recorded=true,tracked=true,newPlace=true)
    private fun skip(facts:RecapFacts=busy,settings:RecapSettings=settings(RecapFrequency.daily),access:Boolean=true,foreground:Instant?=null,at:ZonedDateTime=fire)=
        RecapRules.skip(settings,at,facts,access,foreground)

    @Test fun anOrdinaryDayIsSent() {assertNull(skip())}
    @Test fun skipsWhenOffOrNotAChosenDay() {
        assertEquals(RecapSkip.off,skip(settings=settings(RecapFrequency.off)))
        assertEquals(RecapSkip.off,skip(settings=settings(RecapFrequency.weekly)))
        assertNull(skip(settings=settings(RecapFrequency.weekly),at=at("2026-11-29","21:00")))
        assertEquals(RecapSkip.off,skip(settings=settings(RecapFrequency.weekdays),at=at("2026-11-28","21:00")))
        // The QA trigger runs a weekly recap on a Monday, but never one that is off.
        assertNull(RecapRules.skip(settings(RecapFrequency.weekly),fire,busy,true,null,anyDay=true))
        assertEquals(RecapSkip.off,RecapRules.skip(settings(RecapFrequency.off),fire,busy,true,null,anyDay=true))
    }
    @Test fun skipsWithoutAccess() {assertEquals(RecapSkip.noAccess,skip(access=false))}
    @Test fun skipsWhenNothingWasRecorded() {
        assertEquals(RecapSkip.nothingRecorded,skip(RecapFacts(0,emptyList(),0.0,null,recorded=false,tracked=false,newPlace=false)))
        // Checked before the quiet-day rule, even with it off.
        assertEquals(RecapSkip.nothingRecorded,skip(RecapFacts(0,emptyList(),0.0,null,recorded=false,tracked=true,newPlace=false),settings(RecapFrequency.daily,quiet=false)))
    }
    @Test fun skipsWhenTrackingWasOffAllPeriod() {
        assertEquals(RecapSkip.trackingOff,skip(busy.copy(tracked=false)))
    }
    @Test fun skipsWhenTheAppWasOpenThatEvening() {
        assertEquals(RecapSkip.openedThisEvening,skip(foreground=at("2026-11-23","18:00").toInstant()))
        assertEquals(RecapSkip.openedThisEvening,skip(foreground=at("2026-11-23","20:59").toInstant()))
        assertNull(skip(foreground=at("2026-11-23","17:59").toInstant()))
        assertNull(skip(foreground=at("2026-11-22","19:00").toInstant()))
        // Opening it after the recap time (a late run) does not cancel it.
        assertNull(skip(foreground=at("2026-11-23","21:00").toInstant()))
        // A recap set before 18:00 has no evening to check.
        assertNull(skip(settings=settings(RecapFrequency.daily,17*60),at=at("2026-11-23","17:00"),foreground=at("2026-11-23","16:30").toInstant()))
    }
    @Test fun quietDaysAreSkippedOnlyWithTheSettingOn() {
        val quiet=busy.copy(newPlace=false,distanceMeters=999.0)
        assertTrue(RecapRules.quiet(quiet))
        assertEquals(RecapSkip.quiet,skip(quiet))
        assertNull(skip(quiet,settings(RecapFrequency.daily,quiet=false)))
        // A new place or 1 km of movement makes the day not quiet.
        assertFalse(RecapRules.quiet(quiet.copy(newPlace=true)));assertNull(skip(quiet.copy(newPlace=true)))
        assertFalse(RecapRules.quiet(quiet.copy(distanceMeters=1_000.0)));assertNull(skip(quiet.copy(distanceMeters=1_000.0)))
    }

    // MARK: Content

    @Test fun countsOnlyByDefault() {
        val content=RecapContent.of(busy.copy(names=listOf("Home","Café","Park","Gym")),weekly=false,placeNames=false)
        assertFalse(content.named);assertEquals(4,content.placeCount);assertEquals(0,content.more);assertNull(content.countries)
    }
    @Test fun atMostThreeNamesThenMore() {
        val four=RecapContent.of(busy.copy(placeCount=5,names=listOf("Home","Café","Park","Gym")),weekly=false,placeNames=true)
        assertEquals(listOf("Home","Café","Park"),four.names);assertEquals(2,four.more)
        val three=RecapContent.of(busy.copy(placeCount=3,names=listOf("Home","Café","Park")),weekly=false,placeNames=true)
        assertEquals(3,three.names.size);assertEquals(0,three.more)
        // Unnamed places are counted in "more".
        val mixed=RecapContent.of(busy.copy(placeCount=3,names=listOf("Home")),weekly=false,placeNames=true)
        assertEquals(listOf("Home"),mixed.names);assertEquals(2,mixed.more)
        // With names on but none to show, counts are used.
        assertFalse(RecapContent.of(busy.copy(names=emptyList()),weekly=false,placeNames=true).named)
    }
    @Test fun countriesOnlyForAWeekWithMoreThanOne() {
        assertEquals(2,RecapContent.of(busy.copy(countries=2),weekly=true,placeNames=false).countries)
        assertNull(RecapContent.of(busy.copy(countries=1),weekly=true,placeNames=false).countries)
        assertNull(RecapContent.of(busy.copy(countries=null),weekly=true,placeNames=false).countries)
        assertNull(RecapContent.of(busy.copy(countries=3),weekly=false,placeNames=false).countries)
    }

    // MARK: Migration and onboarding

    @Test fun migrationAndToggle() {
        assertEquals(RecapFrequency.daily,RecapFrequency.migrated(null,legacyPagePending=true))
        assertEquals(RecapFrequency.off,RecapFrequency.migrated(null,legacyPagePending=false))
        // A stored choice always wins.
        assertEquals(RecapFrequency.weekly,RecapFrequency.migrated(RecapFrequency.weekly,legacyPagePending=true))
        assertEquals(RecapFrequency.off,RecapFrequency.migrated(RecapFrequency.off,legacyPagePending=true))
        assertEquals(RecapFrequency.daily,RecapFrequency.fromToggle(true));assertEquals(RecapFrequency.off,RecapFrequency.fromToggle(false))
        assertEquals(listOf("off","daily","weekdays","weekly"),RecapFrequency.entries.map {it.key})
        RecapFrequency.entries.forEach {assertEquals(it,RecapFrequency.fromKey(it.key))};assertNull(RecapFrequency.fromKey("nightly"))
    }
}
