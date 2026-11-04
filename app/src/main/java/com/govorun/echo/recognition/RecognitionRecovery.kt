package com.govorun.echo.recognition

/** One retry per note while the provider is ready. A late archive load must not miss its turn. */
internal class RecognitionRecovery {
    private val tried = HashSet<String>()
    fun take(ready: Boolean, waitingIds: List<String>): List<String> {
        if (!ready) { tried.clear(); return emptyList() }
        return waitingIds.filter { tried.add(it) }
    }
}
