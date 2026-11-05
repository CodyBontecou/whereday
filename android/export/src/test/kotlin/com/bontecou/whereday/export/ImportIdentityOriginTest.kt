package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.*

class ImportIdentityOriginTest {
    private val at=Instant.parse("2020-01-01T00:00:00Z")
    @Test fun anonymousIntervalIdentitiesAreDistinctAndStableAcrossFormats() {
        val visits=listOf(null,at.plusSeconds(3600),at.plusSeconds(7200)).map {end->Visit(coordinate=Coordinate(18.0,-66.0),arrivedAt=at,departedAt=end)}
        var expected:Map<Instant?,String>?=null
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            val text=HistoryExporter.render(format,visits,emptyList(),ExportOptions(zone=ZoneOffset.UTC),locale=Locale.US)
            val parsed=HistoryImporter.parse(text,format,Locale.US,ZoneOffset.UTC).visits
            assertEquals(format.name,3,parsed.map {it.id}.toSet().size)
            val identities=parsed.associate {it.departedAt to it.id}
            if(expected==null) expected=identities else assertEquals(format.name,expected,identities)
            assertEquals(identities,HistoryImporter.parse(text,format,Locale.US,ZoneOffset.UTC).visits.associate {it.departedAt to it.id})
        }
    }
    @Test fun suppliedIdentityProvenanceCannotBeConfusedWithGeneratedIds() {
        val supplied="123e4567-e89b-12d3-a456-426614174000"
        val origins=mutableListOf<Boolean>();val ids=mutableListOf<String>()
        val text="""{"points":[{"id":"$supplied","timestamp":"2020-01-01T00:00:00Z","latitude":18,"longitude":-66},{"timestamp":"2020-01-01T00:00:01Z","latitude":18,"longitude":-66}]}"""
        HistoryImporter.scanSource({text.reader()},ExportFormat.json,onPoint={ids+=it.id},onIdentity={origins+=it})
        assertEquals(listOf(true,false),origins);assertEquals(supplied,ids.first());assertNotEquals(ids.first(),ids.last())
        origins.clear();ids.clear()
        val google="""{"locations":[{"id":"$supplied","timestampMs":0,"latLng":"geo:18,-66"}]}"""
        HistoryImporter.scanSource({google.reader()},ExportFormat.json,onPoint={ids+=it.id},onIdentity={origins+=it})
        assertEquals(listOf(false),origins);assertNotEquals(supplied,ids.single())
    }
}
