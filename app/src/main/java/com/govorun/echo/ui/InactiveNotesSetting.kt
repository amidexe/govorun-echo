package com.govorun.echo.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ClockCountdown
import com.govorun.echo.R
import com.govorun.echo.notes.InactiveNoteAction
import com.govorun.echo.notes.InactiveNoteRule
import com.product.ui.ProductChoiceSheet
import com.product.ui.SettingsLinkRow

@Composable
internal fun InactiveNotesSetting() {
    val context = LocalContext.current
    var rule by remember { mutableStateOf(InactiveNoteRule.read(context)) }
    var choosingAction by remember { mutableStateOf(false) }
    var choosingDaysFor by remember { mutableStateOf<InactiveNoteAction?>(null) }
    fun save(action: InactiveNoteAction, days: Int) {
        if (action != rule.action || days != rule.days) rule = InactiveNoteRule.save(context, action, days)
    }
    fun label(action: InactiveNoteAction) = when (action) {
        InactiveNoteAction.KEEP -> R.string.inactive_keep
        InactiveNoteAction.ARCHIVE -> R.string.inactive_archive
        InactiveNoteAction.TRASH -> R.string.inactive_trash
    }
    val title = stringResource(R.string.inactive_title)
    val value = when (rule.action) {
        InactiveNoteAction.KEEP -> stringResource(R.string.inactive_keep)
        InactiveNoteAction.ARCHIVE -> stringResource(R.string.inactive_archive_after, rule.days)
        InactiveNoteAction.TRASH -> stringResource(R.string.inactive_trash_after, rule.days)
    }
    SettingsLinkRow(PhosphorIcons.Regular.ClockCountdown, title, value = value, onClick = { choosingAction = true })
    if (choosingAction) ProductChoiceSheet(
        title = title, selected = rule.action, options = InactiveNoteAction.entries,
        description = stringResource(R.string.inactive_explanation),
        optionLabel = { context.getString(label(it)) },
        optionDescription = { when (it) {
            InactiveNoteAction.KEEP -> null
            InactiveNoteAction.ARCHIVE -> context.getString(R.string.inactive_archive_detail)
            InactiveNoteAction.TRASH -> context.getString(R.string.inactive_trash_detail)
        } },
        onSelected = { if (it == InactiveNoteAction.KEEP) save(it, rule.days) else choosingDaysFor = it },
        onDismissRequest = { choosingAction = false },
    )
    choosingDaysFor?.let { action ->
        ProductChoiceSheet(
            title = stringResource(label(action)), selected = rule.days, options = InactiveNoteRule.periods,
            description = stringResource(if (action == InactiveNoteAction.TRASH) R.string.inactive_trash_period else R.string.inactive_archive_period),
            optionLabel = { context.getString(R.string.inactive_period, it) },
            onSelected = { save(action, it) }, onDismissRequest = { choosingDaysFor = null },
        )
    }
}
