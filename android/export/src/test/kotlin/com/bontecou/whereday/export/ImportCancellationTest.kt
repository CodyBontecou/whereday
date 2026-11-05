package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ImportCancellationTest {
    @Test fun everySupportedParserCooperatesWithCancellationDuringLargeInput() {
        val points=(1..500).map {TrackPoint(coordinate=Coordinate(18.0,-66.0),time=Instant.EPOCH.plusSeconds(it.toLong()),accuracy=5.0)}
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            val text=HistoryExporter.render(format,emptyList(),points)
            var checks=0
            try {
                HistoryImporter.parse(text,format,checkpoint={if(++checks>=10) throw CancellationException("synthetic cancel")})
                fail("$format ignored cancellation")
            } catch(expected:CancellationException) {assertTrue(checks>=10)}
        }
    }
}
