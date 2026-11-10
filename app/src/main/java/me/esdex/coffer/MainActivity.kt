package me.esdex.coffer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import me.esdex.coffer.feature.onboarding.LoginScreen
import me.esdex.coffer.feature.onboarding.WelcomeGate
import me.esdex.coffer.feature.onboarding.vault.VaultGate
import me.esdex.coffer.debug.DebugTools
import me.esdex.coffer.feature.browser.BrowserScreen
import me.esdex.coffer.feature.browser.BrowserViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.esdex.coffer.feature.browser.R as BrowserR
import me.esdex.coffer.feature.browser.ShareScreen
import me.esdex.coffer.feature.browser.viewer.audio.AudioPlayback
import javax.inject.Inject
import me.esdex.coffer.feature.settings.SettingsScreen
import me.esdex.coffer.core.ui.theme.CofferTheme

// A FragmentActivity, because BiometricPrompt needs one.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var playback: AudioPlayback

    /** Opened by Share from another app: when the files are added or cancelled, go back there. */
    private var openedToShare = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedToShare = savedInstanceState?.getBoolean(OPENED_TO_SHARE) ?: false
        if (savedInstanceState == null) receive(intent)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.blockScreenshots.collect { block ->
                    if (block) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        setContent {
            val appearance by viewModel.appearance.collectAsStateWithLifecycle()
            CofferTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Screens with a bar of their own handle the window edges themselves; the plain ones get them here.
                    val plain = Modifier.safeDrawingPadding()
                    WelcomeGate(plain) {
                        val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
                        if (!signedIn) {
                            LoginScreen(plain)
                        } else {
                            VaultGate(plain) { UnlockedApp() }
                        }
                    }
                }
            }
        }
    }

    private enum class Page { BROWSER, SETTINGS, SHARE }

    @Composable
    private fun UnlockedApp() {
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        // The same one the browser gets: both are the activity's.
        val browser: BrowserViewModel = hiltViewModel()
        val sharing by viewModel.sharing.collectAsStateWithLifecycle()
        val page = when {
            sharing -> Page.SHARE
            settingsOpen -> Page.SETTINGS
            else -> Page.BROWSER
        }
        // Deeper pages come in from the right and leave the way they came, as in Arcanum.
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val deeper = targetState.ordinal > initialState.ordinal
                slideInHorizontally(tween(SLIDE_MILLIS, easing = EaseInOutCubic)) { if (deeper) it else -it } togetherWith
                    slideOutHorizontally(tween(SLIDE_MILLIS, easing = EaseInOutCubic)) { if (deeper) -it else it }
            },
            label = "page",
        ) { shown ->
            when (shown) {
                Page.SHARE -> ShareScreen(onDone = ::shareDone)
                // Debug builds get a Debug section in Settings, as in Arcanum; release builds have none.
                Page.SETTINGS -> SettingsScreen(
                    onBack = { settingsOpen = false },
                    // From Storage's list of files: back to the browser, in the file's folder.
                    onShowFolder = { folderId ->
                        if (folderId == null) browser.goToRoot() else browser.openFolder(folderId)
                        settingsOpen = false
                    },
                    debugTools = if (BuildConfig.DEBUG) ({ DebugTools() }) else null,
                )
                Page.BROWSER -> BrowserScreen(onLock = viewModel::lock, onSettings = { settingsOpen = true })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPENED_TO_SHARE, openedToShare)
    }

    private fun receive(intent: Intent?) {
        if (intent?.action == AudioPlayback.ACTION_SHOW_PLAYER) {
            playback.expand()
            return
        }
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        openedToShare = true
        if (uris.isEmpty()) {
            Toast.makeText(this, BrowserR.string.share_text_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        viewModel.receiveShare(uris, intent.type)
    }

    private fun shareDone(added: Int) {
        if (added > 0) Toast.makeText(this, resources.getQuantityString(BrowserR.plurals.share_adding, added, added), Toast.LENGTH_SHORT).show()
        if (openedToShare) finish()
    }

    private companion object {
        const val OPENED_TO_SHARE = "openedToShare"
        const val SLIDE_MILLIS = 350
    }
}
