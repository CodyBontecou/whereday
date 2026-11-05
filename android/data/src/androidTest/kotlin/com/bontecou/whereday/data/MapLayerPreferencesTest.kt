package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** The Layers sheet's choices survive a new preferences owner and never disturb the other map settings. */
class MapLayerPreferencesTest {
    @Test fun everyLayerChoicePersistsIndependently()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val preferences=AppPreferences(context);val original=preferences.settings.first()
        try {
            preferences.update {it.copy(map=MapLayerSettings(),showOutliers=false)}
            assertEquals(MapLayerSettings(),AppPreferences(context).settings.first().map)
            val flips:List<(MapLayerSettings)->MapLayerSettings> = listOf(
                {it.copy(path=false)},{it.copy(points=true)},{it.copy(visits=false)},{it.copy(endpoints=false)},
                {it.copy(straightGaps=true)},{it.copy(liveSession=false)},{it.copy(visitSuggestions=true)},{it.copy(online=true)})
            for(flip in flips) {
                val before=preferences.settings.first()
                preferences.update {it.copy(map=flip(MapLayerSettings()))}
                val reread=AppPreferences(context).settings.first()
                assertEquals(flip(MapLayerSettings()),reread.map)
                assertEquals(before.copy(map=reread.map),reread)
            }
            // The outlier filter and road matching keep their own keys.
            preferences.update {it.copy(showOutliers=true,map=MapLayerSettings(points=false))}
            val saved=AppPreferences(context).settings.first()
            assertTrue(saved.showOutliers);assertFalse(saved.map.points)
            assertEquals(com.bontecou.whereday.domain.MapLayers(true,false,true,true,true),saved.map.layers(saved.showOutliers))
        } finally {preferences.update {original}}
    }
}
