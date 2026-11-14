package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test

class NearbyPlaceFilterTest {
    private val a=NearbyPlaceSuggestion("a","Café Central","Calle del Sol",Coordinate(0.0,0.0),0.0)
    private val b=NearbyPlaceSuggestion("b","Biblioteca",null,Coordinate(.001,0.0),111.0)
    private val rows=listOf(a,b)
    @Test fun blankFiltersRetainTheOriginalOrderedPageAndObjects() {
        assertSame(rows,filterNearbyPlaceSuggestions(rows," \n ","es"))
        assertSame(a,filterNearbyPlaceSuggestions(rows,"CENTRAL","es").single())
        assertEquals(rows,filterNearbyPlaceSuggestions(rows,"a","es"))
    }
    @Test fun nameAddressCaseAndCanonicalUnicodeAreSearchableWithoutChangingMetadata() {
        for(query in listOf("cAFÉ","CAFE\u0301","  del SOL  ")) assertEquals(listOf(a),filterNearbyPlaceSuggestions(rows,query,"es"))
        assertTrue(filterNearbyPlaceSuggestions(rows,"unknown","es").isEmpty())
        assertEquals("Café Central",a.name);assertEquals("Calle del Sol",a.address)
    }
    @Test fun caseFoldingUsesTheRequestedLocale() {
        val turkish=a.copy(name="İSTANBUL",address=null)
        assertEquals(listOf(turkish),filterNearbyPlaceSuggestions(listOf(turkish),"istanbul","tr"))
        assertTrue(filterNearbyPlaceSuggestions(listOf(turkish),"ıstanbul","tr").isEmpty())
    }
    @Test fun filteringDoesNotMergeDistinctSuggestionsOrMatchMissingAddresses() {
        val duplicate=a.copy(id="another",distanceMeters=2.0)
        assertEquals(listOf(a,duplicate),filterNearbyPlaceSuggestions(listOf(a,duplicate,b),"CAFÉ","es"))
        assertTrue(filterNearbyPlaceSuggestions(listOf(b),"null","es").isEmpty())
    }
}
