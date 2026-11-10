package me.esdex.coffer.feature.browser.viewer.audio

import kotlin.random.Random

enum class Repeat { NONE, ALL, ONE }

/**
 * Which track of a folder plays after which: in folder order, or shuffled with the playing track
 * kept where it is so nothing restarts, and at the end either stopping or going round again.
 * Repeat one is the player's business while a track runs; skipping by hand still moves on.
 */
class TrackOrder(private val count: Int, start: Int, private val random: Random = Random.Default) {
    init {
        require(count > 0 && start in 0 until count) { "start must be one of the tracks" }
    }

    private var order: List<Int> = List(count) { it }
    private var at = start

    /** The track playing, as an index into the folder. */
    val current: Int get() = order[at]

    var shuffled: Boolean = false
        private set

    var repeat: Repeat = Repeat.NONE

    fun shuffle(on: Boolean) {
        if (on == shuffled) return
        val playing = current
        order = if (on) listOf(playing) + (0 until count).filter { it != playing }.shuffled(random) else List(count) { it }
        at = order.indexOf(playing)
        shuffled = on
    }

    /**
     * The track after this one, or null when the last one has played and nothing repeats. [byHand] is
     * a press of Next: it moves on even under repeat one, and from the last track it goes round only
     * when everything repeats.
     */
    fun next(byHand: Boolean): Int? {
        if (!byHand && repeat == Repeat.ONE) return current
        if (at + 1 < count) {
            at++
        } else if (repeat == Repeat.ALL) {
            at = 0
        } else {
            return null
        }
        return current
    }

    /** The track before this one; from the first, the last when everything repeats, else the first again. */
    fun previous(): Int {
        at = when {
            at > 0 -> at - 1
            repeat == Repeat.ALL -> count - 1
            else -> 0
        }
        return current
    }

    /** A track picked by hand: it plays now, and the order goes on from it. */
    fun jumpTo(index: Int) {
        require(index in 0 until count)
        at = order.indexOf(index)
    }
}
