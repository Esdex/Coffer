package me.esdex.coffer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import me.esdex.coffer.feature.onboarding.LoginScreen
import me.esdex.coffer.ui.AccountScreen
import me.esdex.coffer.ui.theme.CofferTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CofferTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
                    val accountName by viewModel.accountName.collectAsStateWithLifecycle()
                    if (signedIn) {
                        AccountScreen(accountName, viewModel::logOut, Modifier.padding(innerPadding))
                    } else {
                        LoginScreen(Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
