package com.mylibrary.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the file-manager-style name order.
 *
 * The properties pinned here are the ones a shelf can be wrong about without anyone noticing on a
 * three-book sample: numbers compared as numbers, a name's case not splitting it, and the order
 * being total so it cannot depend on the order the rows came back in.
 *
 * The Arabic tailoring itself is deliberately not asserted: the JVM's `Collator` and Android's ICU
 * one agree on these properties but are not the same implementation, and a test that pinned the
 * exact alef sequence would be testing the JVM rather than the reader's device.
 */
class NameOrderTest {

    private val collator = nameCollator()

    private fun sorted(values: List<String>): List<String> =
        values.sortedWith(compareBy { nameOrderKey(it, collator) })

    @Test
    fun `numbers compare as numbers, not as text`() {
        val sorted = sorted(listOf("المجلد 10", "المجلد 2", "المجلد 1"))

        assertEquals(listOf("المجلد 1", "المجلد 2", "المجلد 10"), sorted)
    }

    @Test
    fun `leading zeros do not change the value`() {
        val sorted = sorted(listOf("007", "010", "008"))

        assertEquals(listOf("007", "008", "010"), sorted)
    }

    @Test
    fun `arabic-indic digits order by the same value as ascii`() {
        // The shelf's own text is Arabic-first, so a number written in the reader's own numerals
        // must not fall back to a code-point comparison.
        val sorted = sorted(listOf("مجلد ١٠", "مجلد ٢", "مجلد ١"))

        assertEquals(listOf("مجلد ١", "مجلد ٢", "مجلد ١٠"), sorted)
    }

    @Test
    fun `case does not split otherwise equal names`() {
        assertEquals(0, nameOrderKey("appendix", collator).compareTo(nameOrderKey("Appendix", collator)))
    }

    @Test
    fun `a shorter name that is a prefix sorts first`() {
        val sorted = sorted(listOf("Vol 2A", "Vol 2"))

        assertEquals(listOf("Vol 2", "Vol 2A"), sorted)
    }

    @Test
    fun `digits sort before letters at the same position`() {
        val sorted = sorted(listOf("a", "1"))

        assertEquals(listOf("1", "a"), sorted)
    }

    @Test
    fun `the order is total and independent of input order`() {
        val values = listOf("المجلد 10", "مجلد 2", "Cover", "appendix", "الجزء 3")

        assertEquals(sorted(values), sorted(values.reversed()))
    }

    @Test
    fun `an empty name is handled`() {
        assertTrue(nameOrderKey("", collator).compareTo(nameOrderKey("a", collator)) < 0)
    }
}
