package com.govorun.echo.playback

/** A proximity event can change an existing playback session, never start one. */
internal class RaiseToListenSession(private val output: Output) {
    interface Output {
        fun monitor(on: Boolean): Boolean
        /** False leaves both the screen and playback position alone. */
        fun enterEar(): Boolean
        fun leaveEar()
        fun rewind()
    }

    private var eligible = false
    private var near = false
    private var atEar = false

    fun eligible(value: Boolean) {
        if (eligible == value) return
        eligible = value
        if (value) {
            eligible = output.monitor(true)
        } else {
            near = false
            if (atEar) output.leaveEar()
            atEar = false
            output.monitor(false)
        }
    }

    fun proximity(value: Boolean) {
        if (!eligible || near == value) return
        near = value
        if (value) {
            atEar = output.enterEar()
            if (atEar) output.rewind()
        } else {
            if (atEar) output.leaveEar()
            atEar = false
        }
    }
}

/** The current playlist may be a clipped preview; never seek outside it to replay the first words. */
internal fun earRewindPosition(positionMs: Long, firstMs: Long): Long = maxOf(firstMs, positionMs - 500L)
