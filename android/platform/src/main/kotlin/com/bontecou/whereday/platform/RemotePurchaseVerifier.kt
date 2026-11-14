package com.bontecou.whereday.platform

import android.content.Context
import com.bontecou.whereday.billing.*
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.*
import com.google.gson.*
import kotlinx.coroutines.*
import java.io.*
import java.net.URI
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PurchaseConfiguration(val productId:String=BuildConfig.PURCHASE_PRODUCT_ID,val endpoint:String=BuildConfig.PURCHASE_VERIFIER_URL,val publicKey:String=BuildConfig.PURCHASE_VERIFIER_PUBLIC_KEY,val cloudProjectNumber:Long=BuildConfig.PLAY_CLOUD_PROJECT_NUMBER) {
    val configured:Boolean get()=runCatching {
        val uri=URI(endpoint)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.query==null && uri.fragment==null)
        require(productId.matches(Regex("[a-z0-9_.]{1,200}")) && cloudProjectNumber>0)
        val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))) as java.security.interfaces.RSAPublicKey
        require(key.modulus.bitLength()>=2048)
    }.isSuccess
}
class RemotePurchaseVerifier(context:Context,private val configuration:PurchaseConfiguration,private val identity:PurchaseIdentity):PurchaseVerifier {
    private val packageName=context.packageName
    private val integrity=IntegrityManagerFactory.createStandard(context.applicationContext)
    private var provider:StandardIntegrityTokenProvider?=null
    override suspend fun verify(purchase:StorePurchase):PurchaseVerification=withContext(Dispatchers.IO) {
        check(configuration.configured && purchase.productId==configuration.productId && purchase.state==StorePurchaseState.purchased)
        val publicKey=identity.publicKey()
        val challenge=post("v1/challenges",JsonObject().apply { addProperty("publicKey",publicKey);addProperty("packageName",packageName) })
        val challengeId=challenge["challengeId"].asString
        val nonce=challenge["nonce"].asString
        val message=verificationMessage(challengeId,nonce,packageName,purchase.productId,sha256(purchase.token.toByteArray()),identity.hash())
        val prepared=provider ?: withTimeout(60_000) { integrity.prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(configuration.cloudProjectNumber).build()).awaitResult() }.also { provider=it }
        val requestHash=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(message))
        val attestation=try { withTimeout(60_000) { prepared.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).awaitResult().token() } }
            catch(e:Exception) { provider=null;throw e }
        val response=post("v1/verify",JsonObject().apply {
            addProperty("challengeId",challengeId);addProperty("publicKey",publicKey);addProperty("packageName",packageName)
            addProperty("productId",purchase.productId);addProperty("purchaseToken",purchase.token)
            addProperty("signature",identity.sign(message));addProperty("integrityToken",attestation)
        })
        when(response["status"]?.asString) {
            "active" -> PurchaseVerification.Active(response["lease"].asString)
            "pending" -> PurchaseVerification.Pending
            "revoked" -> PurchaseVerification.Revoked
            else -> throw IOException("Purchase verification unavailable")
        }
    }
    private suspend fun post(path:String,body:JsonObject):JsonObject {
        currentCoroutineContext().ensureActive()
        val url=URI(configuration.endpoint.trimEnd('/')+"/$path").toURL()
        val connection=url.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod="POST";connection.instanceFollowRedirects=false;connection.connectTimeout=10_000;connection.readTimeout=20_000;connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            currentCoroutineContext().ensureActive()
            if(connection.responseCode!=200) throw IOException("Purchase verification unavailable")
            val bytes=connection.inputStream.use { input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val count=input.read(buffer);if(count<0) break
                    require(output.size()+count<=128*1024)
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
            currentCoroutineContext().ensureActive()
            return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        } finally { connection.disconnect() }
    }
}
private suspend fun <T> Task<T>.awaitResult():T=suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if(continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if(continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
