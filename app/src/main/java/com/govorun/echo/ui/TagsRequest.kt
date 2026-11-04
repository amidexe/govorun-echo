package com.govorun.echo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Settings asks the list to open the «Теги» screen, which lives over the list. */
internal object TagsRequest {
    var open by mutableStateOf(false)
}
