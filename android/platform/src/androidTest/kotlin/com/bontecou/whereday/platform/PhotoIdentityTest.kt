package com.bontecou.whereday.platform

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.test.uiautomator.*
import android.content.Intent
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoIdentityTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() {check(context.packageName!="com.bontecou.whereday")}
    @After fun cleanup() {PhotoTestProvider.canonical=null}
    @Test fun unsupportedOpaqueReferencesStayDistinctAndProviderCanonicalizationIsExplicit() {
        val uri=Uri.parse("content://${context.packageName}.photo-test/opaque")
        val identity=AndroidPhotoIdentity(context.contentResolver,37,{null},{"version"})
        assertNull(identity.resolve(uri))
        PhotoTestProvider.canonical=Uri.parse("content://${context.packageName}.photo-test/canonical")
        assertEquals("provider:${PhotoTestProvider.canonical}",identity.resolve(uri))
        assertEquals(identity.resolve(uri),identity.resolve(uri.buildUpon().appendPath("alias").build()))
        PhotoTestProvider.canonical=Uri.parse("https://example.invalid/photo")
        assertNull(identity.resolve(uri))
        val failed=AndroidPhotoIdentity(context.contentResolver,37,{throw SecurityException("Synthetic denied equivalence")},{"version"})
        assertNull(failed.resolve(uri))
    }
    @Test fun ownedMediaAliasesUseConcreteVolumeVersionAndCreationGeneration():Unit=runBlocking {
        ownedImage {uri->
            val resolver=context.contentResolver
            val volume=resolver.query(uri,arrayOf(MediaStore.MediaColumns.VOLUME_NAME),null,null,null)!!.use {it.moveToFirst();it.getString(0)}
            val concrete=MediaStore.Images.Media.getContentUri(volume).buildUpon().appendPath(uri.lastPathSegment).build()
            val identity=AndroidPhotoIdentity(context)
            val expected=identity.resolve(uri)
            assertNotNull(expected);assertEquals(expected,identity.resolve(concrete))
            var version="first"
            val document=Uri.parse("content://synthetic-document/selected")
            val mapped=AndroidPhotoIdentity(resolver,37,{if(it==document) concrete else null},{version})
            assertEquals(mapped.resolve(uri),mapped.resolve(document))
            val before=mapped.resolve(uri);version="second";assertNotEquals(before,mapped.resolve(uri))
            val unavailable=AndroidPhotoIdentity(resolver,37,{null},{null});assertNull(unavailable.resolve(uri))
            val metadata=AndroidPhotoSource(context).metadata(uri.toString())
            assertEquals(uri.toString(),metadata.uri);assertEquals(expected,metadata.identity)
        }
    }
    @Test fun ownedMediaDocumentConversionHasTheSameIdentity():Unit=runBlocking {
        ownedImage {uri->
            val document=MediaStore.getDocumentUri(context,uri)
            assertNotNull(document)
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val result=kotlinx.coroutines.CompletableDeferred<Uri?>()
            PhotoIdentityActivity.result=result
            val activity=instrumentation.startActivitySync(Intent(context,PhotoIdentityActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra("initial",document)
            }) as PhotoIdentityActivity
            var selected:Uri?=null
            try {
                val name=resolverName(uri)
                val device=UiDevice.getInstance(instrumentation)
                val item=device.wait(Until.findObject(By.text(name)),10_000)
                assertNotNull("The exact test-owned image must be visible in the document picker",item)
                item!!.click()
                selected=withTimeout(10_000) {result.await()}
                assertNotNull("The picker must return the explicitly selected image",selected)
                val mapped=MediaStore.getMediaUri(context,selected!!)
                assertNotNull("The selected document must map to media",mapped)
                assertTrue("Mapped media URI: $mapped",AndroidPhotoIdentity.isMediaCandidate(mapped!!))
                val identity=AndroidPhotoIdentity(context)
                assertNotNull(identity.resolve(uri));assertEquals(identity.resolve(uri),identity.resolve(selected!!))
            } finally {
                instrumentation.runOnMainSync {activity.finishActivity(100);activity.releaseSelection();activity.finish()}
                selected?.let {reference->assertFalse(context.contentResolver.persistedUriPermissions.any {it.uri==reference})}
                PhotoIdentityActivity.result=null
            }
        }
    }
    private fun resolverName(uri:Uri):String=context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use {it.moveToFirst();it.getString(0)}
    private suspend fun ownedImage(block:suspend(Uri)->Unit) {
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"whereday-identity-${java.util.UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/WheredayQa")
            put(MediaStore.Images.Media.IS_PENDING,1)
        })!!
        try {
            val image=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            try {resolver.openOutputStream(uri)!!.use {assertTrue(image.compress(Bitmap.CompressFormat.JPEG,90,it))}} finally {image.recycle()}
            resolver.update(uri,ContentValues().apply {put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            block(uri)
        } finally {assertEquals(1,resolver.delete(uri,null,null))}
    }
}
