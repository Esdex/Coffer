package me.esdex.coffer.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Google Play's payments policy forbids pointing users at donations outside Play Billing, so the Play
 * build carries no donation page at all: no links and no wallet addresses in its code, as in Arcanum.
 */
internal const val DONATIONS = false

/** Nothing opens this on Play; should something ever do so, it goes straight back. */
@Composable
internal fun DonationsPage(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
