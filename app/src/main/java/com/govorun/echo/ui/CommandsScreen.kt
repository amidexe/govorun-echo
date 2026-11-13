package com.govorun.echo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.Lightning
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Trash
import com.govorun.echo.R
import com.govorun.echo.commands.AndroidCommand
import com.govorun.echo.commands.EventDraft
import com.govorun.echo.commands.EventCalendars
import com.govorun.echo.commands.Command
import com.govorun.echo.commands.CommandStore
import com.govorun.echo.commands.Matcher
import com.govorun.echo.commands.Recipe
import com.govorun.echo.commands.Recipes
import com.govorun.echo.commands.Runner
import com.product.ui.ProductChoiceSheet
import com.product.ui.ProductDivider
import com.product.ui.ProductScaffold
import com.product.ui.ProductTokens
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection
import com.product.ui.productButtonColors
import com.product.ui.productScreenPadding
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The list of voice commands, a place to try a phrase, and the recipes. */
@Composable
internal fun AdvancedCommandsScreen(
    commands: List<Command>,
    onBack: () -> Unit,
    onOpen: (Command?) -> Unit,
    onRecipesChanged: () -> Unit,
) {
    val context = LocalContext.current
    var recipes by remember { mutableStateOf(Recipes.all(context)) }
    var phrase by remember { mutableStateOf("") }
    var pasting by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Recipe?>(null) }
    ProductScaffold(title = stringResource(R.string.commands_title), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md),
        ) {
            item {
                SettingsSection(stringResource(R.string.commands_title)) {
                    if (commands.isEmpty()) {
                        SettingsInfoRow(PhosphorIcons.Regular.Lightning, stringResource(R.string.commands_empty_title), stringResource(R.string.commands_empty_text))
                        ProductDivider()
                    }
                    commands.forEach { command ->
                        val recipe = recipes.firstOrNull { it.id == command.recipeId }
                        SettingsLinkRow(
                            PhosphorIcons.Regular.Lightning,
                            command.name,
                            command.phrases.joinToString(", ") + " · " + (recipe?.name ?: command.recipeId),
                            onClick = { onOpen(command) },
                        )
                        ProductDivider()
                    }
                    SettingsLinkRow(PhosphorIcons.Regular.Plus, stringResource(R.string.commands_add), onClick = { onOpen(null) })
                }
            }
            item {
                SettingsSection(stringResource(R.string.commands_try_title)) {
                    Column(Modifier.padding(horizontal = ProductTokens.Spacing.Section, vertical = ProductTokens.Spacing.Md), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
                        OutlinedTextField(phrase, { phrase = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.commands_try_hint)) })
                        if (phrase.isNotBlank()) {
                            Text(preview(context, phrase, commands, recipes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                SettingsSection(stringResource(R.string.commands_recipes_title)) {
                    recipes.filterNot { it.id == com.govorun.echo.commands.YandexHome.RECIPE }.forEach { recipe ->
                        SettingsLinkRow(
                            PhosphorIcons.Regular.FileText,
                            recipe.name,
                            recipe.actions.joinToString(", ") { it.name },
                            onClick = { viewing = recipe },
                        )
                        ProductDivider()
                    }
                    SettingsLinkRow(PhosphorIcons.Regular.Plus, stringResource(R.string.commands_recipe_add), stringResource(R.string.commands_recipe_add_text), onClick = { pasting = true })
                }
            }
        }
    }
    viewing?.let { recipe ->
        RecipeDialog(
            recipe,
            removable = Recipes.isUser(context, recipe.id),
            onRemove = {
                Recipes.removeUser(context, recipe.id)
                recipes = Recipes.all(context)
                onRecipesChanged()
            },
            onDismiss = { viewing = null },
        )
    }
    if (pasting) {
        PasteRecipe(onDismiss = { pasting = false }) {
            recipes = Recipes.all(context)
            onRecipesChanged()
        }
    }
}

private fun preview(
    context: android.content.Context,
    phrase: String,
    commands: List<Command>,
    recipes: List<Recipe>,
): String {
    val match = Matcher.find(phrase, commands) ?: return context.getString(R.string.commands_try_none)
    val recipe = recipes.firstOrNull { it.id == match.command.recipeId } ?: return context.getString(R.string.commands_try_no_recipe, match.command.recipeId)
    val action = recipe.action(match.command.actionId) ?: return context.getString(R.string.commands_try_no_recipe, match.command.actionId)
    val vars = Runner.variables(match.command, match, CommandStore(context).connection(recipe), "test")
    val sent = runCatching { Runner.describe(context, action, vars) }
        .getOrElse { context.getString(R.string.commands_recipe_destination_required) }
    return context.getString(R.string.commands_try_match, match.command.name, match.rest) + "\n\n" + sent
}

/** One command: name, phrases, where it goes, and a way to try it. */
@Composable
internal fun AdvancedCommandEditorScreen(
    existing: Command?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CommandStore(context) }
    val recipes = remember { Recipes.all(context).filterNot { it.id == com.govorun.echo.commands.YandexHome.RECIPE } }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var phrases by remember { mutableStateOf(existing?.phrases?.joinToString(", ").orEmpty()) }
    var recipe by remember { mutableStateOf(recipes.firstOrNull { it.id == (existing?.recipeId ?: "home-assistant") } ?: recipes.first()) }
    var actionId by remember { mutableStateOf(existing?.actionId?.takeIf { recipe.action(it) != null } ?: recipe.actions.first().id) }
    // A new command asks for the whole phrase, unless its action needs what is said after it (a task, a message).
    var prefix by remember { mutableStateOf(existing?.prefix ?: recipe.action(actionId)?.needsRest ?: false) }
    var blockOnLockScreen by remember { mutableStateOf(existing?.blockOnLockScreen ?: false) }
    val connection = remember { mutableStateMapOf<String, String>() }
    val values = remember { mutableStateMapOf<String, String>() }
    var recipeSheet by remember { mutableStateOf(false) }
    var actionSheet by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var calendars by remember { mutableStateOf(emptyList<EventCalendars.Target>()) }
    var calendarSheet by remember { mutableStateOf(false) }

    fun showCalendars() {
        scope.launch {
            calendars = withContext(Dispatchers.IO) { EventCalendars.writable(context) }
            calendarSheet = calendars.isNotEmpty()
            problem = if (calendarSheet) null else context.getString(R.string.commands_calendar_none)
        }
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (EventCalendars.allowed(context)) showCalendars()
        else problem = context.getString(R.string.commands_calendar_permission)
    }

    LaunchedEffect(recipe.id) {
        connection.clear()
        connection.putAll(store.connection(recipe))
    }
    LaunchedEffect(Unit) { existing?.values?.let { values.putAll(it) } }
    val action = recipe.action(actionId) ?: recipe.actions.first()
    val externalAction = action.request != null || action.intent != null
    val destination = if (externalAction) {
        runCatching { Runner.destination(action, Runner.configuredVariables(connection, values)) }.getOrNull()
    } else null
    val androidKind = action.android?.kind
    val calendarAction = androidKind in setOf(AndroidCommand.Kind.EVENT_DRAFT, AndroidCommand.Kind.EVENT, AndroidCommand.Kind.REMINDER)
    val chosenCalendar = calendars.firstOrNull { it.key == values[EventCalendars.TARGET] }
    LaunchedEffect(calendarAction) {
        if (calendarAction) calendars = withContext(Dispatchers.IO) { EventCalendars.writable(context) }
    }

    fun build(): Command =
        Command(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            phrases = phrases.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
            recipeId = recipe.id,
            actionId = action.id,
            values = if (calendarAction) mapOf(EventCalendars.TARGET to values[EventCalendars.TARGET].orEmpty())
            else action.fields.associate { it.id to values[it.id].orEmpty() },
            discard = false,
            notifyResult = existing?.notifyResult ?: true,
            prefix = prefix,
            blockOnLockScreen = blockOnLockScreen,
        )

    ProductScaffold(title = stringResource(if (existing == null) R.string.commands_new else R.string.commands_edit), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md),
        ) {
            item {
                SettingsSection(stringResource(R.string.commands_hear)) {
                    Column(Modifier.padding(horizontal = ProductTokens.Spacing.Section, vertical = ProductTokens.Spacing.Md), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.commands_name)) },
                            supportingText = { Text(stringResource(R.string.commands_name_hint)) })
                        OutlinedTextField(
                            phrases,
                            { phrases = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.commands_trigger)) },
                            supportingText = {
                                val example = stringResource(R.string.commands_recipe_phrases_example)
                                Text(
                                    stringResource(R.string.commands_phrases_hint, example) + " " +
                                        stringResource(R.string.commands_phrases_wildcard_hint),
                                )
                            },
                        )
                        Row(Modifier.fillMaxWidth().toggleable(prefix, role = Role.Switch) { prefix = it }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.commands_prefix), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.commands_prefix_text), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = prefix, onCheckedChange = null)
                        }
                    }
                    CommandLockScreenSwitch(blockOnLockScreen) { blockOnLockScreen = it }
                }
            }
            item {
                SettingsSection(stringResource(R.string.commands_send)) {
                    SettingsLinkRow(PhosphorIcons.Regular.Lightning, stringResource(R.string.commands_where), value = recipe.name, onClick = { recipeSheet = true })
                    if (recipe.actions.size > 1) {
                        ProductDivider()
                        SettingsLinkRow(PhosphorIcons.Regular.Lightning, stringResource(R.string.commands_what), value = action.name, onClick = { actionSheet = true })
                    }
                    if (calendarAction) {
                        ProductDivider()
                        SettingsLinkRow(
                            PhosphorIcons.Regular.Lightning,
                            stringResource(R.string.commands_calendar_target),
                            summary = stringResource(R.string.commands_calendar_sync_hint),
                            value = chosenCalendar?.name ?: stringResource(R.string.commands_calendar_choose),
                            onClick = {
                                if (EventCalendars.allowed(context)) showCalendars()
                                else calendarPermission.launch(EventCalendars.permissions)
                            },
                        )
                        Text(
                            stringResource(R.string.commands_event_draft_network, EventDraft.MAX_INPUT),
                            Modifier.padding(horizontal = ProductTokens.Spacing.Section, vertical = ProductTokens.Spacing.Sm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(Modifier.padding(horizontal = ProductTokens.Spacing.Section, vertical = ProductTokens.Spacing.Md), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
                        recipe.fields.forEach { field ->
                            OutlinedTextField(
                                connection[field.id].orEmpty(),
                                { connection[field.id] = it },
                                Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(field.label) },
                                supportingText = field.hint.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions.Default,
                            )
                        }
                        action.fields.forEach { field ->
                            OutlinedTextField(
                                values[field.id].orEmpty(),
                                { values[field.id] = it },
                                Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(field.label) },
                                supportingText = field.hint.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            )
                        }
                        if (externalAction) {
                            Text(
                                destination?.let { stringResource(R.string.commands_recipe_destination, it.label) }
                                    ?: stringResource(R.string.commands_recipe_destination_required),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (destination?.cleartext == true) {
                                Text(stringResource(R.string.commands_recipe_http),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = ProductTokens.Spacing.Sm), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
                    OutlinedButton(
                        enabled = !testing && (!externalAction || destination != null),
                        onClick = {
                            val command = build()
                            if (calendarAction) {
                                testing = true
                                scope.launch {
                                    val answer = withContext(Dispatchers.IO) { EventDraft.checkCalendar(context, command.values[EventCalendars.TARGET].orEmpty()) }
                                    result = answer.error ?: context.getString(R.string.commands_calendar_test_ready)
                                    testing = false
                                }
                                return@OutlinedButton
                            }
                            val trigger = command.phrases.firstOrNull()?.trimEnd('*') ?: "тест"
                            val sample = if (androidKind != null) {
                                if (command.prefix) "$trigger ${context.getString(androidKind.sample)}" else trigger
                            } else {
                                if (command.prefix) "$trigger проверка" else trigger
                            }
                            val match = Matcher.find(sample, listOf(command))
                            if (match == null) {
                                result = context.getString(R.string.commands_try_none)
                                return@OutlinedButton
                            }
                            val vars = Runner.variables(command, match, connection.toMap(), "test")
                            testing = true
                            result = null
                            scope.launch {
                                val answer = withContext(Dispatchers.IO) {
                                    Runner.sendCommand(context, action, vars, blockOnLockScreen = command.blockOnLockScreen, probe = true)
                                }
                                result = context.getString(R.string.commands_test_result, answer.label(), answer.reply.orEmpty())
                                testing = false
                            }
                        },
                    ) { Text(stringResource(androidKind?.button ?: R.string.commands_test)) }
                    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = productButtonColors(),
                        onClick = {
                            val command = build()
                            when {
                                command.name.isBlank() -> problem = context.getString(R.string.commands_need_name)
                                command.phrases.isEmpty() -> problem = context.getString(R.string.commands_need_phrases)
                                externalAction && destination == null -> problem = context.getString(R.string.commands_recipe_destination_required)
                                calendarAction && chosenCalendar == null -> problem = context.getString(R.string.commands_calendar_target_required)
                                else -> {
                                    store.saveConnection(recipe, connection.toMap())
                                    store.save(command)
                                    onSaved()
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.action_save)) }
                    if (existing != null) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                store.delete(existing.id)
                                onSaved()
                            },
                        ) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    if (recipeSheet) {
        ProductChoiceSheet(
            title = stringResource(R.string.commands_where),
            selected = recipe,
            options = recipes,
            optionLabel = { it.name },
            onSelected = {
                val firstAction = it.actions.first()
                recipe = it
                actionId = firstAction.id
                if (existing == null || firstAction.android != null) {
                    prefix = firstAction.needsRest || firstAction.android != null
                }
                values.clear()
            },
            onDismissRequest = { recipeSheet = false },
        )
    }
    if (actionSheet) {
        ProductChoiceSheet(
            title = stringResource(R.string.commands_what),
            selected = action,
            options = recipe.actions,
            optionLabel = { it.name },
            onSelected = {
                actionId = it.id
                if (existing == null || it.android != null && it.needsRest) prefix = it.needsRest
                values.clear()
            },
            onDismissRequest = { actionSheet = false },
        )
    }
    if (calendarSheet) {
        ProductChoiceSheet<EventCalendars.Target?>(
            title = stringResource(R.string.commands_calendar_choose),
            selected = chosenCalendar,
            options = calendars,
            optionLabel = { it?.name.orEmpty() },
            optionDescription = { it?.account?.takeIf(String::isNotBlank) },
            onSelected = { target ->
                if (target != null) values[EventCalendars.TARGET] = target.key
                calendarSheet = false
                problem = null
            },
            onDismissRequest = { calendarSheet = false },
        )
    }
}
