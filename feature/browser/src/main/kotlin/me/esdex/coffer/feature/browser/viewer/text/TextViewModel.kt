package me.esdex.coffer.feature.browser.viewer.text

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.ui.theme.TextPrefs
import me.esdex.coffer.core.ui.theme.TextStore
import me.esdex.coffer.feature.browser.transfer.VaultContent
import javax.inject.Inject

/**
 * A text file read whole from Saved Messages and shown (pre-release item 5). Whole, because a text
 * view lays out all of it; up to [TextDocument.MAX_BYTES], which lies within a single chunk.
 */
@HiltViewModel
class TextViewModel @Inject constructor(
    private val content: VaultContent,
    textStore: TextStore,
) : ViewModel() {

    enum class Failure { TOO_LARGE, NOT_TEXT, NOT_AVAILABLE }

    data class State(
        val fileId: Id? = null,
        val loading: Boolean = true,
        val document: TextDocument? = null,
        val failure: Failure? = null,
    )

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    val prefs: StateFlow<TextPrefs> = textStore.prefs

    /** The vault locked: the text came from it, and must not stay on screen. */
    val locked: StateFlow<Boolean> = content.keys.map { it == null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var loading: Job? = null

    fun open(fileId: Id) {
        if (stateFlow.value.fileId == fileId) return
        loading?.cancel()
        stateFlow.value = State(fileId = fileId)
        loading = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { read(fileId) }
            if (stateFlow.value.fileId == fileId) stateFlow.value = result.copy(fileId = fileId, loading = false)
        }
    }

    private suspend fun read(fileId: Id): State {
        val file = content.openFile(fileId) ?: return State(failure = Failure.NOT_AVAILABLE)
        return file.use {
            val reader = it.reader
            if (reader.size > TextDocument.MAX_BYTES) return State(failure = Failure.TOO_LARGE)
            val bytes = ByteArray(reader.size.toInt())
            try {
                var done = 0
                while (done < bytes.size) {
                    val n = reader.read(done.toLong(), bytes, done, bytes.size - done)
                    if (n <= 0) return State(failure = Failure.NOT_AVAILABLE)
                    done += n
                }
                TextDocument.decode(bytes)?.let { doc -> State(document = doc) } ?: State(failure = Failure.NOT_TEXT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                State(failure = Failure.NOT_AVAILABLE)
            } finally {
                bytes.fill(0)
            }
        }
    }

    /** The viewer was left; this model lives as long as the activity, so the text goes here. */
    fun close() {
        loading?.cancel()
        loading = null
        stateFlow.value = State()
    }
}
