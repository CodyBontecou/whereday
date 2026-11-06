package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SavedPlaceReusePlanTest {
    private val origin=Coordinate(0.0,0.0)
    private fun plan(candidate:SavedPlace,existing:List<SavedPlace>)=SavedPlaceReusePlan.build(candidate,existing,{a,b->a.distanceTo(b)},{it.lowercase(Locale.ROOT)})
    @Test fun createsATrimmedPlaceWhenNothingMatches() {
        val result=plan(SavedPlace("new"," Home ",origin," Address ",250.0),emptyList())
        assertNull(result.original);assertEquals(SavedPlace("new","Home",origin,"Address",250.0),result.value)
    }
    @Test fun updatesFirstMatchingLibraryEntryRatherThanTheNearestAndPreservesItsIdentityAndName() {
        val first=SavedPlace("first","HOME",Coordinate(.001,0.0),"Old",150.0)
        val nearer=SavedPlace("nearer","Home",origin,"Other",100.0)
        val result=plan(SavedPlace("new","home",origin,"Updated",250.0),listOf(first,nearer))
        assertEquals(first,result.original);assertEquals("first",result.value.id);assertEquals("HOME",result.value.name)
        assertEquals(origin,result.value.coordinate);assertEquals("Updated",result.value.address);assertEquals(250.0,result.value.radius,0.0)
    }
    @Test fun largerRadiusOnEitherSideMatchesButDifferentNamesAndFarLocationsDoNot() {
        val candidate=SavedPlace("new","Home",origin,radius=50.0)
        val existing=SavedPlace("old","home",Coordinate(.001,0.0),radius=150.0)
        assertEquals(existing.id,plan(candidate,listOf(existing)).value.id)
        assertEquals(existing.id,plan(candidate.copy(radius=150.0),listOf(existing.copy(radius=50.0))).value.id)
        assertNull(plan(candidate,listOf(existing.copy(name="Other"))).original)
        assertNull(plan(candidate,listOf(existing.copy(coordinate=Coordinate(1.0,1.0)))).original)
    }
    @Test fun unrelatedIdentityCollisionFailsAndCheckpointsCanCancelThePlan() {
        val candidate=SavedPlace("collision","Home",origin)
        try {plan(candidate,listOf(candidate.copy(name="Other")));fail()} catch(_:IllegalArgumentException) {}
        try {SavedPlaceReusePlan.build(candidate,listOf(candidate.copy(id="other")),{a,b->a.distanceTo(b)},{it}) {throw java.util.concurrent.CancellationException()};fail()} catch(_:java.util.concurrent.CancellationException) {}
    }
}
