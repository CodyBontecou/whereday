package com.bontecou.whereday

import org.junit.Test
import org.junit.Assert.*

class SoftwareNoticeChunksTest {
    @Test fun virtualizedChunksRetainEveryCharacterIncludingCrLfAndWhitespace() {
        val original=("Copyright © fixture\r\n\r\n  Preserve whitespace.\r\n"+"x".repeat(4001)+"\r\n").repeat(20)
        for(limit in listOf(2,19,3000)) {
            val chunks=softwareNoticeChunks(original,limit)
            assertEquals(original,chunks.joinToString(""))
            assertTrue(chunks.all {it.isNotEmpty() && it.length<=limit})
            assertArrayEquals(original.toByteArray(),chunks.joinToString("").toByteArray())
        }
    }
    @Test fun UnicodePairsAreNeverSplitAcrossVirtualizedItems() {
        val original=("a\uD83C\uDF0D\r\n\uD835\uDC00b").repeat(100)
        for(limit in 2..24) {
            val chunks=softwareNoticeChunks(original,limit)
            assertEquals(original,chunks.joinToString(""))
            assertTrue(chunks.none {it.first().isLowSurrogate() || it.last().isHighSurrogate()})
        }
    }
    @Test fun emptyAndSingleParagraphDocumentsDoNotInventOrOmitContent() {
        assertTrue(softwareNoticeChunks("").isEmpty())
        assertEquals(listOf("Exact fixture"),softwareNoticeChunks("Exact fixture"))
        assertEquals("x".repeat(7000),softwareNoticeChunks("x".repeat(7000)).joinToString(""))
    }
}
