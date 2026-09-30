package com.jrs8205.appletvremote.data

import com.jrs8205.appletvremote.lgtv.LgInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LgTvRepositoryTest {

    private val repository = LgTvRepository(FakeDataStore())

    @Test
    fun theInputListSurvivesStorage() = runTest {
        val inputs = listOf(LgInput("HDMI_1", "HDMI 1", connected = false), LgInput("HDMI_2", "Apple TV \"4K\", living room", connected = true))

        repository.setInputs(inputs)

        assertEquals(inputs, repository.settings.first().inputs)
    }

    @Test
    fun noStoredListMeansNoInputs() = runTest {
        assertEquals(emptyList<LgInput>(), repository.settings.first().inputs)
    }
}
