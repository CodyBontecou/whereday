package com.bontecou.whereday.domain

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test

class PhotoMarkerStyleTest {
    private fun JsonArray.point()=PhotoMarkerPoint(get(0).asDouble,get(1).asDouble)
    @Test fun currentSwiftStyleAndNearestDotRulesMatch() {
        val document=javaClass.getResourceAsStream("/ios-photo-markers.json")!!.reader().use {JsonParser.parseReader(it).asJsonObject}
        assertEquals(4,document.getAsJsonArray("styles").size())
        for(value in document.getAsJsonArray("styles")) {
            val case=value.asJsonObject
            assertEquals(case.get("style").asString,PhotoMarkerStyle.resolve(case.get("images").asBoolean,case.get("dots").asBoolean).name)
        }
        assertEquals(14,document.getAsJsonArray("hits").size())
        for(value in document.getAsJsonArray("hits")) {
            val case=value.asJsonObject
            val centers=case.getAsJsonArray("centers").map {if(it.isJsonNull) null else it.asJsonArray.point()}
            val expected=case.get("index").takeUnless {it.isJsonNull}?.asInt
            assertEquals(case.get("name").asString,expected,PhotoDotHitTesting.targetIndex(case.getAsJsonArray("point").point(),centers))
        }
    }
    @Test fun androidUses48dpTargetsWithStableNearestSelection() {
        val centers=listOf(PhotoMarkerPoint(100.0,100.0),PhotoMarkerPoint(130.0,100.0))
        assertEquals(1,PhotoDotHitTesting.targetIndex(PhotoMarkerPoint(121.0,100.0),centers,48.0))
        assertEquals(0,PhotoDotHitTesting.targetIndex(PhotoMarkerPoint(115.0,100.0),centers,48.0))
        assertEquals(0,PhotoDotHitTesting.targetIndex(PhotoMarkerPoint(76.0,124.0),centers,48.0))
        assertNull(PhotoDotHitTesting.targetIndex(PhotoMarkerPoint(75.999,100.0),centers,48.0))
    }
    @Test fun invalidScreenProjectionCannotBecomeATarget() {
        assertNull(PhotoDotHitTesting.targetIndex(PhotoMarkerPoint(Double.NaN,0.0),listOf(PhotoMarkerPoint(0.0,0.0))))
        assertNull(PhotoDotHitTesting.targetIndex(PhotoMarkerPoint(0.0,0.0),listOf(PhotoMarkerPoint(Double.POSITIVE_INFINITY,0.0))))
        for(size in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            try {PhotoDotHitTesting.targetIndex(PhotoMarkerPoint(0.0,0.0),emptyList(),size);fail("Invalid target size")}
            catch(_:IllegalArgumentException) {}
        }
    }
}
