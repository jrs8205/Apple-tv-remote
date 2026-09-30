package com.jrs8205.appletvremote.lgtv

/** One external input of the LG TV, with the name the TV shows for it, which HDMI-CEC fills in from the device. */
data class LgInput(val id: String, val label: String, val connected: Boolean)

/** The input the TV itself labels as the Apple TV, when exactly one is; the user picks otherwise. */
fun List<LgInput>.appleTv(): LgInput? = filter { it.label.contains("apple tv", ignoreCase = true) }.singleOrNull()
