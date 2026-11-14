package com.bontecou.whereday

import org.junit.Test
import org.junit.Assert.*

class VisitLinkRequestTest {
    @Test fun knownGenerationRestoresTheSamePendingIdentityAndValidationTime() {
        val request=VisitLinkRequest.receive("isome://check-in?name=Cafe","generation")
        assertEquals(request,VisitLinkRequest.restore(request.saved()))
        assertEquals(request.route,VisitLinkRequest.restore(request.saved())!!.route)
    }
    @Test fun unknownCheckingGenerationDoesNotRestoreUnprovenPrivateInput() {
        assertNull(VisitLinkRequest.restore(VisitLinkRequest.receive("isome://check-in").saved()))
    }
    @Test fun malformedSavedRequestsFailClosed() {
        for(parts in listOf(emptyList(),listOf("id","url","date","generation"),listOf("id","url","date"))) assertNull(VisitLinkRequest.restore(parts))
    }
    @Test fun oversizedInputIsBoundedAndRemainsInvalid() {
        val request=VisitLinkRequest.receive("isome://check-in?name="+"x".repeat(20000),"")
        assertEquals(8193,request.url.length);assertNull(request.route)
    }
}
