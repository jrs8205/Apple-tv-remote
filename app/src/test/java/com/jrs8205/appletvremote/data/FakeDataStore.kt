package com.jrs8205.appletvremote.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Preferences held in memory; [readFailure], when set, makes every read fail the way a broken file would. */
class FakeDataStore : DataStore<Preferences> {

    private val store = MutableStateFlow(emptyPreferences())
    private val writes = Mutex()
    @Volatile var readFailure: Throwable? = null

    override val data: Flow<Preferences> = store.map { readFailure?.let { throw it } ?: it }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = writes.withLock {
        store.value = transform(store.value).toPreferences()
        store.value
    }
}

class PlainCipher : SecretCipher {
    override fun wrap(secret: ByteArray): String = secret.joinToString("") { "%02x".format(it) }
    override fun unwrap(wrapped: String): ByteArray = wrapped.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
