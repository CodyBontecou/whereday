package com.bontecou.whereday.export

import com.bontecou.whereday.domain.WebhookDeliveryRules
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

enum class WebhookValidationFailure(val diagnostic:String) {
    url("Enter a valid webhook URL."),
    https("Use HTTPS, or explicitly allow unencrypted HTTP."),
    endpoint("Enter a URL without embedded credentials or a fragment."),
    authenticationValue("Enter a valid authentication value without control characters."),
    apiKey("Enter an API key name and value."),
    basicValue("Enter a valid Basic username and password."),
    basicRequired("Enter a Basic username or password."),
    headerName("Choose a valid authentication header name."),
    urlLength("Webhook URL is too long.")
}
class WebhookValidationException(val reason:WebhookValidationFailure):IllegalArgumentException(reason.diagnostic)

enum class WebhookAuth { none, apiKeyQuery, bearer, basic, customHeader }
enum class WebhookMode { realtime, batchCount, batchTime, manual }
data class WebhookCredentials(val key:String="api_key",val value:String="",val username:String="") {
    init {require(key.length<=256 && value.length<=8192 && username.length<=1024) {"Webhook credentials are too long."}}
    override fun toString()="WebhookCredentials([redacted])"
}
data class WebhookSettings(val enabled:Boolean=false,val endpoint:String="",val format:ExportFormat=ExportFormat.json,
    val auth:WebhookAuth=WebhookAuth.none,val mode:WebhookMode=WebhookMode.realtime,val batchCount:Int=10,val batchMinutes:Int=5,
    val allowInsecureHttp:Boolean=false) {
    init {require(endpoint.length<=16384);require(batchCount in setOf(5,10,25,50,100));require(batchMinutes in setOf(1,5,15,30,60))}
    override fun toString()="WebhookSettings(enabled=$enabled,format=$format,auth=$auth,mode=$mode,endpoint=[redacted])"
}
/** Treat as a capability: URL and headers can contain secrets. Never log or display them. */
class WebhookRequest internal constructor(val url:String,val headers:Map<String,String>) {
    override fun toString()="WebhookRequest([redacted])"
}
object WebhookProtocol {
    private val token=Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val reserved=setOf("host","content-length","content-type","connection","transfer-encoding","user-agent","idempotency-key","proxy-authorization","te","trailer","upgrade","expect")
    fun request(settings:WebhookSettings,credentials:WebhookCredentials,idempotencyKey:String,userAgent:String="Whereday/Android"):WebhookRequest {
        require(idempotencyKey.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {"Invalid webhook delivery identity."}
        require(userAgent.isNotBlank() && headerValue(userAgent)) {"Invalid webhook client identity."}
        val text=settings.endpoint.trim()
        val uri=try {URI(text)} catch(_:Exception) {throw WebhookValidationException(WebhookValidationFailure.url)}
        val scheme=uri.scheme?.lowercase(Locale.ROOT)
        validate(scheme=="https" || (scheme=="http" && settings.allowInsecureHttp),WebhookValidationFailure.https)
        validate(!uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.rawFragment==null && (uri.port==-1 || uri.port in 1..65535),WebhookValidationFailure.endpoint)
        var url=uri.toASCIIString()
        val headers=linkedMapOf("Content-Type" to settings.format.mime,"User-Agent" to userAgent,"Idempotency-Key" to idempotencyKey,"Cache-Control" to "no-store")
        fun value() {validate(credentials.value.isNotEmpty() && headerValue(credentials.value),WebhookValidationFailure.authenticationValue)}
        when(settings.auth) {
            WebhookAuth.none->Unit
            WebhookAuth.apiKeyQuery->{
                validate(credentials.key.isNotBlank() && credentials.value.isNotEmpty(),WebhookValidationFailure.apiKey)
                fun encode(value:String)=URLEncoder.encode(value,StandardCharsets.UTF_8.name()).replace("+","%20")
                url+=(if(uri.rawQuery==null) "?" else if(uri.rawQuery.isEmpty()) "" else "&")+encode(credentials.key)+"="+encode(credentials.value)
            }
            WebhookAuth.bearer->{value();headers["Authorization"]="Bearer ${credentials.value}"}
            WebhookAuth.basic->{
                validate(':' !in credentials.username && credentials.username.none {it<' ' || it=='\u007f'} && credentials.value.none {it<' ' || it=='\u007f'},WebhookValidationFailure.basicValue)
                validate(credentials.username.isNotEmpty() || credentials.value.isNotEmpty(),WebhookValidationFailure.basicRequired)
                headers["Authorization"]="Basic "+Base64.getEncoder().encodeToString("${credentials.username}:${credentials.value}".toByteArray(Charsets.UTF_8))
            }
            WebhookAuth.customHeader->{
                validate(token.matches(credentials.key) && credentials.key.lowercase(Locale.ROOT) !in reserved,WebhookValidationFailure.headerName)
                value();headers[credentials.key]=credentials.value
            }
        }
        validate(url.length<=32768,WebhookValidationFailure.urlLength)
        return WebhookRequest(url,headers.toMap())
    }
    private fun validate(accepted:Boolean,reason:WebhookValidationFailure) {if(!accepted) throw WebhookValidationException(reason)}
    private fun headerValue(value:String)=value.all {it.code in 32..126}
}

data class WebhookHttpResult(val status:Int,val retryAfter:String?=null) {
    init {require(status in 0..999)}
    val successful get()=status in 200..299
    override fun toString()="WebhookHttpResult(status=$status)"
}
sealed interface WebhookRetry {
    data object Complete:WebhookRetry
    data class After(val at:Instant):WebhookRetry
    data object Stopped:WebhookRetry
}
object WebhookRetryPolicy {
    const val maxAttempts=WebhookDeliveryRules.maxAttempts
    fun decide(attempt:Int,result:WebhookHttpResult,now:Instant):WebhookRetry {
        require(attempt>=1)
        if(result.successful) return WebhookRetry.Complete
        if(attempt>=maxAttempts || !WebhookDeliveryRules.retryable(result.status)) return WebhookRetry.Stopped
        val base=Duration.ofSeconds(if(attempt==1) 1 else 2)
        val requested=result.retryAfter?.trim()?.let {header->
            header.takeIf {it.isNotEmpty() && it.all {char->char in '0'..'9'}}?.let {(it.toLongOrNull() ?: 86400L).coerceAtMost(86400)}?.let(Duration::ofSeconds)
                ?: runCatching {Duration.between(now,ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).coerceAtLeast(Duration.ZERO).coerceAtMost(Duration.ofDays(1))}.getOrNull()
        }
        return WebhookRetry.After(now.plus(maxOf(base,requested ?: Duration.ZERO)))
    }
}
