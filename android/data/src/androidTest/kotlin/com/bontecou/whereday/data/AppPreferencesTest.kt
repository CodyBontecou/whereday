package com.bontecou.whereday.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPreferencesTest {
    @Test fun concurrentUnrelatedChangesCannotReenableNetworkGeocoding()=runBlocking {
        val preferences=AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original=preferences.settings.first()
        try {
            preferences.update { it.copy(networkGeocoding=true,metric=true) }
            coroutineScope {
                launch(Dispatchers.Default) { preferences.update { it.copy(networkGeocoding=false) } }
                repeat(25) { index -> launch(Dispatchers.Default) { preferences.update { it.copy(metric=false,distanceFilter=if(index%2==0) 25 else 50) } } }
            }
            val saved=preferences.settings.first()
            assertFalse(saved.networkGeocoding); assertFalse(saved.metric)
            preferences.update { it.copy(appearance="dark") }
            assertFalse(preferences.settings.first().networkGeocoding)
        } finally { preferences.update { original } }
    }
}
