package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PhotoMarkerPreferencesTest {
    @Test fun dotModePersistsWithoutOverwritingImageOrOtherSettings()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val preferences=AppPreferences(context);val original=preferences.settings.first()
        try {
            for(images in listOf(false,true)) {
                preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=images,showPhotoDots=false)}
                val before=preferences.settings.first()
                preferences.update {it.copy(showPhotoDots=true)}
                assertEquals(before.copy(showPhotoDots=true),AppPreferences(context).settings.first())
                preferences.update {it.copy(showPhotoDots=false)}
                assertEquals(before,preferences.settings.first())
            }
        } finally {preferences.update {original}}
    }
}
