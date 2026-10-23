// Typing in the app's fields: amounts grouped, later payments' times, phrase
// words spread over their fields, and versions compared.
package app.tingid.state

import app.tingid.chain.parseAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AmountInputTest {
    @Test
    fun `groups the whole part in threes and leaves the decimals as typed`() {
        assertEquals("", groupTyped(""))
        assertEquals("999", groupTyped("999"))
        assertEquals("40 000", groupTyped("40000"))
        assertEquals("1 234 567.", groupTyped("1234567."))
        assertEquals("1 234 567.12345", groupTyped("1234567.12345"))
        assertEquals("1 234,5", groupTyped("1234,5"))
        assertEquals("12a", groupTyped("12a"))
    }

    @Test
    fun `reads back as typed, so the amount parses the same`() {
        for (raw in listOf("5", "40000", "1234567.5", "0.0001")) {
            assertEquals(raw, ungroupTyped(groupTyped(raw)))
            assertEquals(parseAmount(raw, 4), parseAmount(groupTyped(raw), 4))
        }
        assertEquals("400001", ungroupTyped("${groupTyped("40000")}1"))
    }
}

class WhenTest {
    private val zone = ZoneId.of("Europe/Oslo")
    private fun local(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toEpochSecond()

    @Test
    fun `is first proposed for the next full hour a day ahead`() {
        // Whole hours are the same in every zone with whole-hour offsets.
        assertEquals(local(2026, 10, 17, 12, 0), When.defaultLater(local(2026, 10, 16, 11, 19)))
        assertEquals(local(2026, 10, 17, 11, 0), When.defaultLater(local(2026, 10, 16, 11, 0)))
    }

    @Test
    fun `keeps its time when the day changes, and its day when the time changes`() {
        val at = local(2026, 10, 17, 14, 30)
        assertEquals(local(2026, 12, 24, 14, 30), When.onDay(at, LocalDate.of(2026, 12, 24), zone))
        assertEquals(local(2026, 10, 17, 8, 45), When.atTime(at, LocalTime.of(8, 45, 59), zone))
    }

    @Test
    fun `must be in the future and within the year the node holds it`() {
        val now = local(2026, 10, 16, 11, 19)
        assertEquals("choose a time in the future", When.laterProblem(now + 30, now))
        assertNull(When.laterProblem(now + 3600, now))
        assertNull(When.laterProblem(now + When.LATEST, now))
        assertEquals("choose a time within a year", When.laterProblem(now + When.LATEST + 1, now))
    }
}

class PhraseEntryTest {
    private val twelve = PhraseEntry.fields(12)
    private val valid = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(' ')

    @Test
    fun `moves on after a space, or once no longer word starts with the typed one`() {
        assertNull(PhraseEntry.enter(twelve, 0, "aban").move)
        assertEquals(PhraseEntry.Move.Next, PhraseEntry.enter(twelve, 0, "abandon").move)
        // "act" is a word, but "action", "actor" and "actress" start with it.
        assertFalse(PhraseEntry.finishedWord("act"))
        assertNull(PhraseEntry.enter(twelve, 0, "act").move)
        val spaced = PhraseEntry.enter(twelve, 0, "act ")
        assertEquals(listOf("act") + twelve.drop(1), spaced.words)
        assertEquals(PhraseEntry.Move.Next, spaced.move)
        // A space alone, as after a word that moved on by itself, leaves the field empty.
        val space = PhraseEntry.enter(twelve, 1, " ")
        assertEquals(twelve, space.words)
        assertNull(space.move)
    }

    @Test
    fun `takes words in lower case`() {
        assertEquals("zoo", PhraseEntry.enter(twelve, 3, "Zoo").words[3])
    }

    @Test
    fun `spreads a pasted phrase over the fields, and its length decides how many`() {
        val pasted = PhraseEntry.enter(twelve, 0, " ${List(23) { "abandon" }.joinToString(" ")}\nart ")
        assertEquals(PhraseEntry.Move.Close, pasted.move)
        assertEquals(24, pasted.words.size)
        assertEquals("art", pasted.words[23])
        assertEquals(valid, PhraseEntry.enter(PhraseEntry.fields(24), 0, valid.joinToString(" ")).words)
        val late = PhraseEntry.enter(twelve, 10, "one two three")
        assertEquals(15, late.words.size)
        assertEquals(listOf("one", "two", "three"), late.words.subList(10, 13))
    }

    @Test
    fun `names a wrong word by its place, but not while it is typed`() {
        val words = valid.take(11) + "abou"
        assertNull(PhraseEntry.problem(words, 11))
        assertEquals("word 12 is not one of the recovery phrase words", PhraseEntry.problem(words, null))
        assertNull(PhraseEntry.problem(PhraseEntry.fields(12, listOf("abandon")), null))
        assertTrue(PhraseEntry.problem(List(12) { "abandon" }, null)!!.contains("do not check out"))
        assertNull(PhraseEntry.problem(valid, null))
    }
}

class VersionsTest {
    @Test
    fun `read as three numbers, with or without a v`() {
        assertEquals(listOf(0, 2, 4), Versions.of("v0.2.4"))
        assertEquals(listOf(1, 10, 0), Versions.of("1.10.0"))
        assertNull(Versions.of("v1.2"))
    }

    @Test
    fun `compare by number, not as text`() {
        assertTrue(Versions.newer("v0.10.0", "0.9.9"))
        assertFalse(Versions.newer("v0.2.4", "0.2.4"))
        assertFalse(Versions.newer("v0.1.0", "0.2.4"))
        assertFalse(Versions.newer("nightly", "0.2.4"))
    }
}
