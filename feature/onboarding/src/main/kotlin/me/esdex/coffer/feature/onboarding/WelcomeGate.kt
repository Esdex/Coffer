package me.esdex.coffer.feature.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/** The first-start explanation (SPEC §7, step 1), shown once per install; then [content]. */
@Composable
fun WelcomeGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var seen by remember { mutableStateOf(prefs.getBoolean(WELCOME_SEEN, false)) }
    if (seen) {
        content()
        return
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.welcome_what), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.welcome_api), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.welcome_saved_messages), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    prefs.edit { putBoolean(WELCOME_SEEN, true) }
                    seen = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.welcome_start)) }
        }
    }
}

private const val PREFS = "onboarding"
private const val WELCOME_SEEN = "welcomeSeen"
