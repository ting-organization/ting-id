// The write-down check of a recovery phrase: which words it asks for, and
// whether the answers are those words.
package app.tingid.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PhraseCheckTest {
    private val words = "abandon ability able about above absent absorb abstract absurd abuse access accident".split(' ')

    @Test
    fun `asks for three different places, in order, within the phrase`() {
        repeat(200) {
            val places = PhraseCheck.places(12)
            assertEquals(PhraseCheck.CHECKS, places.size)
            assertEquals(places.sorted(), places)
            assertEquals(places.size, places.toSet().size)
            assertTrue(places.all { it in 0 until 12 })
        }
        assertTrue(PhraseCheck.places(24).all { it in 0 until 24 })
    }

    @Test
    fun `every place can be asked for`() {
        val random = Random(7)
        val seen = HashSet<Int>()
        repeat(500) { seen.addAll(PhraseCheck.places(12, random)) }
        assertEquals((0 until 12).toSet(), seen)
    }

    @Test
    fun `the places come from the generator it is given`() {
        assertEquals(PhraseCheck.places(12, Random(1)), PhraseCheck.places(12, Random(1)))
    }

    @Test
    fun `a phrase shorter than the check is refused`() {
        assertThrows(IllegalArgumentException::class.java) { PhraseCheck.places(2) }
    }

    @Test
    fun `answers match whatever their case and the spaces around them`() {
        val places = listOf(0, 5, 11)
        assertTrue(PhraseCheck.matches(words, places, listOf("abandon", "absent", "accident")))
        assertTrue(PhraseCheck.matches(words, places, listOf(" Abandon", "ABSENT\t", " accident ")))
    }

    @Test
    fun `a wrong word, a word at another place or a missing answer does not match`() {
        val places = listOf(0, 5, 11)
        assertFalse(PhraseCheck.matches(words, places, listOf("abandon", "absorb", "accident")))
        assertFalse(PhraseCheck.matches(words, places, listOf("accident", "absent", "abandon")))
        assertFalse(PhraseCheck.matches(words, places, listOf("abandon", "absent")))
        assertFalse(PhraseCheck.matches(words, places, listOf("abandon", "absent", "")))
        assertFalse(PhraseCheck.matches(words, listOf(0, 5, 12), listOf("abandon", "absent", "")))
    }
}
