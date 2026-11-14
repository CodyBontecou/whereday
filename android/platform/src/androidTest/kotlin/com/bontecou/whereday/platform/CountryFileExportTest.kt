package com.bontecou.whereday.platform

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.UUID

class CountryFileExportTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val tree=DocumentsContract.buildTreeDocumentUri("com.bontecou.whereday.export-test-documents","root")
    private val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
    private suspend fun fixture(block:suspend (HistoryDatabase,ExportFiles)->Unit) {
        check(context.packageName!="com.bontecou.whereday")
        val name="country-file-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name);val files=ExportFiles(context,db) {}
        try {
            context.grantUriPermission(context.packageName,tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            db.history().put(StayRow(CountryStay(countryCode="US",firstDay=LocalDate.parse("2020-01-01"),lastDay=LocalDate.parse("2022-12-31"))))
            db.history().put(StayRow(CountryStay(countryCode="CA",firstDay=LocalDate.parse("2020-01-01"),lastDay=LocalDate.parse("2020-01-02"))))
            db.history().putPoints((0..1001).map {i ->PointRow(TrackPoint(id="synthetic-$i",coordinate=Coordinate(18.0,-66.0),time=Instant.parse("2020-01-01T15:00:00Z"),accuracy=5.0,countryCode="US",timeZone="America/New_York"),null)})
            db.history().put(PointRow(TrackPoint(id="unresolved",coordinate=Coordinate(18.0,-66.0),time=Instant.parse("2020-01-05T15:00:00Z"),accuracy=5.0),null))
            db.history().put(PointRow(TrackPoint(id="legacy-before-epoch",coordinate=Coordinate(51.5,-0.1),time=Instant.parse("1969-12-31T12:00:00Z"),accuracy=5.0,countryCode="GB",timeZone="Europe/London"),null))
            block(db,files)
        } finally {
            files.clearCache();db.close();context.deleteDatabase(name)
            context.revokeUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            File(context.cacheDir,"synthetic-export-documents").deleteRecursively()
        }
    }
    @Test fun completeCountryFileExceedsPreviewAndMatchesProvenanceAndCivilBounds()=runBlocking {
        fixture {_,files ->
            val prepared=files.prepareCountryDays(CountryDayBounds()).files.single()
            assertEquals("iso-me-country-days.csv",prepared.relativePath)
            val bytes=prepared.file.readBytes();val text=bytes.toString(Charsets.UTF_8)
            assertTrue(text.startsWith("country_code,local_date,source\r\nCA,2020-01-01,manual\r\n"))
            assertEquals(1100,text.split("\r\n").count {it.isNotEmpty()}) // Header + 1,096 US dates + 2 CA dates + one pre-epoch GB date.
            assertTrue(text.contains("GB,1969-12-31,recorded\r\n"))
            assertTrue(text.contains("US,2020-01-01,recorded+manual\r\n"))
            assertTrue(text.endsWith("US,2022-12-31,manual\r\n"))
            assertEquals(ExportCountsForCountry,prepared.counts)
            val filtered=files.prepareCountryDays(CountryDayBounds(LocalDate.parse("2020-02-28"),LocalDate.parse("2020-03-01"))).files.single().file.readText()
            assertEquals("country_code,local_date,source\r\nUS,2020-02-28,manual\r\nUS,2020-02-29,manual\r\nUS,2020-03-01,manual\r\n",filtered)
        }
    }
    @Test fun countryPayloadUsesSameVerifiedPickerFolderAndShareDelivery()=runBlocking {
        fixture {_,files ->
            val prepared=files.prepareCountryDays(CountryDayBounds())
            val intent=files.share(prepared);assertEquals("text/csv",intent.type);assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            val request=files.beginPicker(prepared)
            val uri=DocumentsContract.createDocument(context.contentResolver,parent,"text/csv","selected.csv")!!
            assertEquals(ExportPickerPhase.completed,files.pickerResult(request.id,uri,0)!!.phase)
            val text=context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()};assertTrue(text.contains("US,2022-12-31,manual\r\n"))
            val another=files.prepareCountryDays(CountryDayBounds())
            val one=files.saveToFolder(another,tree).single();val two=files.saveToFolder(another,tree).single();assertNotEquals(one,two)
            assertEquals(another.files.single().file.readText(),context.contentResolver.openInputStream(one)!!.bufferedReader().use {it.readText()})
        }
    }
    @Test fun deniedAccessBeforeOrAfterPreparationLeavesNoCountryPayloadAndTamperedShareIsRejected()=runBlocking {
        fixture {db,files ->
            val before=File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet()
            var gates=0
            val denied=ExportFiles(context,db) {gates++;error("Verified access is required")}
            try {denied.prepareCountryDays(CountryDayBounds());fail()} catch(_:IllegalStateException) {}
            assertEquals(1,gates)
            gates=0
            val expired=ExportFiles(context,db) {if(++gates==2) error("Access expired")}
            try {expired.prepareCountryDays(CountryDayBounds());fail()} catch(_:IllegalStateException) {}
            assertEquals(2,gates);assertEquals(before,File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet())
            val prepared=files.prepareCountryDays(CountryDayBounds());val file=prepared.files.single().file
            val bytes=file.readBytes();bytes[0]='X'.code.toByte();file.writeBytes(bytes)
            try {files.share(prepared);fail()} catch(_:IllegalStateException) {}
        }
    }
    @Test fun cancellationDuringFinalAccessCheckRemovesCountryStaging()=runBlocking {
        fixture {db,_ ->
            val before=File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet()
            val task=async {
                val taskContext=currentCoroutineContext();var checks=0
                val canceled=ExportFiles(context,db) {if(++checks==2) taskContext.cancel()}
                canceled.prepareCountryDays(CountryDayBounds())
            }
            try {task.await();fail()} catch(_:CancellationException) {}
            assertEquals(before,File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet())
        }
    }
    companion object {private val ExportCountsForCountry=com.bontecou.whereday.export.ExportCounts(0,0)}
}
