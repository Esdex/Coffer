package me.esdex.coffer.feature.browser.transfer

/**
 * Whether the transfer queue is running, so new work wakes the run there is instead of starting another,
 * and none is lost to a run that is just ending. A run that finds nothing left [finishing] goes on if
 * anything came in since it last looked; once it has said it is ending, new work asks WorkManager for a
 * run after it ([Start.AFTER]), as one still ending would keep a plain request from starting.
 */
class QueueState {
    enum class Start {
        /** A run is going and will see the new work: nothing to ask for. */
        NONE,

        /** No run is going: one, unless one is already waiting to start. */
        ONE,

        /** The run is ending: one more after it. */
        AFTER,
    }

    private enum class Phase { IDLE, RUNNING, ENDING }

    private var phase = Phase.IDLE
    private var news = 0L

    /** New work is there. Says how to have it run. */
    @Synchronized
    fun added(): Start {
        news++
        return when (phase) {
            Phase.RUNNING -> Start.NONE
            Phase.ENDING -> {
                // One run after the ending one is enough; until it starts, it will see whatever else comes.
                phase = Phase.IDLE
                Start.AFTER
            }
            Phase.IDLE -> Start.ONE
        }
    }

    /** A run starts; what it returns is passed back to [finishing]. */
    @Synchronized
    fun started(): Long {
        phase = Phase.RUNNING
        return news
    }

    /** What the run has seen so far, before it looks again for work. */
    @Synchronized
    fun seen(): Long = news

    /** The run found nothing to do after [seen]: true if it may end, false if new work came meanwhile. */
    @Synchronized
    fun finishing(seen: Long): Boolean {
        if (news != seen) return false
        phase = Phase.ENDING
        return true
    }

    /** The run was stopped from outside (no network, the system): it will be started again for what is left. */
    @Synchronized
    fun stopped() {
        if (phase == Phase.RUNNING) phase = Phase.IDLE
    }
}
