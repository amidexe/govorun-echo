package com.govorun.echo

import androidx.annotation.StringRes

/** Words and numbers every part of Echo shows the same way: a note's error in words, a length as «1:05» or «1:02:07». */
@StringRes
internal fun errorLabel(error: String): Int =
    when (error) {
        "no_speech" -> R.string.error_no_speech
        "needs_authorization" -> R.string.error_needs_authorization
        "not_installed" -> R.string.error_not_installed
        "model_unavailable" -> R.string.error_model_unavailable
        "busy" -> R.string.error_busy
        "postponed" -> R.string.error_postponed
        "unreachable" -> R.string.error_unreachable
        "import_failed" -> R.string.error_import_failed
        "failed_5" -> R.string.error_failed_5
        "failed_-5" -> R.string.error_file_filter_unsupported
        else -> R.string.error_unknown
    }

internal fun formatDuration(ms: Long): String {
    val total = ms / 1_000
    return if (total >= 3_600) {
        "%d:%02d:%02d".format(total / 3_600, total / 60 % 60, total % 60)
    } else {
        "%d:%02d".format(total / 60, total % 60)
    }
}
