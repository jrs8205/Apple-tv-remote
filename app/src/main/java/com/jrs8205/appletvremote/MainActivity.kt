package com.jrs8205.appletvremote

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.ui.RootViewModel
import com.jrs8205.appletvremote.ui.Screen
import com.jrs8205.appletvremote.ui.onboarding.OnboardingScreen
import com.jrs8205.appletvremote.ui.pairing.PairingScreen
import com.jrs8205.appletvremote.ui.pairing.PairingViewModel
import com.jrs8205.appletvremote.ui.remote.RemoteScreen
import com.jrs8205.appletvremote.ui.remote.RemoteViewModel
import com.jrs8205.appletvremote.ui.settings.HidProbeScreen
import com.jrs8205.appletvremote.ui.settings.LogScreen
import com.jrs8205.appletvremote.ui.settings.SettingsScreen
import com.jrs8205.appletvremote.ui.settings.SettingsViewModel
import com.jrs8205.appletvremote.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val container = appContainer
            return when (modelClass) {
                RootViewModel::class.java -> RootViewModel(container)
                PairingViewModel::class.java -> PairingViewModel(container)
                RemoteViewModel::class.java -> RemoteViewModel(container)
                SettingsViewModel::class.java -> SettingsViewModel(container)
                else -> throw IllegalArgumentException("unknown view model $modelClass")
            } as T
        }
    }

    private val rootViewModel: RootViewModel by viewModels { factory }
    private val pairingViewModel: PairingViewModel by viewModels { factory }
    private val remoteViewModel: RemoteViewModel by viewModels { factory }
    private val settingsViewModel: SettingsViewModel by viewModels { factory }

    @Volatile private var volumeKeysControlTv = false
    @Volatile private var remoteVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            appContainer.settingsRepository.settings.collect { volumeKeysControlTv = it.useHardwareVolumeButtons }
        }
        setContent {
            AppTheme {
                val screen by rootViewModel.screen.collectAsStateWithLifecycle()
                remoteVisible = screen == Screen.REMOTE
                BackHandler(enabled = screen == Screen.SETTINGS || screen == Screen.LOGS || screen == Screen.HID_PROBE) { rootViewModel.back() }
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        Screen.ONBOARDING -> OnboardingScreen(settingsRepository = appContainer.settingsRepository)
                        Screen.PAIRING -> PairingScreen(viewModel = pairingViewModel, onPaired = { rootViewModel.clearOverlays() })
                        Screen.REMOTE -> RemoteScreen(viewModel = remoteViewModel, onOpenSettings = rootViewModel::openSettings)
                        Screen.SETTINGS -> SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { rootViewModel.back() },
                            onOpenLogs = rootViewModel::openLogs,
                            onPairAnother = rootViewModel::openPairing,
                            onOpenHidProbe = rootViewModel::openHidProbe,
                        )
                        Screen.LOGS -> LogScreen(log = appContainer.connectionLog, onBack = { rootViewModel.back() }, onShare = ::shareLog)
                        Screen.HID_PROBE -> HidProbeScreen(onPress = remoteViewModel::press, onBack = { rootViewModel.back() })
                    }
                }
            }
        }
    }

    /** Offers the log files (current and previous) to any app that takes text files. */
    private fun shareLog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sink = appContainer.logSink
            sink.flush()
            val uris = sink.files().map { FileProvider.getUriForFile(this@MainActivity, "$packageName.logs", it) }
            if (uris.isEmpty()) return@launch
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            send.type = "text/plain"
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            withContext(Dispatchers.Main) { startActivity(Intent.createChooser(send, getString(R.string.share_log))) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!volumeKeysControlTv || !remoteVisible) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { remoteViewModel.press(HidButton.VOLUME_UP); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { remoteViewModel.press(HidButton.VOLUME_DOWN); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysControlTv && remoteVisible && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
}
