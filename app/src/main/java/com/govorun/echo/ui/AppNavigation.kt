package com.govorun.echo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

internal data class AppPage(
    val key: Int,
    val screen: Screen,
    val argument: String? = null,
    val query: String? = null,
)

/** The key belongs to one opening of a screen, including while a child is on top of it. */
internal class AppNavigation private constructor(pages: List<AppPage>, private var nextKey: Int) {
    constructor() : this(listOf(AppPage(0, Screen.Notes)), 1)

    var pages by mutableStateOf(pages)
        private set
    val current: AppPage get() = pages.last()

    fun open(screen: Screen, argument: String? = null, query: String? = null) {
        pages = pages + AppPage(nextKey++, screen, argument, query)
    }

    fun openFromIntent(screen: Screen, id: String) {
        pages = listOf(pages.first(), AppPage(nextKey++, screen, id))
    }

    fun back(from: AppPage = current) {
        if (current == from && pages.size > 1) pages = pages.dropLast(1)
    }

    fun showNotes() {
        if (pages.size > 1) pages = pages.take(1)
    }

    companion object {
        val Saver = listSaver<AppNavigation, Any?>(
            save = { nav ->
                listOf(nav.nextKey) + nav.pages.flatMap { listOf(it.key, it.screen.name, it.argument, it.query) }
            },
            restore = { saved ->
                val pages = saved.drop(1).chunked(4).map { (key, screen, argument, query) ->
                    AppPage(key as Int, Screen.valueOf(screen as String), argument as String?, query as String?)
                }
                AppNavigation(pages, saved.first() as Int)
            },
        )
    }
}
