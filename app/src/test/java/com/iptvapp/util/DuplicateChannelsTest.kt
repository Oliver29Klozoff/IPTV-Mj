package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateChannelsTest {
    private data class Ch(val id: Int, val name: String)

    private fun collapse(list: List<Ch>, scores: Map<Int, Int> = emptyMap()) =
        DuplicateChannels.collapse(list, { it.name }) { scores[it.id] }

    @Test
    fun `copies of one channel become one row, in the first copy's place`() {
        val out = collapse(
            listOf(Ch(1, "CNN"), Ch(2, "ESPN"), Ch(3, "Fox News"), Ch(4, "ESPN HD"), Ch(5, "US: ESPN FHD")),
        )
        assertEquals(listOf(1, 2, 3), out.map { it.item.id })
        assertEquals(listOf(0, 2, 0), out.map { it.extraCopies })
    }

    @Test
    fun `the copy that has played best is the one shown`() {
        val out = collapse(listOf(Ch(2, "ESPN"), Ch(4, "ESPN HD"), Ch(5, "ESPN FHD")), mapOf(2 to 30, 4 to 90, 5 to 60))
        assertEquals(4, out.single().item.id)
        assertEquals(2, out.single().extraCopies)
    }

    @Test
    fun `a copy with no history beats a poor one but not a proven one`() {
        assertEquals(5, collapse(listOf(Ch(2, "ESPN"), Ch(5, "ESPN HD")), mapOf(2 to 20)).single().item.id)
        assertEquals(2, collapse(listOf(Ch(2, "ESPN"), Ch(5, "ESPN HD")), mapOf(2 to 80)).single().item.id)
    }

    @Test
    fun `numbered channels and unreadable names stay apart`() {
        val out = collapse(listOf(Ch(1, "ESPN"), Ch(2, "ESPN 2"), Ch(3, "新闻"), Ch(4, "新闻")))
        assertEquals(4, out.size)
    }

    @Test
    fun `the row label counts the other copies`() {
        assertEquals("ESPN", DuplicateChannels.label("ESPN", null))
        assertEquals("ESPN", DuplicateChannels.label("ESPN", 0))
        assertEquals("ESPN HD  +2", DuplicateChannels.label("ESPN HD", 2))
    }
}
