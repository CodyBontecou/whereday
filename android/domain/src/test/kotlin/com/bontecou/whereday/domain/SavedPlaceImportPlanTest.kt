package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.text.Normalizer

class SavedPlaceImportPlanTest {
    @Test fun plannerMatchesEighteenActualSwiftCasesUsingItsEllipsoidalDistanceMatrixAndNameKeys() {
        val cases=requireNotNull(javaClass.getResourceAsStream("/ios-saved-place-plans.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        assertEquals(18,cases.size())
        for(element in cases) {
            val c=element.asJsonObject
            fun places(key:String)=c[key].asJsonArray.map {value ->value.asJsonObject.let {SavedPlace(it["id"].asString,it["name"].asString,Coordinate(it["latitude"].asDouble,it["longitude"].asDouble),it["address"].asString,it["radius"].asDouble)}}
            val existing=places("existing");val incoming=places("incoming");val all=existing+incoming
            val indices=all.withIndex().associate {it.value.coordinate to it.index}
            val keys=c["nameKeys"].asJsonObject.entrySet().associate {it.key.trim() to Normalizer.normalize(it.value.asString,Normalizer.Form.NFC)}
            val distance={a:Coordinate,b:Coordinate->c["distances"].asJsonArray[indices.getValue(a)].asJsonArray[indices.getValue(b)].asDouble}
            val plan=SavedPlaceImportPlan.build(incoming,existing,SavedPlaceDuplicatePolicy.valueOf(c["policy"].asString),distance,{keys.getValue(it)})
            assertEquals(c["name"].asString,c["entries"].asJsonArray.map {it.asJsonObject["targetId"].asString},plan.entries.map {it.targetId})
            assertEquals(c["name"].asString,c["entries"].asJsonArray.map {it.asJsonObject["action"].asString},plan.entries.map {it.action.name})
            assertEquals(c["name"].asString,c["summary"].asJsonObject.let {SavedPlaceImportSummary(it["added"].asInt,it["updated"].asInt,it["skipped"].asInt)},plan.summary)
        }
    }
}
