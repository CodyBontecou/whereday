package com.bontecou.whereday.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** An unreadable settings file must not fail every read, which crashed each launch. */
@RunWith(AndroidJUnit4::class)
class AppPreferencesCorruptionTest {
    @Test fun corruptSettingsFileIsReplacedWithPrivateDefaults()=runBlocking {withTimeout(20000) {
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"settings-corruption-${System.nanoTime()}")
        check(folder.mkdirs())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val file=File(folder,"settings.preferences_pb")
            file.writeBytes(byteArrayOf(0x0A,0x7F,0x01,0x02,0x03))
            val preferences=AppPreferences(PreferenceDataStoreFactory.create(corruptionHandler=settingsCorruptionHandler,scope=scope) {file})

            val recovered=preferences.settings.first()
            assertEquals(AppSettings(networkGeocoding=false,map=MapLayerSettings(online=false)),recovered)

            // The replacement is a working store: later changes persist.
            preferences.update {it.copy(appearance="dark")}
            assertEquals("dark",preferences.settings.first().appearance)
            assertFalse(preferences.settings.first().networkGeocoding)
        } finally {scope.cancel();folder.deleteRecursively()}
    }}
}
