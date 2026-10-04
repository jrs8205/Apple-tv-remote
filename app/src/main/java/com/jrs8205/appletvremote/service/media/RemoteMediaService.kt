package com.jrs8205.appletvremote.service.media

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.jrs8205.appletvremote.MainActivity
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.appContainer
import com.jrs8205.appletvremote.protocol.companion.PlayState
import com.jrs8205.appletvremote.remote.RemoteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shows the Apple TV's playback in the notification shade and on the lock screen. The session's
 * player is [CompanionPlayer]; skip buttons come and go with what the TV allows.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RemoteMediaService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private var player: CompanionPlayer? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        val container = appContainer
        val companionPlayer = CompanionPlayer(container.remoteController, ::statusText, mainLooper)
        player = companionPlayer
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The service is started with a plain intent, so nothing else would register the session with it.
        session = MediaSession.Builder(this, companionPlayer)
            .setSessionActivity(launch)
            .setCallback(SkipCallback())
            .build()
            .also(::addSession)
        scope.launch {
            combine(container.remoteController.state, container.settingsRepository.settings) { state, settings -> state to settings }
                .collect { (state, settings) ->
                    companionPlayer.update(state)
                    session?.setMediaButtonPreferences(skipButtons(state, settings.skipBackwardSeconds, settings.skipForwardSeconds))
                    if (!settings.mediaNotificationEnabled) stop("media notification turned off")
                }
        }
        scope.launch {
            // Lives only while the TV plays or pauses over a live connection: Android stops an idle service in the
            // background anyway, so playback that starts while the app is hidden gets its notification back once the
            // app is visible again. A menu the TV opens over the video reads as nothing playing for a moment, which
            // must not take the lock screen controls away. The emptied playlist makes Media3 withdraw the notification.
            container.remoteController.state.map { it.hasPlayback }.withDropGrace(PLAYBACK_GRACE_MS).collect { active ->
                companionPlayer.show(active)
                if (!active) stop("nothing playing for ${PLAYBACK_GRACE_MS / 1000} s")
            }
        }
    }

    private fun stop(reason: String) {
        if (stopping) return
        stopping = true
        appContainer.connectionLog.log { "media service stopping: $reason" }
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The process may be killed as soon as this returns, so the notification goes now rather than in onDestroy.
        appContainer.connectionLog.log { "media service stopping: app removed from the recent apps" }
        releaseSession()
        stopSelf()
    }

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

    private fun releaseSession() {
        scope.cancel()
        val current = session ?: return
        session = null
        removeSession(current)
        current.player.release()
        current.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
    }

    private fun statusText(state: RemoteState): String = getString(
        when (state.media.playState) {
            PlayState.PLAYING -> R.string.media_playing
            PlayState.PAUSED -> R.string.media_paused
            else -> R.string.media_idle
        },
    )

    private fun skipButtons(state: RemoteState, backSeconds: Int, forwardSeconds: Int): ImmutableList<CommandButton> {
        val buttons = ArrayList<CommandButton>()
        if (state.media.canSkipBackward) {
            buttons += CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                .setDisplayName(getString(R.string.cd_skip_backward, backSeconds))
                .setSessionCommand(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
                .build()
        }
        if (state.media.canSkipForward) {
            buttons += CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                .setDisplayName(getString(R.string.cd_skip_forward, forwardSeconds))
                .setSessionCommand(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .build()
        }
        return ImmutableList.copyOf(buttons)
    }

    private inner class SkipCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(sessionCommands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val container = appContainer
            scope.launch {
                val settings = container.settingsRepository.settings.first()
                when (customCommand.customAction) {
                    COMMAND_SKIP_BACK -> container.remoteController.skip(-settings.skipBackwardSeconds.toDouble())
                    COMMAND_SKIP_FORWARD -> container.remoteController.skip(settings.skipForwardSeconds.toDouble())
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        /** How long the TV may report nothing playing before the notification goes. */
        const val PLAYBACK_GRACE_MS = 10_000L
        const val COMMAND_SKIP_BACK = "com.jrs8205.appletvremote.SKIP_BACK"
        const val COMMAND_SKIP_FORWARD = "com.jrs8205.appletvremote.SKIP_FORWARD"
    }
}
