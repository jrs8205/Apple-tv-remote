package com.jrs8205.appletvremote.service.media

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.jrs8205.appletvremote.protocol.companion.ConnectionState
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.protocol.companion.MediaCommand
import com.jrs8205.appletvremote.protocol.companion.PlayState
import com.jrs8205.appletvremote.remote.RemoteController
import com.jrs8205.appletvremote.remote.RemoteState

/**
 * A player that never plays anything itself: it mirrors what the Apple TV reports through `_iMC`
 * and forwards transport and volume commands, so the system media controls can drive the TV.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CompanionPlayer(
    private val controller: RemoteController,
    private val statusText: (RemoteState) -> String,
    looper: Looper,
) : SimpleBasePlayer(looper) {

    private var remote: RemoteState = controller.state.value
    /** Whether the notification shows the TV's playback; kept through the TV's brief reports of nothing playing. */
    private var showing: Boolean = remote.hasPlayback

    fun update(state: RemoteState) {
        remote = state
        invalidateState()
    }

    fun show(active: Boolean) {
        showing = active
        invalidateState()
    }

    override fun getState(): State {
        val media = remote.media
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_GET_TIMELINE,
            )
            .addIf(Player.COMMAND_SEEK_TO_NEXT, media.canNext)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, media.canPrevious)
            .build()
        val item = MediaItem.Builder()
            .setMediaId("apple-tv")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(remote.device?.name ?: "Apple TV")
                    .setArtist(statusText(remote))
                    .build(),
            )
            .build()
        // An empty playlist is what makes Media3 take the notification down while nothing is playing.
        val playlist = if (showing)listOf(MediaItemData.Builder("apple-tv").setMediaItem(item).setIsSeekable(false).build()) else emptyList()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setPlaybackState(if (showing)Player.STATE_READY else Player.STATE_IDLE)
            .setPlayWhenReady(media.playState == PlayState.PLAYING, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(VOLUME_STEPS).build())
            .setDeviceVolume(VOLUME_STEPS / 2)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // The separate play and pause commands are obeyed by only some tvOS apps, and the play state reported to us is a
        // guess; the remote's own play/pause button behaves in every app as it does on the Siri Remote.
        controller.log.log { "system media controls: ${if (playWhenReady) "play" else "pause"}" }
        controller.press(HidButton.PLAY_PAUSE)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.media(MediaCommand.NEXT)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.media(MediaCommand.PREVIOUS)
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        controller.press(HidButton.VOLUME_UP)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        controller.press(HidButton.VOLUME_DOWN)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        controller.press(HidButton.MUTE)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        controller.media(MediaCommand.PAUSE)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val VOLUME_STEPS = 16
    }
}

/** True while the TV reports an active playback session over a live connection. */
internal val RemoteState.hasPlayback: Boolean
    get() = connection == ConnectionState.Ready && media.playState != PlayState.INACTIVE
