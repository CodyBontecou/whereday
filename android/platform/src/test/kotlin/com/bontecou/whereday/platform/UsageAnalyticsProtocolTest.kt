package com.bontecou.whereday.platform

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UsageAnalyticsProtocolTest {
    private fun pending()=UsagePending(UUID.randomUUID(),UsageEvent(UsageEventName.purchaseFinished,UsageStep.notifications,
        UsageAuthorization.foreground,UsageRequestKind.notifications,UsageTrackingIntent.later,UsagePaywall.export,UsageProduct.individualLifetime,
        UsageOutcome.pending,UsageError.paymentPending),UsageMetadata("0.1.0","1"))
    @Test fun immutableProtocolRoundTripsAndNetworkEnvelopeHasOnlyWhitelistedProperties() {
        val entry=pending();val state=UsageConsentState(UUID.randomUUID(),listOf(entry))
        assertEquals(state,UsageAnalyticsCodec.decode(UsageAnalyticsCodec.encode(state)))
        val root=JsonParser.parseString(UsagePacket(requireNotNull(state.consentId),entry).encoded()).asJsonObject
        assertEquals(setOf("installId","events"),root.keySet())
        val p=root["events"].asJsonArray.single().asJsonObject["properties"].asJsonObject
        assertEquals(setOf("appVersion","buildNumber","platform","onboardingStep","authorizationStatus","authorizationRequestKind","trackingIntent","paywallContext","productId","purchaseOutcome","errorCategory"),p.keySet())
        assertEquals("android",p["platform"].asString)
        assertFalse(UsagePacket(state.consentId,entry).toString().contains(state.consentId.toString()))
    }
    @Test fun actualEncoderMatchesTheSharedReceiverFixture() {
        val entry=pending().copy(id=UUID.fromString("00000000-0000-4000-8000-000000000901"))
        val packet=UsagePacket(UUID.fromString("00000000-0000-4000-8000-000000000001"),entry)
        val fixture=requireNotNull(javaClass.getResourceAsStream("/usage-analytics-android.json")).bufferedReader().use {it.readText()}
        assertEquals(JsonParser.parseString(fixture),JsonParser.parseString(packet.encoded()))
    }
    @Test fun actualNotificationEncoderMatchesReceiverFixtureAndCannotClaimLocationAuthorization() {
        val event=UsageEvent(UsageEventName.permissionCompleted,step=UsageStep.notifications,authorization=UsageAuthorization.authorized,request=UsageRequestKind.notifications)
        val entry=UsagePending(UUID.fromString("00000000-0000-4000-8000-000000000905"),event,UsageMetadata("0.1.0","1"))
        val packet=UsagePacket(UUID.fromString("00000000-0000-4000-8000-000000000001"),entry)
        val fixture=requireNotNull(javaClass.getResourceAsStream("/usage-analytics-android-notification.json")).bufferedReader().use {it.readText()}
        assertEquals(JsonParser.parseString(fixture),JsonParser.parseString(packet.encoded()))
        assertEquals(event,UsageAnalyticsCodec.decode(UsageAnalyticsCodec.encode(UsageConsentState(packet.consentId,listOf(entry)))).pending.single().event)
        try {UsageEvent(UsageEventName.permissionCompleted,authorization=UsageAuthorization.authorized);fail("Ambiguous notification grant accepted")} catch(_:IllegalArgumentException) {}
    }
    @Test fun strictDecoderRejectsUnknownFieldsDuplicatesWrongVersionsAndDisabledQueues() {
        val state=UsageConsentState(UUID.randomUUID(),listOf(pending()));val raw=UsageAnalyticsCodec.encode(state)
        val invalid=listOf(raw.replace("\"version\":1","\"version\":2"),raw.replace("\"version\":1","\"version\":1,\"version\":1"),
            raw.replace("\"platform\":\"android\"","\"platform\":\"ios\""),raw.replace("\"platform\":\"android\"","\"platform\":\"android\",\"latitude\":1"),
            raw.replace("\"consentId\":\"${state.consentId}\"","\"consentId\":null"),raw+" trailing",raw.replace("\"onboardingStep\":\"notifications\"","\"onboardingStep\":\"private place\""))
        for(value in invalid) {try {UsageAnalyticsCodec.decode(value);fail("Invalid analytics state accepted")} catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} catch(_:com.google.gson.JsonParseException) {} catch(_:java.io.IOException) {}}
    }
    @Test fun metadataCannotContainUserTextPathsOrUnboundedVersionStrings() {
        for(value in listOf("file:///history","1.0\nsecret","1.0-beta","1".repeat(25))) {
            try {UsageMetadata(value,"1");fail("Unbounded metadata accepted")} catch(_:IllegalArgumentException) {}
        }
        try {UsageMetadata("1.0","install-id");fail("Arbitrary build accepted")} catch(_:IllegalArgumentException) {}
        assertEquals(UsageConsentState(),UsageAnalyticsCodec.decode(UsageAnalyticsCodec.encode(UsageConsentState())))
    }
}
