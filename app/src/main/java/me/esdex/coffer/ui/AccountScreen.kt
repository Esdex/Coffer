package me.esdex.coffer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.esdex.coffer.R

/** Signed in to Telegram. The vault itself comes on top of this. */
@Composable
fun AccountScreen(accountName: String?, onLogOut: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.account_signed_in), style = MaterialTheme.typography.headlineSmall)
        accountName?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        OutlinedButton(onClick = onLogOut) { Text(stringResource(R.string.account_log_out)) }
    }
}
