package com.jrs8205.appletvremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jrs8205.appletvremote.lgtv.LgInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class NavigationMode { TOUCHPAD, SWIPE, DPAD }

data class Settings(
    val navigationMode: NavigationMode = NavigationMode.TOUCHPAD,
    val useHardwareVolumeButtons: Boolean = false,
    val skipForwardSeconds: Int = 10,
    val skipBackwardSeconds: Int = 10,
    val hapticsEnabled: Boolean = true,
    val mediaNotificationEnabled: Boolean = false,
    val onboardingSeen: Boolean = false,
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<Settings> = dataStore.data.map { prefs ->
        Settings(
            navigationMode = prefs[NAVIGATION_MODE]?.let { runCatching { NavigationMode.valueOf(it) }.getOrNull() } ?: NavigationMode.TOUCHPAD,
            useHardwareVolumeButtons = prefs[HARDWARE_VOLUME] ?: false,
            skipForwardSeconds = prefs[SKIP_FORWARD] ?: 10,
            skipBackwardSeconds = prefs[SKIP_BACKWARD] ?: 10,
            hapticsEnabled = prefs[HAPTICS] ?: true,
            mediaNotificationEnabled = prefs[MEDIA_NOTIFICATION] ?: false,
            onboardingSeen = prefs[ONBOARDING_SEEN] ?: false,
        )
    }

    suspend fun setNavigationMode(mode: NavigationMode) = dataStore.edit { it[NAVIGATION_MODE] = mode.name }
    suspend fun setUseHardwareVolumeButtons(enabled: Boolean) = dataStore.edit { it[HARDWARE_VOLUME] = enabled }
    suspend fun setSkipForwardSeconds(seconds: Int) = dataStore.edit { it[SKIP_FORWARD] = seconds }
    suspend fun setSkipBackwardSeconds(seconds: Int) = dataStore.edit { it[SKIP_BACKWARD] = seconds }
    suspend fun setHapticsEnabled(enabled: Boolean) = dataStore.edit { it[HAPTICS] = enabled }
    suspend fun setMediaNotificationEnabled(enabled: Boolean) = dataStore.edit { it[MEDIA_NOTIFICATION] = enabled }
    suspend fun setOnboardingSeen() = dataStore.edit { it[ONBOARDING_SEEN] = true }

    private companion object {
        val NAVIGATION_MODE = stringPreferencesKey("navigation_mode")
        val HARDWARE_VOLUME = booleanPreferencesKey("use_hardware_volume_buttons")
        val SKIP_FORWARD = intPreferencesKey("skip_forward_seconds")
        val SKIP_BACKWARD = intPreferencesKey("skip_backward_seconds")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val MEDIA_NOTIFICATION = booleanPreferencesKey("media_notification_enabled")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
    }
}

/** One paired Apple TV at a time, the same as the reference app. */
class DeviceRepository(private val dataStore: DataStore<Preferences>, cipher: SecretCipher) {

    private val codec = PairedDeviceCodec(cipher)

    val device: Flow<PairedDevice?> = dataStore.data.map { prefs ->
        val stored = StoredDevice(
            name = prefs[NAME] ?: return@map null,
            host = prefs[HOST] ?: return@map null,
            port = prefs[PORT] ?: return@map null,
            pairingId = prefs[PAIRING_ID] ?: return@map null,
            displayName = prefs[DISPLAY_NAME] ?: return@map null,
            wrappedSeed = prefs[SEED] ?: return@map null,
            accessoryIdHex = prefs[ACCESSORY_ID] ?: return@map null,
            accessoryPublicKeyHex = prefs[ACCESSORY_KEY] ?: return@map null,
        )
        codec.decode(stored)
    }

    suspend fun save(device: PairedDevice) {
        val stored = codec.encode(device)
        dataStore.edit { prefs ->
            prefs[NAME] = stored.name
            prefs[HOST] = stored.host
            prefs[PORT] = stored.port
            prefs[PAIRING_ID] = stored.pairingId
            prefs[DISPLAY_NAME] = stored.displayName
            prefs[SEED] = stored.wrappedSeed
            prefs[ACCESSORY_ID] = stored.accessoryIdHex
            prefs[ACCESSORY_KEY] = stored.accessoryPublicKeyHex
        }
    }

    suspend fun updateAddress(host: String, port: Int) {
        dataStore.edit { prefs ->
            prefs[HOST] = host
            prefs[PORT] = port
        }
    }

    suspend fun forget() {
        dataStore.edit { prefs ->
            prefs.remove(NAME)
            prefs.remove(HOST)
            prefs.remove(PORT)
            prefs.remove(PAIRING_ID)
            prefs.remove(DISPLAY_NAME)
            prefs.remove(SEED)
            prefs.remove(ACCESSORY_ID)
            prefs.remove(ACCESSORY_KEY)
        }
    }

    private companion object {
        val NAME = stringPreferencesKey("device_name")
        val HOST = stringPreferencesKey("device_host")
        val PORT = intPreferencesKey("device_port")
        val PAIRING_ID = stringPreferencesKey("device_pairing_id")
        val DISPLAY_NAME = stringPreferencesKey("device_display_name")
        val SEED = stringPreferencesKey("device_wrapped_seed")
        val ACCESSORY_ID = stringPreferencesKey("device_accessory_id")
        val ACCESSORY_KEY = stringPreferencesKey("device_accessory_key")
    }
}

data class LgTvSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val macAddress: String? = null,
    val clientKey: String? = null,
    val inputId: String = "HDMI_1",
    /** The inputs the TV listed when pairing, so the settings can name them. */
    val inputs: List<LgInput> = emptyList(),
    /** SPKI SHA-256 of the TV's certificate, learned when pairing; later connections accept only this key. */
    val certificate: String? = null,
)

/** The LG webOS TV that the Apple TV hangs off, used only to wake the chain over HDMI-CEC. */
class LgTvRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<LgTvSettings> = dataStore.data.map { prefs ->
        LgTvSettings(
            enabled = prefs[ENABLED] ?: false,
            host = prefs[HOST] ?: "",
            macAddress = prefs[MAC],
            clientKey = prefs[CLIENT_KEY],
            inputId = prefs[INPUT] ?: "HDMI_1",
            inputs = prefs[INPUTS]?.let(::decodeInputs).orEmpty(),
            certificate = prefs[CERTIFICATE],
        )
    }

    suspend fun setEnabled(enabled: Boolean) = dataStore.edit { it[ENABLED] = enabled }
    suspend fun setHost(host: String) = dataStore.edit { it[HOST] = host.trim() }
    suspend fun setMacAddress(mac: String?) = dataStore.edit { if (mac.isNullOrBlank()) it.remove(MAC) else it[MAC] = mac }
    suspend fun setClientKey(key: String?) = dataStore.edit { if (key.isNullOrBlank()) it.remove(CLIENT_KEY) else it[CLIENT_KEY] = key }
    suspend fun setInputId(inputId: String) = dataStore.edit { it[INPUT] = inputId }
    suspend fun setInputs(inputs: List<LgInput>) = dataStore.edit { it[INPUTS] = encodeInputs(inputs) }
    suspend fun setCertificate(spkiSha256: String?) = dataStore.edit { if (spkiSha256.isNullOrBlank()) it.remove(CERTIFICATE) else it[CERTIFICATE] = spkiSha256 }

    private companion object {
        val ENABLED = booleanPreferencesKey("lg_enabled")
        val HOST = stringPreferencesKey("lg_host")
        val MAC = stringPreferencesKey("lg_mac")
        val CLIENT_KEY = stringPreferencesKey("lg_client_key")
        val INPUT = stringPreferencesKey("lg_input")
        val INPUTS = stringPreferencesKey("lg_inputs")
        val CERTIFICATE = stringPreferencesKey("lg_certificate")

        fun encodeInputs(inputs: List<LgInput>): String =
            JSONArray(inputs.map { JSONObject().put("id", it.id).put("label", it.label).put("connected", it.connected) }).toString()

        fun decodeInputs(text: String): List<LgInput> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                LgInput(item.getString("id"), item.getString("label"), item.optBoolean("connected", false))
            }
        }.getOrDefault(emptyList())
    }
}

/** The random identifier this phone reports as `_pubID`; created once and kept. */
class IdentityRepository(private val dataStore: DataStore<Preferences>) {

    suspend fun publicId(): String {
        dataStore.data.first()[PUBLIC_ID]?.let { return it }
        val created = UUID.randomUUID().toString()
        dataStore.edit { prefs -> if (prefs[PUBLIC_ID] == null) prefs[PUBLIC_ID] = created }
        return dataStore.data.first()[PUBLIC_ID] ?: created
    }

    private companion object {
        val PUBLIC_ID = stringPreferencesKey("public_id")
    }
}
