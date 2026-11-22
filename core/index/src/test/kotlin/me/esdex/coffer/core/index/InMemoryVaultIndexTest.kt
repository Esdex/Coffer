package me.esdex.coffer.core.index

/** The index the sync tests stand on keeps the same contract as the Room one. */
class InMemoryVaultIndexTest : VaultIndexContract() {
    override fun newIndex(): VaultIndex = InMemoryVaultIndex()
}
