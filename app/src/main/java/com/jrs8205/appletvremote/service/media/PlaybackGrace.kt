package com.jrs8205.appletvremote.service.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * Passes `true` on at once and `false` only once it has lasted [graceMs]. The Apple TV reports that
 * nothing is playing for a moment whenever one of its own menus opens over the video. Repeats are
 * dropped first: every unrelated state change maps to the same value again and would restart the wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.withDropGrace(graceMs: Long): Flow<Boolean> = distinctUntilChanged().transformLatest { active ->
    if (!active) delay(graceMs)
    emit(active)
}.distinctUntilChanged()
