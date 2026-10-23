// What typing does in the app's fields, apart from any screen: an amount
// grouped as it is typed, a later payment's time, the words of a recovery
// phrase spread over their fields, and release versions.
package app.tingid.state

import app.tingid.chain.Phrase
import app.tingid.chain.WHITE_SPACE
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// ---- Amounts as typed ------------------------------------------------------------
//
// An amount as it is typed, shown with its whole part in groups of three, as
// the wallet shows amounts: `40000.5` reads `40 000.5`. What was typed stays
// the digits alone, for parseAmount.

/** `raw` with its whole part grouped; anything that is not a plain number shows as typed. */
fun groupTyped(raw: String): String {
    val m = Regex("^([0-9]*)([.,]?[0-9]*)$").matchEntire(raw) ?: return raw
    val whole = m.groupValues[1]
    val grouped = StringBuilder()
    for ((i, c) in whole.withIndex()) {
        if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(' ')
        grouped.append(c)
    }
    return grouped.toString() + m.groupValues[2]
}

/** The typed text without the groups' spaces. */
fun ungroupTyped(text: String): String = text.replace(Regex("[${WHITE_SPACE}_]"), "")

// ---- When a payment goes ----------------------------------------------------------
//
// Now, or at a chosen local date and time, which the node holds the payment
// until (wiki: node, deferred transactions).

object When {
    /** How far ahead a payment can be set: the node holds a transaction for at most 366 days. */
    const val LATEST = 365 * 86_400L

    /** A later payment's first proposal: the next full hour, a day from now. */
    fun defaultLater(now: Long): Long = Math.ceilDiv(now + 86_400, 3600L) * 3600

    /** `at` moved to the date `day`, keeping its time of day, in `zone`. */
    fun onDay(at: Long, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long {
        val d = Instant.ofEpochSecond(at).atZone(zone)
        return ZonedDateTime.of(day, d.toLocalTime(), zone).toEpochSecond()
    }

    /** `at` moved to the time of day `time`, on the same date, to the minute. */
    fun atTime(at: Long, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long {
        val d = Instant.ofEpochSecond(at).atZone(zone)
        return ZonedDateTime.of(d.toLocalDate(), time.withSecond(0).withNano(0), zone).toEpochSecond()
    }

    /** What is wrong with `at` as a later payment's time, or null. */
    fun laterProblem(at: Long, now: Long): String? = when {
        at <= now + 60 -> "choose a time in the future"
        at > now + LATEST -> "choose a time within a year"
        else -> null
    }
}

// ---- A recovery phrase, typed into its fields -------------------------------------

object PhraseEntry {
    private val known: Set<String> by lazy { Phrase.WORDS.toHashSet() }

    /** Whether `word` is one of the 2,048. */
    fun isPhraseWord(word: String): Boolean = word in known

    /** Empty fields for a phrase of `length` words, keeping what `words` has. */
    fun fields(length: Int, words: List<String> = emptyList()): List<String> = List(length) { words.getOrElse(it) { "" } }

    /** On the list, and no longer word starts with it: nothing more to type. */
    fun finishedWord(word: String): Boolean = word in known && Phrase.suggest(word, 2).size == 1

    enum class Move { Next, Close }

    class Entered(val words: List<String>, val move: Move?)

    /**
     * `raw` typed into field `i`: the words then, and whether the next field takes
     * over or the keyboard closes. A pasted phrase fills the fields from `i` on;
     * pasted into the first, its own length decides how many there are.
     */
    fun enter(words: List<String>, i: Int, raw: String): Entered {
        val parts = raw.lowercase().split(Regex("[$WHITE_SPACE]+")).filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val length = if (i == 0 && parts.size in Phrase.LENGTHS) parts.size
            else Phrase.LENGTHS.firstOrNull { it >= maxOf(words.size, i + parts.size) } ?: words.size
            val next = fields(length, words).toMutableList()
            parts.take(maxOf(0, length - i)).forEachIndexed { k, p -> next[i + k] = p }
            return Entered(next, Move.Close)
        }
        val word = parts.firstOrNull() ?: ""
        // A space after the word, or a word that needs nothing more, moves on.
        val endsInSpace = raw.isNotEmpty() && app.tingid.chain.isWhiteSpace(raw.codePointBefore(raw.length))
        val move = if (word.isNotEmpty() && (endsInSpace || finishedWord(word))) Move.Next else null
        return Entered(words.mapIndexed { j, w -> if (j == i) word else w }, move)
    }

    /** Why the words are no phrase, leaving alone the one in field `typing`; with all words in, the checksum's say. */
    fun problem(words: List<String>, typing: Int?): String? {
        val unknown = words.indices.firstOrNull { words[it].isNotEmpty() && it != typing && words[it] !in known }
        if (unknown != null) return "word ${unknown + 1} is not one of the recovery phrase words"
        return if (words.all { it in known }) Phrase.problem(words.joinToString(" ")) else null
    }
}

// ---- Release versions, as GitHub tags them ----------------------------------------

object Versions {
    /** `v1.2.3` or `1.2.3` as numbers to compare; null for anything else. */
    fun of(text: String): List<Int>? {
        val m = Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)$").matchEntire(app.tingid.chain.trimWhiteSpace(text)) ?: return null
        return m.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    /** Whether release `latest` is newer than `mine`. */
    fun newer(latest: String, mine: String): Boolean {
        val a = of(latest) ?: return false
        val b = of(mine) ?: return false
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
