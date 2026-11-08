package me.esdex.coffer

import android.net.Uri
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
import me.esdex.coffer.core.vault.VaultStore
import me.esdex.coffer.core.ui.theme.Appearance
import me.esdex.coffer.core.ui.theme.AppearanceStore
import me.esdex.coffer.feature.browser.transfer.ShareInbox
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val telegram: Telegram,
    private val vault: VaultManager,
    store: VaultStore,
    private val inbox: ShareInbox,
    appearanceStore: AppearanceStore,
) : ViewModel() {

    val appearance: StateFlow<Appearance> = appearanceStore.appearance

    /** Files shared from another app are waiting for a folder (SPEC §6, step 1). */
    val sharing: StateFlow<Boolean> = inbox.state
        .map { it !is ShareInbox.State.Empty }
        .stateIn(viewModelScope, SharingStarted.Eagerly, inbox.state.value !is ShareInbox.State.Empty)

    /** Copies them at once, while the permission to read them lasts. */
    fun receiveShare(uris: List<Uri>, type: String?) = inbox.receive(uris, type)

    /** FLAG_SECURE for the whole window (SPEC §9), following the setting as it changes. */
    val blockScreenshots: StateFlow<Boolean> = store.changes
        .map { store.blockScreenshots }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.blockScreenshots)

    val signedIn: StateFlow<Boolean> = telegram.state
        .map { it == AuthState.Ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        telegram.start()
    }

    fun lock() {
        viewModelScope.launch { vault.lockVault() }
    }
}
