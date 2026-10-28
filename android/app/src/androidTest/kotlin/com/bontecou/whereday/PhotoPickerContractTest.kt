package com.bontecou.whereday

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoPickerContractTest {
    @Before fun isolate() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun locationRequestRequiresOptInAndOnlyAppliesToNativePhotoPicker() {
        val extra="android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS"
        val native=Intent(MediaStore.ACTION_PICK_IMAGES)
        requestPickerLocations(native,false);assertFalse(native.hasExtra(extra))
        requestPickerLocations(native,true);assertTrue(native.getBooleanExtra(extra,false))
        requestPickerLocations(native,false);assertFalse(native.hasExtra(extra))
        val fallback=Intent(Intent.ACTION_OPEN_DOCUMENT)
        requestPickerLocations(fallback,true);assertFalse(fallback.hasExtra(extra))
    }
    @Test fun delegationPreservesSelectionOrderAndCancellation() {
        val a=Uri.parse("content://synthetic/a");val b=Uri.parse("content://synthetic/b")
        val data=Intent().apply {clipData=ClipData.newRawUri("Synthetic fixture",a).apply {addItem(ClipData.Item(b))}}
        val contract=PhotoPickerContract {true}
        assertEquals(listOf(a,b),contract.parseResult(Activity.RESULT_OK,data))
        assertTrue(contract.parseResult(Activity.RESULT_CANCELED,data).isEmpty())
    }
}
