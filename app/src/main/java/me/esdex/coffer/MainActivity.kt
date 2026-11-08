package me.esdex.coffer

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import me.esdex.coffer.feature.onboarding.LoginScreen
import me.esdex.coffer.feature.onboarding.WelcomeGate
import me.esdex.coffer.feature.onboarding.vault.VaultGate
import me.esdex.coffer.debug.DebugTools
import me.esdex.coffer.feature.browser.BrowserScreen
import me.esdex.coffer.ui.theme.CofferTheme

// A FragmentActivity, because BiometricPrompt needs one.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CofferTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val content = Modifier.padding(innerPadding)
                    WelcomeGate(content) {
                        val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
                        if (!signedIn) {
                            LoginScreen(content)
                        } else {
                            VaultGate(content) {
                                var debugOpen by remember { mutableStateOf(false) }
                                BrowserScreen(
                                    onLock = viewModel::lock,
                                    onLogOut = viewModel::logOut,
                                    modifier = content,
                                    extraMenu = if (BuildConfig.DEBUG) {
                                        { close -> DropdownMenuItem(text = { Text("Debug") }, onClick = { close(); debugOpen = true }) }
                                    } else {
                                        null
                                    },
                                )
                                if (debugOpen) {
                                    AlertDialog(
                                        onDismissRequest = { debugOpen = false },
                                        confirmButton = { TextButton(onClick = { debugOpen = false }) { Text("Close") } },
                                        text = { Column(Modifier.verticalScroll(rememberScrollState())) { DebugTools() } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
