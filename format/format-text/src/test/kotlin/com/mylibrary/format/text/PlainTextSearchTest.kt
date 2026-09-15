package com.mylibrary.format.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search, and the contract that makes a hit usable: the locator's character offset has to index the
 * match in the very same string [PlainTextDocument.chapterText] returns, because that is the string
 * the reader scrolls to.
 */
class PlainTextSearchTest {

    @Test
    fun `the locator offset indexes the match in the chapter text`() {
        val document = open(ARABIC_SAMPLE)
        val query = "الزمان"

        val hit = searchIn(document, query).first()

        val locator = locatorOf(hit)
        val chapter = textOf(document, locator.chapterIndex)
        // The offset is only useful if it can be used to cut the match out of the chapter.
        assertEquals(query, chapter.substring(locator.charOffset, locator.charOffset + query.length))
    }

    @Test
    fun `the locator offset indexes the match in the chapter it names`() {
        val document = open(ARABIC_SAMPLE + "" + ENGLISH_SAMPLE)

        val hit = searchIn(document, "clocks").first()

        val locator = locatorOf(hit)
        assertEquals(1, locator.chapterIndex)
        val chapter = textOf(document, locator.chapterIndex)
        assertEquals("clocks", chapter.substring(locator.charOffset, locator.charOffset + "clocks".length))
        // And it is the second chapter's text, not something offset into the first one.
        assertFalse(chapter.contains("كان يا ما كان"))
    }

    @Test
    fun `reports the snippet around the match`() {
        val document = open(ENGLISH_SAMPLE)
        val query = "thirteen"

        val hit = searchIn(document, query).first()

        assertEquals(query, hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertTrue("snippet was too long: ${hit.snippet.length}", hit.snippet.length <= query.length + 80)
        assertTrue(hit.snippet.contains("clocks were striking"))
    }

    @Test
    fun `labels a hit with its one based chapter number`() {
        val document = open(ARABIC_SAMPLE + "" + ENGLISH_SAMPLE)

        assertEquals("1", searchIn(document, "الزمان").first().label)
        assertEquals("2", searchIn(document, "clocks").first().label)
    }

    @Test
    fun `ignores case`() {
        val document = open("The wind was cold. THE WIND was sharp. the wind went on.")

        val hits = searchIn(document, "the wind")

        assertEquals(3, hits.size)
    }

    @Test
    fun `reports hits in reading order`() {
        val document = open("one two one two one")

        val offsets = searchIn(document, "one").map { locatorOf(it).charOffset }

        assertEquals(offsets.sorted(), offsets)
        assertEquals(3, offsets.size)
    }

    @Test
    fun `stops at the limit it was given`() {
        val document = open("needle ".repeat(10))

        val hits = searchIn(document, "needle", limit = 3)

        assertEquals(3, hits.size)
    }

    @Test
    fun `returns nothing for a blank query`() {
        val document = open(ARABIC_SAMPLE)

        assertTrue(searchIn(document, "   ").isEmpty())
        assertTrue(searchIn(document, "").isEmpty())
    }

    @Test
    fun `returns nothing when the word is not there`() {
        assertTrue(searchIn(open(ARABIC_SAMPLE), "zzzz").isEmpty())
    }

    @Test
    fun `does not report a match that runs across the break between two chapters`() {
        // The break is not part of either chapter's text, so there would be no offset to highlight.
        val document = open("alphabeta")

        assertTrue(searchIn(document, "alphabet").isEmpty())
        assertEquals("alpha", textOf(document, 0))
        assertEquals("beta", textOf(document, 1))
    }

    @Test
    fun `finds a word at the very start and the very end of a chapter`() {
        val document = open("start middle end")

        assertEquals(0, locatorOf(searchIn(document, "start").first()).charOffset)
        assertEquals(13, locatorOf(searchIn(document, "end").first()).charOffset)
    }
}
