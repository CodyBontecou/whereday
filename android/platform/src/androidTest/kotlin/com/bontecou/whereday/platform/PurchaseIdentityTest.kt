package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.CachedGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

class PurchaseIdentityTest {
    @Test fun deviceIdentitySurvivesNewAdapterAndProducesVerifiableSignatures() {
        val first=PurchaseIdentity()
        val publicKey=first.publicKey()
        val message="synthetic purchase challenge".toByteArray()
        val signature=Base64.getUrlDecoder().decode(first.sign(message))
        val fresh=PurchaseIdentity()
        assertEquals(publicKey,fresh.publicKey())
        assertEquals(first.hash(),fresh.hash())
        val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        fun verifies(bytes:ByteArray)=Signature.getInstance("SHA256withRSA").apply { initVerify(key);update(bytes) }.verify(signature)
        assertTrue(verifies(message))
        assertFalse(verifies("different challenge".toByteArray()))
    }

    @Test fun signedCachePersistsAndClearsInIsolatedTestApplication()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // This library test APK has its own UID/preferences. Never clear the installed journal app.
        check(context.packageName!="com.bontecou.whereday")
        val first=PurchaseGrantCache(context)
        val cached=CachedGrant("synthetic.signed.lease",Instant.ofEpochSecond(1_793_098_800))
        first.clear()
        try {
            first.save(cached)
            assertEquals(cached,PurchaseGrantCache(context).load())
            first.clear()
            assertNull(PurchaseGrantCache(context).load())
        } finally { first.clear() }
    }
}
