package com.jrs8205.appletvremote

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.content.Intent
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.PlayState
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.jrs8205.appletvremote.service.media.RemoteMediaService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.jrs8205.appletvremote.data.DeviceRepository
import com.jrs8205.appletvremote.data.IdentityRepository
import com.jrs8205.appletvremote.data.KeystoreSecretCipher
import com.jrs8205.appletvremote.data.LgTvRepository
import com.jrs8205.appletvremote.data.SettingsRepository
import com.jrs8205.appletvremote.data.appDataStore
import com.jrs8205.appletvremote.discovery.AndroidSocketConnector
import com.jrs8205.appletvremote.discovery.AndroidNetworkTargets
import com.jrs8205.appletvremote.discovery.NsdDiscovery
import com.jrs8205.appletvremote.remote.ConnectionLog
import com.jrs8205.appletvremote.remote.FileLogSink
import com.jrs8205.appletvremote.remote.RemoteController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(context.appDataStore)
    val deviceRepository = DeviceRepository(context.appDataStore, KeystoreSecretCipher())
    val identityRepository = IdentityRepository(context.appDataStore)
    val lgTvRepository = LgTvRepository(context.appDataStore)
    val logSink = FileLogSink(File(context.filesDir, "logs"))
    val connectionLog = ConnectionLog(sink = logSink::append)
    val discovery = NsdDiscovery(context, connectionLog)
    val remoteController = RemoteController(
        scope = appScope,
        deviceRepository = deviceRepository,
        identityRepository = identityRepository,
        connector = AndroidSocketConnector(context.getSystemService(ConnectivityManager::class.java)),
        networkTargets = AndroidNetworkTargets(context.getSystemService(ConnectivityManager::class.java)),
        discovery = discovery,
        lgTvRepository = lgTvRepository,
        clientName = context.getString(R.string.app_name),
        clientModel = Build.MODEL,
        log = connectionLog,
    )
}

class RemoteApp : Application() {

    lateinit var container: AppContainer
        private set

    private val foreground = MutableStateFlow(false)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // A process killed while the media notification was detached (paused TV) leaves it behind; it is stale by now.
        getSystemService(NotificationManager::class.java)?.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        container = AppContainer(this)
        container.connectionLog.log {
            "app start: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}, code ${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        }
        container.appScope.launch {
            combine(container.remoteController.state, container.settingsRepository.settings, foreground) { state, settings, visible ->
                visible && settings.mediaNotificationEnabled && state.connection == ConnectionState.Ready && state.media.playState != PlayState.INACTIVE
            }.distinctUntilChanged().collect { wanted ->
                // A plain start while the app is visible: Media3 raises the service to the foreground itself once the
                // TV is playing, and a paused TV would never satisfy startForegroundService's deadline.
                if (wanted) {
                    runCatching { startService(Intent(this@RemoteApp, RemoteMediaService::class.java)) }
                        .onSuccess { container.connectionLog.log { "media service started" } }
                        .onFailure { container.connectionLog.log { "media service start failed: $it" } }
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground.value = true
                container.remoteController.onAppForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground.value = false
                container.appScope.launch {
                    val keepAlive = container.settingsRepository.settings.first().mediaNotificationEnabled
                    container.remoteController.onAppBackground(keepAlive)
                }
            }
        })
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as RemoteApp).container
