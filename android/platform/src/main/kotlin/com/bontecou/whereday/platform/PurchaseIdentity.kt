package com.bontecou.whereday.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bontecou.whereday.billing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.*
import java.time.Instant
import java.util.Base64

class PurchaseIdentity {
    private val lock=Any()
    private fun key():KeyStore.PrivateKeyEntry=synchronized(lock) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
        }.generateKeyPair()
        store.getEntry(ALIAS,null) as KeyStore.PrivateKeyEntry
    }
    fun publicKey():String=Base64.getEncoder().encodeToString(key().certificate.publicKey.encoded)
    fun hash():String=sha256(key().certificate.publicKey.encoded)
    fun sign(message:ByteArray):String=Base64.getUrlEncoder().withoutPadding().encodeToString(Signature.getInstance("SHA256withRSA").apply {
        initSign(key().privateKey); update(message)
    }.sign())
    companion object { private const val ALIAS="whereday_purchase_v1" }
}

class PurchaseGrantCache(context:Context):GrantCache {
    private val preferences=context.applicationContext.getSharedPreferences("purchase_access",Context.MODE_PRIVATE)
    override suspend fun load():CachedGrant?=withContext(Dispatchers.IO) {
        preferences.getString("signed_lease",null)?.let { CachedGrant(it,Instant.ofEpochSecond(preferences.getLong("last_seen",0))) }
    }
    override suspend fun save(value:CachedGrant)=withContext(Dispatchers.IO) {
        check(preferences.edit().putString("signed_lease",value.signedLease).putLong("last_seen",value.lastSeen.epochSecond).commit()) { "Purchase cache could not be saved" }
    }
    override suspend fun clear()=withContext(Dispatchers.IO) { check(preferences.edit().clear().commit()) { "Purchase cache could not be cleared" } }
}

/** Plan history survives a lapse and a lease clear; it holds no purchase token, only its hash, and never grants access. */
internal class PlanHistoryStore(context:Context) {
    private val preferences=context.applicationContext.getSharedPreferences("plan_history",Context.MODE_PRIVATE)
    fun load()=PlanHistory(preferences.getBoolean("had_subscription",false),
        preferences.getString("checkout_plan",null)?.let { name -> StorePlan.entries.firstOrNull { it.name==name } },
        preferences.getString("checkout_purchase_hash",null))
    fun save(value:PlanHistory) {
        preferences.edit().putBoolean("had_subscription",value.hadSubscription).putString("checkout_plan",value.checkoutPlan?.name)
            .putString("checkout_purchase_hash",value.checkoutPurchaseHash).apply()
    }
}
