package com.govorun.echo

/** Focused native dialogs count as visible windows without pretending there is a resumed activity. */
internal object CaptureWindows {
    private val focused = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()
    fun focus(owner: Any, visible: Boolean) { if (visible) focused.add(owner) else focused.remove(owner) }
    val visible: Boolean get() = focused.isNotEmpty()
}
