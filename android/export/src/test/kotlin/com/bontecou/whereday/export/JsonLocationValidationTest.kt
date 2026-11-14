package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.Instant

class JsonLocationValidationTest {
    @Test fun ownTracksInvalidIntegersFailInsteadOfClampingOrEmittingNonfiniteNumbers()=runBlocking {
        val time=Instant.parse("2026-10-28T00:00:00Z")
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE,9_223_372_036_854_775_808.0)) {
            val point=TrackPoint(coordinate=Coordinate(18.0,-66.0),time=time,accuracy=value)
            try {HistoryExporter.render(ExportFormat.owntracks,emptyList(),listOf(point));fail("Invalid OwnTracks measurement $value")} catch(_:IllegalArgumentException) {} catch(_:ArithmeticException) {}
            val source=object:ExportRecordSource {
                override suspend fun visits(consume:suspend (Visit)->Unit) {}
                override suspend fun points(consume:suspend (TrackPoint)->Unit) {consume(point)}
            }
            try {StreamingHistoryExporter.write(StringWriter(),ExportFormat.owntracks,source);fail("Invalid streaming measurement $value")} catch(_:IllegalArgumentException) {} catch(_:ArithmeticException) {}
        }
    }
}
