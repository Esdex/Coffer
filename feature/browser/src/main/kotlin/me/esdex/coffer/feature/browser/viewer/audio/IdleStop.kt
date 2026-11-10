package me.esdex.coffer.feature.browser.viewer.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Calls [stop] once the vault has been locked and nothing has played for [afterMillis]. A queue keeps
 * its files' keys past the lock so the music plays on; one that is only paused, or has played to its
 * end, would otherwise keep them for as long as the process lives.
 */
fun stopWhenIdleLocked(scope: CoroutineScope, locked: Flow<Boolean>, playing: Flow<Boolean>, afterMillis: Long, stop: () -> Unit): Job = scope.launch {
    combine(locked, playing) { isLocked, isPlaying -> isLocked && !isPlaying }
        .distinctUntilChanged()
        .collectLatest { idle ->
            if (idle) {
                delay(afterMillis)
                stop()
            }
        }
}
