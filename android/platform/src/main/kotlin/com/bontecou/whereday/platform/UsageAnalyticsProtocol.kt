package com.bontecou.whereday.platform

import com.google.gson.*
import java.util.UUID

enum class UsageEventName(val wire:String) {
    onboardingStarted("onboarding_started"),stepViewed("onboarding_step_viewed"),permissionRequested("onboarding_location_authorization_requested"),
    permissionCompleted("onboarding_location_authorization_completed"),trackingIntentChanged("onboarding_tracking_intent_changed"),onboardingCompleted("onboarding_completed"),
    paywallShown("onboarding_paywall_shown"),purchaseStarted("onboarding_purchase_started"),purchaseFinished("onboarding_purchase_finished"),
    restoreStarted("onboarding_restore_started"),restoreFinished("onboarding_restore_finished")
}
enum class UsageStep {welcome,features,permissions,notifications,ready}
enum class UsageAuthorization(val wire:String) {notDetermined("not_determined"),authorized("authorized"),foreground("when_in_use"),always("always"),denied("denied"),restricted("restricted"),unknown("unknown")}
enum class UsageRequestKind(val wire:String) {foreground("when_in_use"),background("always"),settings("settings"),notifications("notifications")}
enum class UsageTrackingIntent(val wire:String) {startImmediately("start_immediately"),later("later"),unavailable("unavailable")}
enum class UsagePaywall {export,settings,webhook,onboarding}
enum class UsageOutcome(val wire:String) {started("started"),succeeded("succeeded"),failed("failed"),cancelled("cancelled"),pending("pending"),restored("restored"),notFound("not_found")}
enum class UsageError(val wire:String) {productUnavailable("product_unavailable"),storeUnavailable("store_unavailable"),networkUnavailable("network_unavailable"),userCancelled("user_cancelled"),verificationFailed("verification_failed"),paymentPending("payment_pending"),notUnlocked("not_unlocked"),unknown("unknown")}
enum class UsageProduct(val wire:String) {individualLifetime("whereday_export_lifetime")}

/** No general property map, user text, device metadata, timestamps or support-log entry input. */
data class UsageEvent(val name:UsageEventName,val step:UsageStep?=null,val authorization:UsageAuthorization?=null,
    val request:UsageRequestKind?=null,val tracking:UsageTrackingIntent?=null,val paywall:UsagePaywall?=null,
    val product:UsageProduct?=null,val outcome:UsageOutcome?=null,val error:UsageError?=null) {
    init {require(authorization!=UsageAuthorization.authorized || request==UsageRequestKind.notifications)}
}
data class UsageMetadata(val version:String,val build:String) {
    init {require(version.length<=24 && version.matches(Regex("[0-9]+(?:\\.[0-9]+){0,3}")));require(build.matches(Regex("[0-9]{1,12}")))}
}
data class UsagePending internal constructor(val id:UUID,val event:UsageEvent,val metadata:UsageMetadata)
data class UsageConsentState internal constructor(val consentId:UUID?=null,val pending:List<UsagePending> = emptyList()) {
    init {require(pending.size<=50 && pending.map {it.id}.toSet().size==pending.size);require(consentId!=null || pending.isEmpty())}
}
data class UsagePacket internal constructor(val consentId:UUID,val event:UsagePending) {
    fun encoded():String=JsonObject().apply {addProperty("installId",consentId.toString());add("events",JsonArray().apply {add(UsageAnalyticsCodec.event(event))})}.toString()
    override fun toString()="UsagePacket(event=${event.event.name},identity=[redacted])"
}

internal object UsageAnalyticsCodec {
    private val gson=GsonBuilder().serializeNulls().create()
    fun event(value:UsagePending)=JsonObject().apply {
        addProperty("eventId",value.id.toString());addProperty("eventName",value.event.name.wire)
        add("properties",JsonObject().apply {
            addProperty("appVersion",value.metadata.version);addProperty("buildNumber",value.metadata.build);addProperty("platform","android")
            value.event.step?.let {addProperty("onboardingStep",it.name)};value.event.authorization?.let {addProperty("authorizationStatus",it.wire)}
            value.event.request?.let {addProperty("authorizationRequestKind",it.wire)};value.event.tracking?.let {addProperty("trackingIntent",it.wire)}
            value.event.paywall?.let {addProperty("paywallContext",it.name)};value.event.product?.let {addProperty("productId",it.wire)}
            value.event.outcome?.let {addProperty("purchaseOutcome",it.wire)};value.event.error?.let {addProperty("errorCategory",it.wire)}
        })
    }
    fun encode(value:UsageConsentState):String=gson.toJson(JsonObject().apply {
        addProperty("version",1);add("consentId",value.consentId?.let {JsonPrimitive(it.toString())} ?: JsonNull.INSTANCE)
        add("events",JsonArray().apply {value.pending.forEach {add(event(it))}})
    }).also {require(it.toByteArray(Charsets.UTF_8).size<=65_536)}
    fun decode(raw:String):UsageConsentState {
        val root=AutomationJson.objectValue(raw,maxChars=65_536)
        require(root.keySet()==setOf("version","consentId","events") && AutomationJson.integer(root,"version")==1L)
        val id=root["consentId"].takeUnless {it.isJsonNull}?.let {uuid(AutomationJson.text(root,"consentId"))}
        val events=root["events"];require(events.isJsonArray && events.asJsonArray.size()<=50)
        val entries=events.asJsonArray.map {item ->
            require(item.isJsonObject);val entry=item.asJsonObject
            require(entry.keySet()==setOf("eventId","eventName","properties"))
            val eventId=uuid(AutomationJson.text(entry,"eventId"));val name=requireNotNull(UsageEventName.entries.singleOrNull {it.wire==AutomationJson.text(entry,"eventName")})
            require(entry["properties"].isJsonObject);val p=entry["properties"].asJsonObject
            require(p.keySet().containsAll(setOf("appVersion","buildNumber","platform")))
            require(p.keySet().all {it in setOf("appVersion","buildNumber","platform","onboardingStep","authorizationStatus","authorizationRequestKind","trackingIntent","paywallContext","productId","purchaseOutcome","errorCategory")})
            require(AutomationJson.text(p,"platform")=="android")
            fun <T> option(key:String,values:List<T>,wire:(T)->String):T?=if(!p.has(key)) null else requireNotNull(values.singleOrNull {wire(it)==AutomationJson.text(p,key)})
            val event=UsageEvent(name,option("onboardingStep",UsageStep.entries){it.name},option("authorizationStatus",UsageAuthorization.entries){it.wire},
                option("authorizationRequestKind",UsageRequestKind.entries){it.wire},option("trackingIntent",UsageTrackingIntent.entries){it.wire},
                option("paywallContext",UsagePaywall.entries){it.name},option("productId",UsageProduct.entries){it.wire},
                option("purchaseOutcome",UsageOutcome.entries){it.wire},option("errorCategory",UsageError.entries){it.wire})
            UsagePending(eventId,event,UsageMetadata(AutomationJson.text(p,"appVersion"),AutomationJson.text(p,"buildNumber")))
        }
        return UsageConsentState(id,java.util.Collections.unmodifiableList(entries))
    }
    private fun uuid(raw:String)=UUID.fromString(raw).also {require(it.toString()==raw && it.version()==4)}
}
