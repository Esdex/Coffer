package me.esdex.coffer.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Release builds have no debug tools; the storage self-check exists only in debug builds. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DebugTools(modifier: Modifier = Modifier) = Unit
