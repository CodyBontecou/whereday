package com.bontecou.whereday.geographyassets

import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.geography.GeographicTimeZoneIndex
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.runBlocking
import net.iakovlev.timeshape.TimeZoneEngine
import net.iakovlev.timeshape.proto.Geojson
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.*
import kotlin.random.Random

class BoundaryAssetFidelityTest {
    private val root=Path.of(System.getProperty("geography.assets")).resolve("timezones")
    private fun index()=GeographicTimeZoneIndex(open={ Files.newInputStream(root.resolve(it)) })

    @Test fun everyOriginalRingAndCoordinateSurvivesRepackagingExactly() {
        var polygonId=0
        var vertices=0L
        val manifest=DataInputStream(BufferedInputStream(Files.newInputStream(root.resolve("index.bin"))))
        manifest.use { metadata ->
            assertEquals(0x57445A31,metadata.readInt()); assertEquals(GeographicTimeZoneIndex.VERSION,metadata.readUTF())
            assertEquals(64,metadata.readUTF().length)
            val count=metadata.readInt()
            TarArchiveInputStream(BufferedInputStream(ZstdInputStream(TimeZoneEngine::class.java.getResourceAsStream("/data.tar.zstd")))).use { tar ->
                while(true) {
                    val entry=tar.nextEntry ?: break
                    val feature=Geojson.Feature.parseFrom(tar.readNBytes(entry.size.toInt()))
                    val zone=feature.getProperties(0).valueString
                    val polygons=if(feature.geometry.hasPolygon()) listOf(feature.geometry.polygon) else feature.geometry.multiPolygon.coordinatesList
                    for(polygon in polygons) {
                        assertEquals(polygonId,metadata.readInt()); assertEquals(zone,metadata.readUTF())
                        val bytes=metadata.readLong()
                        val minLat=metadata.readDouble(); val minLon=metadata.readDouble(); val maxLat=metadata.readDouble(); val maxLon=metadata.readDouble()
                        val file=root.resolve("p-${polygonId++}.bin")
                        assertEquals(bytes,Files.size(file))
                        DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { generated ->
                            assertEquals(polygon.coordinatesCount,generated.readInt())
                            for(ring in polygon.coordinatesList) {
                                assertEquals(ring.coordinatesCount,generated.readInt())
                                for(point in ring.coordinatesList) {
                                    assertEquals(java.lang.Double.doubleToRawLongBits(point.lon.toDouble()),java.lang.Double.doubleToRawLongBits(generated.readFloat().toDouble()))
                                    assertEquals(java.lang.Double.doubleToRawLongBits(point.lat.toDouble()),java.lang.Double.doubleToRawLongBits(generated.readFloat().toDouble()))
                                    assertTrue(point.lat in minLat..maxLat && point.lon in minLon..maxLon)
                                    vertices++
                                }
                            }
                            assertEquals(-1,generated.read())
                        }
                    }
                }
            }
            assertEquals(count,polygonId); assertEquals(-1,metadata.read())
        }
        assertTrue("Expected worldwide source vertices",vertices>1_000_000)
        println("Verified $polygonId original polygons and $vertices exact IEEE-754 coordinate pairs")
    }

    @Test fun globalQueriesMatchOriginalEngineIncludingBordersAndLargeGeometries()=runBlocking {
        val original=TimeZoneEngine.initialize()
        val bounded=index()
        val fixtures=listOf(
            Coordinate(40.7128,-74.0060),Coordinate(33.4484,-112.074),Coordinate(18.4655,-66.1057),
            Coordinate(1.3521,103.8198),Coordinate(-33.8688,151.2093),Coordinate(55.7558,37.6173),
            Coordinate(64.2008,-149.4937),Coordinate(49.8951,-97.1384),Coordinate(-54.8019,-68.3030),
            Coordinate(31.7619,-106.4850),Coordinate(31.6904,-106.4245),Coordinate(41.9029,12.4534),
            Coordinate(0.0,180.0),Coordinate(0.0,-180.0),Coordinate(-90.0,0.0),Coordinate(90.0,0.0),
        )
        val random=Random(20261027)
        val samples=fixtures+(0 until 144).map { Coordinate(random.nextDouble(-89.0,89.0),random.nextDouble(-180.0,180.0)) }
        for(point in samples) {
            val expected=original.queryAll(point.latitude,point.longitude).map { it.id }.toSet()
            assertEquals("Global lookup at synthetic fixture $point",expected,bounded.queryAll(point))
            assertTrue(bounded.metrics.cachedGeometryBytes<=4L*1024*1024)
        }
        bounded.release()
    }
}
