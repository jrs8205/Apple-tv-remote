package com.jrs8205.appletvremote.lgtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LgInputTest {

    private val hdmi1 = LgInput("HDMI_1", "HDMI 1", connected = true)
    private val appleTv = LgInput("HDMI_2", "Apple TV", connected = true)

    @Test
    fun theInputTheTvLabelsAppleTvIsChosen() {
        assertEquals(appleTv, listOf(hdmi1, appleTv, LgInput("HDMI_3", "Soundbar", connected = true)).appleTv())
    }

    @Test
    fun theLabelIsMatchedRegardlessOfCase() {
        assertEquals("HDMI_2", listOf(hdmi1, appleTv.copy(label = "apple tv 4K")).appleTv()?.id)
    }

    @Test
    fun noInputIsChosenWhenNoneOrSeveralAreLabelledAppleTv() {
        assertNull(listOf(hdmi1, LgInput("HDMI_2", "HDMI 2", connected = true)).appleTv())
        assertNull(listOf(appleTv, appleTv.copy(id = "HDMI_3")).appleTv())
    }
}
