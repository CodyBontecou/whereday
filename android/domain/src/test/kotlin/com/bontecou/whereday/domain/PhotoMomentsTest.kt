package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PhotoMomentsTest {
    private val now=Instant.parse("2026-10-27T14:00:00Z")
    private val location=Coordinate(48.8584,2.2945)
    private fun point(seconds:Long)=TrackPoint(id="point-$seconds",coordinate=Coordinate(18.0+seconds*0.000001,-66.0),time=now.plusSeconds(seconds),accuracy=5.0)
    @Test fun gpsWinsAndMissingCaptureTimeDoesNotInventACorrelation() {
        val asset=PhotoAssetMetadata("content://synthetic/photo",now,location)
        assertEquals(PhotoMoment(asset.uri,now,location,PhotoCoordinateSource.photoGPS),PhotoCorrelation.resolve(asset,point(-1),point(1),null,now))
        assertNull(PhotoCorrelation.resolve(asset.copy(takenAt=null),point(0),null,null,now))
    }
    @Test fun nearestFixUsesEarlierTieAndInclusiveFifteenMinuteWindow() {
        val asset=PhotoAssetMetadata("content://synthetic/photo",now)
        assertEquals(point(-900).coordinate,PhotoCorrelation.resolve(asset,point(-900),point(900),null,now)!!.coordinate)
        assertEquals(point(2).coordinate,PhotoCorrelation.resolve(asset,point(-3),point(2),null,now)!!.coordinate)
        assertNull(PhotoCorrelation.resolve(asset,point(-901),point(901),null,now))
    }
    @Test fun visitFallbackHasGraceAndOpenVisitsEndAtReferenceTime() {
        val asset=PhotoAssetMetadata("content://synthetic/photo",now)
        val visit=Visit(coordinate=location,arrivedAt=now.minusSeconds(100),departedAt=null)
        assertEquals(PhotoCoordinateSource.visit,PhotoCorrelation.resolve(asset,point(-901),null,visit,now)!!.source)
        assertNotNull(PhotoCorrelation.resolve(asset.copy(takenAt=now.minusSeconds(1000)),null,null,visit,now))
        assertNull(PhotoCorrelation.resolve(asset.copy(takenAt=now.minusSeconds(1001)),null,null,visit,now))
        assertNotNull(PhotoCorrelation.resolve(asset.copy(takenAt=now.plusSeconds(900)),null,null,visit,now))
        assertNull(PhotoCorrelation.resolve(asset.copy(takenAt=now.plusSeconds(901)),null,null,visit,now))
    }
    @Test fun displaySamplingRetainsEndpointsAndDoesNotCapTheTotal() {
        val sampler=PhotoMomentSampler(10001)
        repeat(10001) {sampler.add(PhotoMoment("photo-$it",now.plusMillis(it.toLong()),location,PhotoCoordinateSource.photoGPS))}
        val photos=sampler.photos()
        assertEquals(500,photos.size);assertEquals("photo-0",photos.first().uri);assertEquals("photo-10000",photos.last().uri)
        assertEquals(500,photos.map {it.uri}.distinct().size)
        assertTrue(PhotoMomentSampler(0).photos().isEmpty())
    }
}
