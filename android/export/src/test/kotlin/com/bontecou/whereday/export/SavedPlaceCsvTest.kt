package com.bontecou.whereday.export

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test

class SavedPlaceCsvTest {
    @Test fun previewMatchesSixteenActualSwiftParserFixturesIncludingErrorsAndPhysicalLines() {
        val cases=requireNotNull(javaClass.getResourceAsStream("/ios-saved-place-csv.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        assertEquals(16,cases.size())
        val gson=GsonBuilder().serializeNulls().create()
        for(element in cases) {
            val c=element.asJsonObject
            if(c.has("error")) {
                try {SavedPlaceCsvImporter.parse(c["csv"].asString.toByteArray());fail(c["name"].asString)}
                catch(e:IllegalArgumentException) {assertEquals(c["name"].asString,c["error"].asString,e.message)}
            } else {
                val preview=SavedPlaceCsvImporter.parse(c["csv"].asString.toByteArray())
                val rows=preview.rows.map {row ->mapOf("rowNumber" to row.rowNumber,"name" to row.name,"errors" to row.errors,
                    "place" to row.place?.let {mapOf("name" to it.name,"address" to it.address,"latitude" to it.coordinate.latitude,"longitude" to it.coordinate.longitude,"radius" to it.radius)})}
                assertEquals(c["name"].asString,c["rows"],gson.toJsonTree(rows))
                assertEquals(preview.places.size,preview.places.map {it.id}.distinct().size)
            }
        }
    }
    @Test fun rejectsInvalidUtf8FileAndRecordLimitsAndSupportsCancellation() {
        for(bytes in listOf(byteArrayOf(0xff.toByte(),0xfe.toByte()),ByteArray(SavedPlaceCsvImporter.maximumFileSize+1))) {
            try {SavedPlaceCsvImporter.parse(bytes);fail()} catch(_:IllegalArgumentException) {}
        }
        val rows="name,latitude,longitude\n"+"\"Home\nOffice\",38,-9\n".repeat(10_001)
        try {SavedPlaceCsvImporter.parse(rows.toByteArray());fail()} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("10,000"))}
        try {SavedPlaceCsvImporter.parse("name,latitude,longitude\nHome,38,-9".toByteArray()) {throw java.util.concurrent.CancellationException()};fail()}
        catch(_:java.util.concurrent.CancellationException) {}
    }
    @Test fun fullTenThousandRowBoundaryRemainsAvailableForPreview() {
        val preview=SavedPlaceCsvImporter.parse(("name,latitude,longitude\n"+"Place,0,0\n".repeat(10_000)).toByteArray())
        assertEquals(10_000,preview.places.size);assertEquals(10_001,preview.rows.last().rowNumber)
    }
}
