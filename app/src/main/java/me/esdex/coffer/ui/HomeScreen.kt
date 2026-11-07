package me.esdex.coffer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.esdex.coffer.R
import me.esdex.coffer.debug.DebugTools

/** The vault is open. The file browser comes here next. */
@Composable
fun HomeScreen(accountName: String?, onLock: () -> Unit, onLogOut: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_unlocked), style = MaterialTheme.typography.headlineSmall)
        accountName?.let { Text(stringResource(R.string.home_account, it), style = MaterialTheme.typography.bodyLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onLock) { Text(stringResource(R.string.home_lock)) }
            OutlinedButton(onClick = onLogOut) { Text(stringResource(R.string.account_log_out)) }
        }
        DebugTools(Modifier.padding(top = 16.dp))
    }
}
