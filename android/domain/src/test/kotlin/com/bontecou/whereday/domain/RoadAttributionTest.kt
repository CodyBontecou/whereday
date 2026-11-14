package com.bontecou.whereday.domain

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoadAttributionTest {
    private val credits=listOf(RoadAttribution("Geoapify","https://www.geoapify.com/"),RoadAttribution("OpenStreetMap","https://www.openstreetmap.org/copyright",RoadAttributionKind.data))
    private val points=(0..2).map {TrackPoint("p$it",Coordinate(0.0,it*.01),Instant.EPOCH.plusSeconds(it*600L),5.0)}
    @Test fun creditLinksAreBoundedHttpsWithoutEmbeddedCredentials() {
        for((name,url) in listOf("" to "https://example.test", "x".repeat(81) to "https://example.test", "bad\nname" to "https://example.test", "Source" to "http://example.test", "Source" to "https://user:synthetic@example.test", "Source" to "not a URL", "Source" to ("https://example.test/"+"x".repeat(2048)))) {
            try {RoadAttribution(name,url);fail("Invalid credit accepted")} catch(_:IllegalArgumentException) {}
        }
        try {RoadDirectionsResult(1000.0,points.map {it.coordinate},List(9) {credits.first()});fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun acceptedGapsRetainTheirCreditsOnce():Unit=runBlocking {
        val route=RoadSnapping.build(points,RoadDirections {a,b,_->RoadDirectionsResult(a.distanceTo(b)*1.1,listOf(a,b),credits)})
        assertTrue(route.hasSnappedSegments);assertEquals(credits,route.attributions)
    }
    @Test fun rejectedProviderGeometryDoesNotPublishItsCredits():Unit=runBlocking {
        val route=RoadSnapping.build(points,RoadDirections {a,b,_->RoadDirectionsResult(a.distanceTo(b)*20,listOf(a,b),credits)})
        assertFalse(route.hasSnappedSegments);assertTrue(route.attributions.isEmpty())
    }
    @Test fun cachedCreditsDoNotAliasTheProvidersMutableListAndExpireWithConsent():Unit=runBlocking {
        val mutable=credits.toMutableList();var calls=0
        val service=RoadDisplayService(RoadDirections {a,b,_->calls++;RoadDirectionsResult(a.distanceTo(b)*1.1,listOf(a,b),mutable)},minimumRequestIntervalMillis=0)
        service.updateAccess(RoadDisplayAccess(true,true,true,true,true))
        val first=service.prepare(points);mutable.clear()
        assertEquals(credits,first.route.attributions)
        val cached=service.prepare(points);assertTrue(cached.fromCache);assertEquals(credits,cached.route.attributions);assertEquals(2,calls)
        service.updateAccess(RoadDisplayAccess())
        assertFalse(service.isCurrent(cached,cached.route.fingerprint))
        try {service.prepare(points);fail()} catch(_:RoadDisplayUnavailable) {}
    }
}
