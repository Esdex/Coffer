package me.esdex.coffer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.VaultManager
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val telegram: Telegram,
    private val vault: VaultManager,
) : ViewModel() {

    val signedIn: StateFlow<Boolean> = telegram.state
        .map { it == AuthState.Ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        telegram.start()
    }

    fun lock() {
        viewModelScope.launch { vault.lockVault() }
    }

    fun logOut() {
        viewModelScope.launch { runCatching { telegram.logOut() } }
    }
}
