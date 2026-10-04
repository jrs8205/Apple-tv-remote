package com.jrs8205.appletvremote.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jrs8205.appletvremote.AppContainer
import com.jrs8205.appletvremote.data.Settings
import com.jrs8205.appletvremote.protocol.companion.HidButton
import com.jrs8205.appletvremote.protocol.companion.MediaCommand
import com.jrs8205.appletvremote.protocol.companion.TouchPhase
import com.jrs8205.appletvremote.remote.RemoteState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class RemoteViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<RemoteState> = container.remoteController.state
    val settings: StateFlow<Settings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())

    fun press(button: HidButton) = container.remoteController.press(button)
    fun hold(button: HidButton, holdMs: Long) = container.remoteController.press(button, holdMs)
    fun buttonDown(button: HidButton) = container.remoteController.buttonDown(button)
    fun buttonUp(button: HidButton) = container.remoteController.buttonUp(button)
    fun togglePower() = container.remoteController.togglePower()
    fun media(command: MediaCommand) = container.remoteController.media(command)
    fun skipForward() = container.remoteController.skip(settings.value.skipForwardSeconds.toDouble())
    fun skipBackward() = container.remoteController.skip(-settings.value.skipBackwardSeconds.toDouble())
    fun touch(phase: TouchPhase, x: Int, y: Int) = container.remoteController.touch(phase, x, y)
    fun connect() = container.remoteController.connect()
    fun sendText(text: String) = container.remoteController.sendText(text)
    fun refreshKeyboard() = container.remoteController.refreshKeyboard()
}
