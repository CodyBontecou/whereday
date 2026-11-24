package com.bontecou.whereday.platform

import android.content.Context
import android.content.res.Configuration
import com.bontecou.whereday.domain.RecapContent
import com.bontecou.whereday.domain.RecapFacts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** The evening recap's wording from the platform resources, with synthetic counts and names only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RecapTextTest {
    private val english=localized(Locale.US)
    private fun localized(locale:Locale):Context=RuntimeEnvironment.getApplication().createConfigurationContext(Configuration().apply {setLocale(locale)})
    private fun facts(places:Int,meters:Double,names:List<String> =emptyList(),countries:Int?=null)=RecapFacts(places,names,meters,countries,recorded=true,tracked=true,newPlace=true)
    private fun daily(facts:RecapFacts,names:Boolean=false)=RecapContent.of(facts,weekly=false,placeNames=names)
    private fun weekly(facts:RecapFacts,names:Boolean=false)=RecapContent.of(facts,weekly=true,placeNames=names)

    @Test fun dailyCountsWithPlurals() {
        assertEquals("Your day",RecapText.title(english,daily(facts(4,5_600.0))))
        assertEquals("4 places and 5.6 km. Tap to see it on the map.",RecapText.body(english,daily(facts(4,5_600.0)),metric=true))
        assertEquals("1 place and 0.3 km. Tap to see it on the map.",RecapText.body(english,daily(facts(1,300.0)),metric=true))
        assertEquals("0 places and 2.4 km. Tap to see it on the map.",RecapText.body(english,daily(facts(0,2_400.0)),metric=true))
    }
    @Test fun distanceFollowsTheUnitSetting() {
        assertEquals("4 places and 3.5 mi. Tap to see it on the map.",RecapText.body(english,daily(facts(4,5_600.0)),metric=false))
        assertEquals("12 km",RecapText.distance(12_340.0,true,Locale.US))
        assertEquals("1,520 km",RecapText.distance(1_520_000.0,true,Locale.US))
        assertEquals("0.0 km",RecapText.distance(Double.NaN,true,Locale.US))
        assertEquals("5,6 km",RecapText.distance(5_600.0,true,Locale("es")))
    }
    @Test fun namesOnListAtMostThreeThenMore() {
        assertEquals("Home. 5.6 km.",RecapText.body(english,daily(facts(1,5_600.0,listOf("Home")),names=true),metric=true))
        assertEquals("Home and Corner café. 5.6 km.",RecapText.body(english,daily(facts(2,5_600.0,listOf("Home","Corner café")),names=true),metric=true))
        assertEquals("Home, Corner café and Riverside park. 5.6 km.",
            RecapText.body(english,daily(facts(3,5_600.0,listOf("Home","Corner café","Riverside park")),names=true),metric=true))
        assertEquals("Home, Corner café, Riverside park and 2 more. 5.6 km.",
            RecapText.body(english,daily(facts(5,5_600.0,listOf("Home","Corner café","Riverside park","Gym")),names=true),metric=true))
        assertEquals("Home and 1 more. 5.6 km.",RecapText.body(english,daily(facts(2,5_600.0,listOf("Home")),names=true),metric=true))
        // Names off, or no named place: counts only.
        assertEquals("2 places and 5.6 km. Tap to see it on the map.",RecapText.body(english,daily(facts(2,5_600.0,listOf("Home")),names=false),metric=true))
        assertEquals("2 places and 5.6 km. Tap to see it on the map.",RecapText.body(english,daily(facts(2,5_600.0),names=true),metric=true))
    }
    @Test fun weeklyWithAndWithoutCountries() {
        assertEquals("Your week",RecapText.title(english,weekly(facts(11,1_520_000.0))))
        assertEquals("11 places, 1,520 km and 2 countries. Tap to see the week.",RecapText.body(english,weekly(facts(11,1_520_000.0,countries=2)),metric=true))
        assertEquals("11 places and 1,520 km. Tap to see the week.",RecapText.body(english,weekly(facts(11,1_520_000.0,countries=1)),metric=true))
        assertEquals("11 places and 1,520 km. Tap to see the week.",RecapText.body(english,weekly(facts(11,1_520_000.0,countries=null)),metric=true))
        assertEquals("Lisbon, Alfama, Belém and 8 more. 1,520 km across 2 countries.",
            RecapText.body(english,weekly(facts(11,1_520_000.0,listOf("Lisbon","Alfama","Belém","Sintra"),countries=2),names=true),metric=true))
        assertEquals("Lisbon. 9.0 km.",RecapText.body(english,weekly(facts(1,9_000.0,listOf("Lisbon"),countries=1),names=true),metric=true))
    }
    @Test fun spanishUsesItsOwnWordsPluralsAndNumbers() {
        val spanish=localized(Locale("es"))
        assertEquals("Tu día",RecapText.title(spanish,daily(facts(4,5_600.0))))
        assertEquals("4 lugares y 5,6 km. Toca para verlo en el mapa.",RecapText.body(spanish,daily(facts(4,5_600.0)),metric=true))
        assertEquals("1 lugar y 5,6 km. Toca para verlo en el mapa.",RecapText.body(spanish,daily(facts(1,5_600.0)),metric=true))
        assertEquals("1.520 lugares, 1.520 km y 2 países. Toca para ver la semana.",RecapText.body(spanish,weekly(facts(1_520,1_520_000.0,countries=2)),metric=true))
        assertEquals("Casa, Café y 1 más. 5,6 km.",RecapText.body(spanish,daily(facts(3,5_600.0,listOf("Casa","Café")),names=true),metric=true))
        assertEquals("Resumen de la noche",spanish.getString(R.string.recap_channel))
    }
}
