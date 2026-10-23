// The check that a recovery phrase was written down: a few of its words, at
// places picked at random, typed back from the paper. The places come from
// the system's generator, so that no one can tell beforehand which words a
// check asks for.
package app.tingid.state

import app.tingid.chain.trimWhiteSpace
import java.security.SecureRandom
import java.util.Random

object PhraseCheck {
    /** Words asked back. */
    const val CHECKS = 3

    /**
     * [CHECKS] different places among `size` words, in order, picked by
     * `random`: the system's generator in the app, a seeded one in tests.
     */
    fun places(size: Int, random: Random = SecureRandom()): List<Int> {
        require(size >= CHECKS) { "a phrase has more words than the check asks for" }
        return (0 until size).shuffled(random).take(CHECKS).sorted()
    }

    /**
     * Whether each answer is the word at its place: as typed on any keyboard,
     * so the case and spaces around it do not matter.
     */
    fun matches(words: List<String>, places: List<Int>, answers: List<String>): Boolean =
        places.size == answers.size && places.indices.all { i -> trimWhiteSpace(answers[i]).lowercase() == words.getOrNull(places[i]) }
}
