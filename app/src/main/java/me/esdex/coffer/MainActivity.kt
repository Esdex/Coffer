package me.esdex.coffer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import me.esdex.coffer.core.ui.components.LocalLockFlight
import me.esdex.coffer.core.ui.components.LockFlight
import me.esdex.coffer.core.ui.components.LockFlightOverlay
import me.esdex.coffer.core.ui.components.NoKeyboardLearning
import me.esdex.coffer.core.ui.theme.CofferTheme
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.debug.DebugTools
import me.esdex.coffer.feature.browser.BrowserScreen
import me.esdex.coffer.feature.browser.BrowserViewModel
import me.esdex.coffer.feature.browser.R as BrowserR
import me.esdex.coffer.feature.browser.ShareScreen
import me.esdex.coffer.feature.browser.viewer.audio.AudioPlayback
import me.esdex.coffer.feature.onboarding.LoginScreen
import me.esdex.coffer.feature.onboarding.WelcomeGate
import me.esdex.coffer.feature.onboarding.vault.VaultGate
import me.esdex.coffer.feature.settings.SettingsScreen

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
              // The padlock that flies to the unlock screen when the user locks, drawn over everything.
              val lockFlight = remember { LockFlight() }
              // Left mid-flight, as with Home at once: the flight ends there, and the files held on screen for it
              // go before the recent apps screen takes its picture (audit 3, AND3-5).
              val lifecycle = LocalLifecycleOwner.current.lifecycle
              DisposableEffect(lifecycle) {
                  val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE && lockFlight.flying) lockFlight.landed() }
                  lifecycle.addObserver(observer)
                  onDispose { lifecycle.removeObserver(observer) }
              }
              // Every field in the app asks the keyboard not to learn what is typed (audit 1, L6).
              NoKeyboardLearning {
              CompositionLocalProvider(LocalLockFlight provides lockFlight) {
               Box(Modifier.fillMaxSize()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Screens with a bar of their own handle the window edges themselves; the plain ones get them here.
                    val plain = Modifier.safeDrawingPadding()
                    WelcomeGate(plain) {
                        val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
                        // Signing in fades into the vault; signing out fades back.
                        AnimatedContent(signedIn, transitionSpec = { fadeIn(Motion.screen()) togetherWith fadeOut(Motion.standard()) }, label = "signed in") { inside ->
                            if (!inside) {
                                LoginScreen(plain)
                            } else {
                                VaultGate(plain) { UnlockedApp() }
                            }
                        }
                    }
                }
                LockFlightOverlay(lockFlight)
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
                slideInHorizontally(Motion.screen()) { if (deeper) it else -it } togetherWith
                    slideOutHorizontally(Motion.screen()) { if (deeper) -it else it }
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
    }
}
