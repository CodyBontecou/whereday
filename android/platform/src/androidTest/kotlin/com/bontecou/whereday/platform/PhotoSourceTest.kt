package com.bontecou.whereday.platform

import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PhotoSourceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file:File
    @Before fun setup() {
        check(context.packageName!="com.bontecou.whereday")
        file=File(context.cacheDir,"exports/photo-source-${java.util.UUID.randomUUID()}.jpg").apply {parentFile!!.mkdirs()}
        Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888).let {bitmap->
            file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))};bitmap.recycle()
        }
    }
    @After fun close() {PhotoTestProvider.file=null;PhotoTestProvider.captureTime=null;PhotoTestProvider.originalFile?.delete();PhotoTestProvider.originalFile=null;PhotoTestProvider.originalFailure=null;PhotoTestProvider.onOpen={};PhotoTestProvider.opened.clear();file.delete()}
    @Test fun selectedFileReadsOffsetAwareExifGpsAndBoundedOrientedThumbnail()=runBlocking {
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2026:10:27 10:00:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,"-04:00")
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,"123")
            setLatLong(48.8584,2.2945)
            setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.exports",file).toString()
        val source=AndroidPhotoSource(context)
        val metadata=source.metadata(uri)
        assertEquals(Instant.parse("2026-10-27T14:00:00.123Z"),metadata.takenAt)
        assertEquals(48.8584,metadata.gps!!.latitude,0.000001)
        assertEquals(2.2945,metadata.gps!!.longitude,0.000001)
        val image=source.thumbnail(uri,128)
        try {assertTrue(image.width<=128 && image.height<=128);assertTrue(image.height>image.width)} finally {image.recycle()}
    }
    @Test fun missingDateDoesNotUseFileCreationTimeAndNonContentReferencesAreRejected()=runBlocking {
        val source=AndroidPhotoSource(context)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.exports",file).toString()
        val value=source.metadata(uri)
        assertNull(value.takenAt);assertNull(value.gps)
        for(reference in listOf(file.toURI().toString(),"https://example.invalid/photo.jpg","content:///missing-authority")) {
            try {source.metadata(reference);fail("Expected a content URI validation failure")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun libraryCaptureTimeWinsAndRevokedOrMissingAssetsFailClosed()=runBlocking {
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2026:10:27 10:00:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,"-04:00");saveAttributes()
        }
        PhotoTestProvider.file=file
        PhotoTestProvider.captureTime=Instant.parse("2020-01-02T03:04:05Z").toEpochMilli()
        val source=AndroidPhotoSource(context)
        val base="content://${context.packageName}.photo-test"
        assertEquals(Instant.parse("2020-01-02T03:04:05Z"),source.metadata("$base/available").takenAt)
        try {source.metadata("$base/revoked");fail("Expected revoked access")} catch(_:SecurityException) {}
        try {source.metadata("$base/missing");fail("Expected missing file")} catch(_:java.io.FileNotFoundException) {}
    }
    @Test fun exifRequiresARealOffsetAndValidDateWithoutAssumingCurrentZone() {
        assertNull(photoExifInstant("2026:10:27 10:00:00",null,null))
        assertNull(photoExifInstant("2026:02:30 10:00:00","+00:00",null))
        assertNull(photoExifInstant("2026:10:27 10:00:00","not-a-zone",null))
        assertEquals(Instant.parse("2026-10-27T04:30:00Z"),photoExifInstant("2026:10:27 10:00:00","+05:30",null))
    }
    @Test fun permittedOriginalUsesGpsWhileDeniedOrRevokedAccessKeepsReadableRedactedMetadata()=runBlocking {
        val original=File(file.parentFile,"original-${java.util.UUID.randomUUID()}.jpg")
        file.copyTo(original)
        ExifInterface(original).apply {setLatLong(48.8584,2.2945);saveAttributes()}
        PhotoTestProvider.file=original;PhotoTestProvider.originalFile=original
        PhotoTestProvider.captureTime=Instant.parse("2026-10-27T14:00:00Z").toEpochMilli()
        val reference="content://${context.packageName}.photo-test/available"
        var allowed=false
        val source=AndroidPhotoSource(context.contentResolver,{allowed},{android.provider.MediaStore.setRequireOriginal(it)})
        assertNull(source.metadata(reference).gps)
        assertFalse(android.provider.MediaStore.getRequireOriginal(PhotoTestProvider.opened.last()))
        allowed=true
        val value=source.metadata(reference)
        assertEquals(reference,value.uri);assertEquals(48.8584,value.gps!!.latitude,0.000001)
        assertTrue(android.provider.MediaStore.getRequireOriginal(PhotoTestProvider.opened.last()))
        PhotoTestProvider.onOpen={allowed=false}
        assertNull(source.metadata(reference).gps)
        assertEquals(Instant.parse("2026-10-27T14:00:00Z"),source.metadata(reference).takenAt)
    }
    @Test fun originalFailuresFallBackToTheReadableImageWithoutInventingGps()=runBlocking {
        PhotoTestProvider.file=file;PhotoTestProvider.captureTime=123456789L
        val reference="content://${context.packageName}.photo-test/available"
        val source=AndroidPhotoSource(context.contentResolver,{true},{android.provider.MediaStore.setRequireOriginal(it)})
        for(failure in listOf(SecurityException("Synthetic revoked permission"),UnsupportedOperationException("Synthetic unsupported original"),java.io.FileNotFoundException("Synthetic missing original"))) {
            PhotoTestProvider.originalFailure=failure;PhotoTestProvider.opened.clear()
            val value=source.metadata(reference)
            assertEquals(Instant.ofEpochMilli(123456789L),value.takenAt);assertNull(value.gps)
            assertEquals(listOf(true,false),PhotoTestProvider.opened.map {android.provider.MediaStore.getRequireOriginal(it)})
        }
    }
    @Test fun onlyCanonicalMediaStoreImagesUseRequireOriginal() {
        val media=android.net.Uri.parse("content://media/external_primary/images/media/42")
        assertTrue(android.provider.MediaStore.getRequireOriginal(originalMediaPhotoUri(media)!!))
        for(value in listOf("content://media/picker/0/example/42","content://media/external/images/media/redacted-42","content://media/external/video/media/42","content://com.android.providers.media.documents/document/image%3A42","content://example.invalid/images/media/42")) assertNull(originalMediaPhotoUri(android.net.Uri.parse(value)))
    }

    @Test fun ownedSyntheticMediaStorePhotoUsesOriginalUriAndHonorsTheGpsGateWithoutScanning()=runBlocking {
        ExifInterface(file).apply {setLatLong(48.8584,2.2945);saveAttributes()}
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"whereday-original-qa-${java.util.UUID.randomUUID()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/WheredayQA")
            put(android.provider.MediaStore.Images.Media.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        try {
            resolver.openOutputStream(uri)!!.use {output->file.inputStream().use {it.copyTo(output)}}
            resolver.update(uri,android.content.ContentValues().apply {put(android.provider.MediaStore.Images.Media.IS_PENDING,0)},null,null)
            val denied=AndroidPhotoSource(resolver,{false}).metadata(uri.toString())
            assertNull(denied.gps)
            val allowed=AndroidPhotoSource(resolver,{true}).metadata(uri.toString())
            assertEquals(uri.toString(),allowed.uri)
            assertEquals(48.8584,allowed.gps!!.latitude,0.000001);assertEquals(2.2945,allowed.gps!!.longitude,0.000001)
        } finally {assertEquals(1,resolver.delete(uri,null,null))}
    }

}
