package com.bontecou.whereday.platform

import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ImportStageCodecTest {
    @Test fun privateInstantsUseExactIntegerSecondAndNanosecondTuples() {
        for(value in listOf(Instant.EPOCH,Instant.ofEpochSecond(-1,999_999_999),
            Instant.ofEpochSecond(1,123_456_789),Instant.MIN,Instant.MAX)) {
            val encoded=ImportStageCodec.gson.toJson(value,Instant::class.java)
            val element=com.google.gson.JsonParser.parseString(encoded)
            assertTrue("Private timestamp should avoid ISO formatting: $encoded",element.isJsonArray)
            val tuple=element.asJsonArray
            assertEquals(2,tuple.size())
            assertTrue(tuple[0].asJsonPrimitive.isNumber);assertTrue(tuple[1].asJsonPrimitive.isNumber)
            assertEquals(value.epochSecond.toString(),tuple[0].asString)
            assertEquals(value.nano.toString(),tuple[1].asString)
            assertEquals(value,ImportStageCodec.gson.fromJson(encoded,Instant::class.java))
        }
        assertNull(ImportStageCodec.gson.fromJson("null",Instant::class.java))
    }
    @Test fun invalidPrivateInstantTuplesCannotNormalizeOrLosePrecision() {
        for(encoded in listOf("[]","[0]","[0,0,0]","[0,-1]","[0,1000000000]",
            "[9223372036854775807,0]","[0,0.5]","[0,1e-999]","[1.0,0]","[\"1\",0]","[true,0]")) {
            val result=runCatching {ImportStageCodec.gson.fromJson(encoded,Instant::class.java)}
            assertTrue("Invalid private timestamp accepted: $encoded",result.isFailure)
        }
    }
    @Test fun privatePointsUseExactFixedFieldTuples() {
        val value=TrackPoint(id="point-tuple",coordinate=Coordinate(18.0,-66.0),time=Instant.ofEpochSecond(-1,123456789),accuracy=1.5,
            altitude=-2.5,speed=3.5,outlier=true,countryCode="PR",timeZone="America/Puerto_Rico",countryResolutionAttemptedAt=Instant.EPOCH)
        val payload=ImportStageCodec.gson.toJson(value)
        val encoded=com.google.gson.JsonParser.parseString(payload)
        assertTrue("Private point should avoid reflective field-name scans: $payload",encoded.isJsonArray)
        val tuple=encoded.asJsonArray
        assertEquals(11,tuple.size());assertEquals(value.id,tuple[0].asString)
        assertEquals(18.0,tuple[1].asDouble,0.0);assertEquals(-66.0,tuple[2].asDouble,0.0)
        assertEquals(value,ImportStageRow(ImportRecordKind.point,value.id,payload).point())
        val unknown=value.copy(altitude=null,speed=null,countryCode=null,timeZone=null,countryResolutionAttemptedAt=null)
        val absent=com.google.gson.JsonParser.parseString(ImportStageCodec.gson.toJson(unknown)).asJsonArray
        for(index in listOf(5,6,8,9,10))assertTrue(absent[index].isJsonNull)
        assertEquals(unknown,ImportStageCodec.gson.fromJson(absent,TrackPoint::class.java))
        for(at in listOf(Instant.MIN,Instant.MAX)) {
            val extremes=value.copy(coordinate=Coordinate(-0.0,0.0),time=at,accuracy=Double.MIN_VALUE,
                altitude=Double.MAX_VALUE,speed=-0.0,countryCode="",timeZone="UTC | 北",countryResolutionAttemptedAt=at)
            assertEquals(extremes,ImportStageCodec.gson.fromJson(ImportStageCodec.gson.toJson(extremes),TrackPoint::class.java))
        }
    }
    @Test fun privatePointWriterRejectsNonfiniteNumbers() {
        val value=TrackPoint(id="nonfinite",coordinate=Coordinate(18.0,-66.0),time=Instant.EPOCH,accuracy=0.0)
        for(number in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            val badLatitude=com.google.gson.Gson().fromJson("{\"latitude\":\"$number\",\"longitude\":0}",Coordinate::class.java)
            val badLongitude=com.google.gson.Gson().fromJson("{\"latitude\":0,\"longitude\":\"$number\"}",Coordinate::class.java)
            for(bad in listOf(value.copy(accuracy=number),value.copy(altitude=number),value.copy(speed=number),
                value.copy(coordinate=badLatitude),value.copy(coordinate=badLongitude)))
                assertTrue("Nonfinite staged value serialized",runCatching {ImportStageCodec.gson.toJson(bad)}.isFailure)
        }
    }
    @Test fun malformedPrivatePointTuplesFailClosed() {
        val base=com.google.gson.JsonParser.parseString("[\"point-tuple\",18,-66,[0,0],0,null,null,false,null,null,null]").asJsonArray
        val invalid=mutableListOf(base.deepCopy().also {it.remove(10)},base.deepCopy().also {it.add(0)})
        for((index,value) in listOf(0 to com.google.gson.JsonPrimitive(42),1 to com.google.gson.JsonPrimitive("18"),
            1 to com.google.gson.JsonPrimitive(91),2 to com.google.gson.JsonPrimitive(181),3 to com.google.gson.JsonNull.INSTANCE,
            4 to com.google.gson.JsonPrimitive("NaN"),7 to com.google.gson.JsonPrimitive("false"),8 to com.google.gson.JsonPrimitive(42),
            9 to com.google.gson.JsonPrimitive(true),10 to com.google.gson.JsonArray()))invalid+=base.deepCopy().also {it.set(index,value)}
        for(tuple in invalid)assertTrue("Malformed point accepted: $tuple",runCatching {ImportStageCodec.gson.fromJson(tuple,TrackPoint::class.java)}.isFailure)
    }
    @Test fun absentOptionalValuesStayDistinctFromKnownZeroAndEmptyValues() {
        val unknown=TrackPoint(id="optional-values",coordinate=Coordinate(18.0,-66.0),time=Instant.EPOCH,accuracy=5.0)
        val knownZero=unknown.copy(altitude=0.0,speed=0.0,countryCode="",timeZone="")
        for(value in listOf(unknown,knownZero)) {
            val payload=ImportStageCodec.gson.toJson(value)
            assertEquals(value,ImportStageRow(ImportRecordKind.point,value.id,payload).point())
        }
        assertNotEquals(ImportStageCodec.hash(PointRow(unknown,null)),ImportStageCodec.hash(PointRow(knownZero,null)))
    }
    @Test fun completeMetadataAndNanosecondsSurvivePrivateStaging() {
        val at=Instant.parse("2026-10-26T13:00:00.123456789Z")
        val visit=Visit(id="visit",coordinate=Coordinate(18.0,-66.0),arrivedAt=at,departedAt=at.plusSeconds(10),
            name="Café | 北",address="One\nTwo",notes="Quote \" and \\",source=VisitSource.manual,confirmation=Confirmation.corrected,
            originalCoordinate=Coordinate(19.0,-67.0),originalName="Original",originalAddress="Original address",
            detectedCoordinate=Coordinate(20.0,-68.0),detectedName="Detected",detectedAddress="Detected address",
            confirmedAt=at.plusSeconds(1),updatedAt=at.plusSeconds(2),placeSource="userEntered",placeDistanceMeters=42.0,
            countryCode="PR",timeZone="America/Puerto_Rico",departureInferred=true,countryResolutionAttemptedAt=at.plusSeconds(3))
        val point=TrackPoint(id="point",coordinate=visit.coordinate,time=at,accuracy=1.5,altitude=-2.5,speed=3.5,outlier=true,
            countryCode="PR",timeZone="America/Puerto_Rico",countryResolutionAttemptedAt=at.plusSeconds(3))
        assertEquals(visit,ImportStageRow(ImportRecordKind.visit,visit.id,ImportStageCodec.gson.toJson(visit)).visit())
        assertEquals(point,ImportStageRow(ImportRecordKind.point,point.id,ImportStageCodec.gson.toJson(point)).point())
        assertNotEquals(ImportStageCodec.hash(PointRow(point,"session-a")),ImportStageCodec.hash(PointRow(point,"session-b")))
        assertNotEquals(ImportStageCodec.hash(VisitRow(visit)),ImportStageCodec.hash(VisitRow(visit.copy(originalAddress="Changed"))))
        assertNotEquals(ImportStageCodec.hash(VisitRow(visit)),ImportStageCodec.hash(VisitRow(visit.copy(countryResolutionAttemptedAt=null))))
        assertEquals(ImportStageCodec.hash(VisitRow(visit)),ImportStageCodec.hash(VisitRow(visit.copy())))
    }
}
