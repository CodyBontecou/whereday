package com.bontecou.whereday

import com.bontecou.whereday.domain.HistoryRange
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MapDateNativeReferenceTest {
    @Test fun dateEditsMatchTheObservedNativeSwiftUiBindings() {
        val root=javaClass.getResourceAsStream("/ios-custom-date-edits.json")!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        assertEquals(9,root["cases"].asJsonArray.size())
        for(element in root["cases"].asJsonArray) {
            val value=element.asJsonObject
            fun text(key:String)=value[key].asString
            val zone=ZoneId.of(text("zone"))
            var draft=MapDateDraft(HistoryRange(Instant.parse(text("first")),Instant.parse(text("last")).plusMillis(1)),zone)
            for(stepElement in value["steps"].asJsonArray) {
                val step=stepElement.asJsonObject;val date=LocalDate.parse(step["date"].asString)
                draft=if(step["bound"].asString=="first") draft.edit(date,null) else draft.edit(null,date)
            }
            val result=draft.apply(draft.selectedFirst,draft.selectedLast).range(Instant.EPOCH,zone)
            assertEquals(text("name"),Instant.parse(text("expectedFirst")),result.from)
            assertEquals(text("name"),Instant.parse(text("expectedLast")).plusMillis(1),result.until)
        }
    }
}
