package com.bontecou.whereday.billing

import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets.UTF_8
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.time.*
import java.util.Base64

enum class Capability { recording, historyImport, preview, fileExport, scheduledExport, webhook }
data class VerifiedGrant(val id: String,val product: String,val deviceKeyHash: String,val purchaseTokenHash: String,val issuedAt: Instant,val refreshAfter: Instant,val expiresAt: Instant) {
    fun allows(now: Instant,lastSeen: Instant)=now>=issuedAt.minusSeconds(300) && now>=lastSeen.minusSeconds(300) && now<expiresAt
}
data class CachedGrant(val signedLease: String,val lastSeen: Instant)
interface GrantCache { suspend fun load(): CachedGrant?; suspend fun save(value: CachedGrant); suspend fun clear() }

/** Signed cache only: a Play client callback or editable preference never unlocks paid features. */
class LeaseVerifier(publicKeySpki: String,private val packageName: String,private val productId: String,private val deviceKeyHash: String) {
    private val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeySpki)))
    init { require((key as java.security.interfaces.RSAPublicKey).modulus.bitLength()>=2048) }
    fun verify(compact: String): VerifiedGrant {
        require(compact.length in 1..16_384)
        val pieces=compact.split('.'); require(pieces.size==3)
        val header=JsonParser.parseString(String(Base64.getUrlDecoder().decode(pieces[0]),UTF_8)).asJsonObject
        require(header["alg"]?.asString=="RS256" && header["typ"]?.asString=="JWT")
        val signature=Signature.getInstance("SHA256withRSA").apply { initVerify(key); update("${pieces[0]}.${pieces[1]}".toByteArray(UTF_8)) }
        require(signature.verify(Base64.getUrlDecoder().decode(pieces[2]))) { "Invalid purchase verification signature" }
        val claims=JsonParser.parseString(String(Base64.getUrlDecoder().decode(pieces[1]),UTF_8)).asJsonObject
        fun text(name: String)=claims[name]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: error("Missing grant field")
        fun time(name: String): Instant {
            val value=claims[name]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull() ?: error("Invalid grant time")
            return Instant.ofEpochSecond(value)
        }
        require(text("aud")==packageName && text("product_id")==productId && text("sub")==deviceKeyHash)
        val grant=VerifiedGrant(text("jti"),productId,deviceKeyHash,text("purchase_token_hash"),time("iat"),time("refresh_after"),time("exp"))
        require(grant.id.length in 1..128 && grant.purchaseTokenHash.matches(Regex("[0-9a-f]{64}")))
        require(grant.expiresAt>grant.issuedAt && grant.refreshAfter in grant.issuedAt..grant.expiresAt)
        require(Duration.between(grant.issuedAt,grant.expiresAt)<=Duration.ofDays(7))
        return grant
    }
}

fun sha256(bytes: ByteArray): String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Shared with the verifier service. No raw purchase token occurs in the signed/attested text. */
fun verificationMessage(challengeId: String,nonce: String,packageName: String,productId: String,purchaseTokenHash: String,publicKeyHash: String): ByteArray {
    val parts=listOf("whereday-purchase-v1",challengeId,nonce,packageName,productId,purchaseTokenHash,publicKeyHash)
    require(parts.all { it.isNotEmpty() && '\n' !in it && '\r' !in it })
    return parts.joinToString("\n").toByteArray(UTF_8)
}
