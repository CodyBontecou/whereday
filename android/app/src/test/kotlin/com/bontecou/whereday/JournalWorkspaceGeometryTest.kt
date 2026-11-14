package com.bontecou.whereday

import org.junit.Test
import org.junit.Assert.*

class JournalWorkspaceGeometryTest {
    @Test fun windowBoundsChooseCompactOrPairedWithoutDeviceOrOrientationNames() {
        assertFalse(JournalWorkspaceGeometry.arrange(839,1000,1f,emptyList()).paired)
        val paired=JournalWorkspaceGeometry.arrange(840,1000,1f,emptyList())
        assertEquals(WorkspaceRect(0,0,408,1000),paired.primary);assertEquals(WorkspaceRect(432,0,840,1000),paired.companion)
        assertEquals(paired.primary,JournalWorkspaceGeometry.arrange(840,1000,1f,emptyList(),rtl=true).companion)
        assertFalse(JournalWorkspaceGeometry.arrange(840,359,1f,emptyList()).paired)
        assertFalse(JournalWorkspaceGeometry.arrange(1680,1000,2f,emptyList(),allowCompanion=false).paired)
        assertNull(JournalWorkspaceGeometry.arrange(0,1000,1f,emptyList()).primary)
    }
    @Test fun bookAndTabletopUseActualHingeBoundsAndKeepPrimaryAtLeadingOrTop() {
        val book=WorkspaceFold(WorkspaceRect(370,0,430,900),WorkspaceFoldAxis.vertical,true,true)
        val result=JournalWorkspaceGeometry.arrange(800,900,1f,listOf(book))
        assertEquals(WorkspaceRect(0,0,370,900),result.primary);assertEquals(WorkspaceRect(430,0,800,900),result.companion)
        assertEquals(result.companion,JournalWorkspaceGeometry.arrange(800,900,1f,listOf(book),rtl=true).primary)
        val tabletop=WorkspaceFold(WorkspaceRect(0,440,900,460),WorkspaceFoldAxis.horizontal,true)
        val horizontal=JournalWorkspaceGeometry.arrange(900,900,1f,listOf(tabletop),rtl=true)
        assertEquals(WorkspaceRect(0,0,900,438),horizontal.primary);assertEquals(WorkspaceRect(0,462,900,900),horizontal.companion)
    }
    @Test fun compactFallbackStillAvoidsHingesAndIgnoresFlatNonoccludingCreases() {
        val fold=WorkspaceFold(WorkspaceRect(200,0,220,900),WorkspaceFoldAxis.vertical,true)
        assertEquals(WorkspaceRect(222,0,700,900),JournalWorkspaceGeometry.arrange(700,900,1f,listOf(fold)).primary)
        assertFalse(JournalWorkspaceGeometry.arrange(700,900,1f,listOf(fold)).paired)
        assertEquals(WorkspaceRect(0,0,700,900),JournalWorkspaceGeometry.arrange(700,900,1f,listOf(fold.copy(separating=false))).primary)
        assertEquals(WorkspaceRect(222,0,700,900),JournalWorkspaceGeometry.arrange(700,900,1f,listOf(fold.copy(separating=false,occluding=true))).primary)
    }
    @Test fun zeroWidthPartialMultipleAndOffWindowFoldsNeverPutPanesAcrossTheExcludedBand() {
        val vertical=WorkspaceFold(WorkspaceRect(800,200,800,600),WorkspaceFoldAxis.vertical,true)
        val horizontal=WorkspaceFold(WorkspaceRect(0,450,1600,450),WorkspaceFoldAxis.horizontal,true)
        val result=JournalWorkspaceGeometry.arrange(1600,900,1f,listOf(vertical,horizontal))
        for(rect in listOfNotNull(result.primary,result.companion)) {
            assertTrue(rect.right<=788 || rect.left>=812);assertTrue(rect.bottom<=438 || rect.top>=462)
        }
        assertEquals(result,JournalWorkspaceGeometry.arrange(1600,900,1f,listOf(horizontal,vertical)))
        assertEquals(JournalWorkspaceGeometry.arrange(800,900,1f,emptyList()),JournalWorkspaceGeometry.arrange(800,900,1f,listOf(vertical.copy(bounds=WorkspaceRect(900,0,900,900)))))
    }
    @Test fun invalidDensityIsRejectedAndOccludedOrEmptyWindowsHaveNoInteractivePane() {
        for(density in listOf(0f,Float.NaN,Float.POSITIVE_INFINITY)) assertTrue(runCatching {JournalWorkspaceGeometry.arrange(800,900,density,emptyList())}.isFailure)
        assertNull(JournalWorkspaceGeometry.arrange(800,900,1f,listOf(WorkspaceFold(WorkspaceRect(0,0,800,900),WorkspaceFoldAxis.vertical,true,true))).primary)
        val result=JournalWorkspaceGeometry.arrange(800,900,1f,listOf(WorkspaceFold(WorkspaceRect(-100,0,10,900),WorkspaceFoldAxis.vertical,true)))
        assertEquals(WorkspaceRect(10,0,800,900),result.primary)
    }
}
