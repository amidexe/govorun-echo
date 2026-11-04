package com.govorun.echo.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Second-order high-pass at 250 Hz, used only to measure how loud the voice is: engine and road rumble sit far below speech but carry
 * most of the energy, so without it a car keeps the level bars at the top. The recording itself is never filtered.
 */
internal class RumbleFilter(
    rate: Int,
    cutoffHz: Double = 250.0,
) {
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        val w = 2 * PI * cutoffHz / rate
        val alpha = sin(w) / (2 * 0.7071)
        val a0 = 1 + alpha
        b0 = ((1 + cos(w)) / 2 / a0).toFloat()
        b1 = (-(1 + cos(w)) / a0).toFloat()
        b2 = b0
        a1 = (-2 * cos(w) / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}
