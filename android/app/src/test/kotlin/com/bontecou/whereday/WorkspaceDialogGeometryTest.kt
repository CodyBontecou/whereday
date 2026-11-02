package com.bontecou.whereday

import org.junit.Test
import org.junit.Assert.*

class WorkspaceDialogGeometryTest {
    private val top=WorkspaceRect(0,0,1000,400)
    private val bottom=WorkspaceRect(0,432,1000,832)
    @Test fun keyboardObscuredOwnerUsesAnotherSafePaneAndReturnsWhenItHides() {
        assertEquals(top,WorkspaceDialogGeometry.choose(bottom,listOf(top,bottom),WorkspaceRect(0,0,1000,520),280,240))
        assertEquals(bottom,WorkspaceDialogGeometry.choose(bottom,listOf(top,bottom),WorkspaceRect(0,0,1000,832),280,240))
    }
    @Test fun usableOwnerDoesNotJumpToALargerOrEarlierRegion() {
        val owner=WorkspaceRect(600,0,1000,600)
        assertEquals(owner,WorkspaceDialogGeometry.choose(owner,listOf(WorkspaceRect(0,0,568,800),owner),WorkspaceRect(0,0,1000,800),280,240))
    }
    @Test fun noPaneCrossesTheExcludedBandAndInsetsAreIntersectedBeforeSelection() {
        val chosen=WorkspaceDialogGeometry.choose(bottom,listOf(top,bottom),WorkspaceRect(20,30,980,520),280,240)!!
        assertEquals(WorkspaceRect(20,30,980,400),chosen)
        assertTrue(chosen.bottom<=400)
    }
    @Test fun noBetterUsablePaneKeepsTheVisibleOwnerAndFullOcclusionHasNoTarget() {
        val owner=WorkspaceRect(500,0,1000,832)
        assertEquals(WorkspaceRect(500,0,1000,100),WorkspaceDialogGeometry.choose(owner,listOf(top,owner),WorkspaceRect(0,0,1000,100),280,240))
        assertNull(WorkspaceDialogGeometry.choose(bottom,listOf(top,bottom),WorkspaceRect(0,900,1000,1000),280,240))
    }
    @Test fun singlePaneArrangementsRetainAllPhysicalSafeRegionsForModalFallback() {
        val fold=WorkspaceFold(WorkspaceRect(0,400,1000,432),WorkspaceFoldAxis.horizontal,true)
        val result=JournalWorkspaceGeometry.arrange(1000,832,1f,listOf(fold),allowCompanion=false)
        assertFalse(result.paired);assertEquals(listOf(top,bottom),result.safeRegions)
        assertEquals(top,WorkspaceDialogGeometry.choose(bottom,result.safeRegions,WorkspaceRect(0,0,1000,500),280,240))
    }
    @Test fun largeTextMinimumCanSelectASufficientRegionInsteadOfCrushingTheOwner() {
        val upper=WorkspaceRect(0,0,1000,600);val lower=WorkspaceRect(0,632,1000,1200)
        assertEquals(upper,WorkspaceDialogGeometry.choose(lower,listOf(upper,lower),WorkspaceRect(0,0,1000,950),280,432))
    }
}
