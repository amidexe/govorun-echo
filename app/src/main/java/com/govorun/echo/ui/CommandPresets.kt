package com.govorun.echo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.Lightning
import com.adamglin.phosphoricons.regular.LockSimple
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Question
import com.adamglin.phosphoricons.regular.Sparkle
import com.govorun.echo.AppEntry
import com.govorun.echo.R
import com.govorun.echo.commands.*
import com.product.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CommandTemplate.title: Int get() = when (this) {
    CommandTemplate.Calendar -> R.string.commands_preset_calendar
    CommandTemplate.Alarm -> R.string.commands_preset_alarm
    CommandTemplate.Timer -> R.string.commands_preset_timer
    CommandTemplate.Yandex -> R.string.commands_yandex_title
}

private val CommandTemplate.phraseExample: Int get() = when (this) {
    CommandTemplate.Calendar -> R.string.commands_calendar_phrases_example
    CommandTemplate.Alarm -> R.string.commands_alarm_phrases_example
    CommandTemplate.Timer -> R.string.commands_timer_phrases_example
    CommandTemplate.Yandex -> R.string.commands_yandex_phrases_example
}

private val CommandTemplate.usesSmartFunctions: Boolean get() = this != CommandTemplate.Yandex

/** One list; every template selection creates a new independent command. */
@Composable
internal fun CommandsScreen(
    commands: List<Command>,
    onBack: () -> Unit,
    onOpen: (Command) -> Unit,
    onTemplate: (CommandTemplate) -> Unit,
    onHistory: () -> Unit,
    onAdvanced: () -> Unit,
) {
    val context = LocalContext.current
    var templates by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    ProductScaffold(title = stringResource(R.string.commands_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Section)) {
            if (commands.isNotEmpty()) item {
                Button(
                    onClick = { context.startActivity(AppEntry.commandCapture(context)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = productButtonColors(),
                ) {
                    Icon(PhosphorIcons.Regular.Microphone, contentDescription = null)
                    Spacer(Modifier.width(ProductTokens.Spacing.Sm))
                    Text(stringResource(R.string.commands_check_voice))
                }
            }
            item {
                SettingsSection("", showTitle = false) {
                    commands.forEach { command ->
                        SettingsLinkRow(PhosphorIcons.Regular.Lightning, command.name,
                            command.phrases.joinToString(", "), onClick = { onOpen(command) })
                        ProductDivider()
                    }
                    SettingsLinkRow(PhosphorIcons.Regular.Plus, stringResource(R.string.commands_add_template),
                        onClick = { templates = true })
                }
            }
            item {
                SettingsSection("", showTitle = false) {
                    SettingsLinkRow(PhosphorIcons.Regular.FileText, stringResource(R.string.commands_history), onClick = onHistory)
                    ProductDivider()
                    SettingsLinkRow(PhosphorIcons.Regular.Lightning, stringResource(R.string.commands_other_recipes),
                        summary = stringResource(R.string.commands_other_recipes_summary), onClick = onAdvanced)
                    ProductDivider()
                    SettingsLinkRow(PhosphorIcons.Regular.Question, stringResource(R.string.commands_launch_title),
                        onClick = { help = true })
                }
            }
        }
    }
    if (help) {
        ProductSheet(stringResource(R.string.commands_launch_title), onDismissRequest = { help = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = ProductTokens.Spacing.Section),
                verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
                Text(stringResource(R.string.commands_launch_help), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { help = false }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.help_close))
                }
            }
        }
    }
    if (templates) ProductChoiceSheet<CommandTemplate?>(title = stringResource(R.string.commands_add_template),
        options = CommandTemplate.entries, selected = null,
        optionLabel = { context.getString(checkNotNull(it).title) }, onDismissRequest = { templates = false },
        onSelected = { chosen -> chosen?.let { templates = false; onTemplate(it) } },
        optionDescription = {
            when {
                it?.usesSmartFunctions == true -> context.getString(R.string.commands_preset_smart_required)
                it == CommandTemplate.Yandex && !YandexHome.enabled -> context.getString(R.string.commands_preset_unavailable)
                it == CommandTemplate.Yandex -> context.getString(R.string.commands_preset_yandex_summary)
                else -> null
            }
        },
        optionEnabled = { it != CommandTemplate.Yandex || YandexHome.enabled },
        optionIcon = { if (it?.usesSmartFunctions == true) PhosphorIcons.Regular.Sparkle else null })
}

@Composable
internal fun CommandTemplateEditor(template: CommandTemplate, onBack: () -> Unit, onSaved: () -> Unit) {
    val title = stringResource(template.title)
    val command = remember(template) { template.create(title) }
    CommandEditorScreen(command, onBack, onSaved)
}

/** A native command has a fixed type. Only the person's name, phrase and calendar need editing. */
@Composable
internal fun CommandEditorScreen(existing: Command?, onBack: () -> Unit, onSaved: () -> Unit) {
    val template = existing?.let(CommandTemplate::of)
    if (existing == null || template == null) {
        AdvancedCommandEditorScreen(existing, onBack, onSaved)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CommandStore(context) }
    val saved = remember { store.commands().any { it.id == existing.id } }
    var name by remember { mutableStateOf(existing.name) }
    var phrase by remember { mutableStateOf(existing.phrases.joinToString(", ")) }
    var notify by remember { mutableStateOf(existing.notifyResult) }
    var blockOnLockScreen by remember { mutableStateOf(existing.blockOnLockScreen) }
    var target by remember { mutableStateOf(existing.values[EventCalendars.TARGET].orEmpty()) }
    val yandex = template == CommandTemplate.Yandex
    var scenarioId by remember { mutableStateOf(existing.values[YandexHome.TARGET].orEmpty()) }
    var scenarioName by remember { mutableStateOf(existing.values[YandexHome.TARGET_NAME].orEmpty()) }
    var scenarioReady by remember { mutableStateOf(false) }
    var calendars by remember { mutableStateOf(emptyList<EventCalendars.Target>()) }
    var choosing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var policy by remember { mutableStateOf(CommandPolicy.current()) }
    var pendingSave by remember { mutableStateOf<Command?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        pendingSave?.let { command ->
            store.save(command.copy(notifyResult = allowed))
            pendingSave = null
            onSaved()
        }
    }
    fun load(choose: Boolean) {
        scope.launch {
            calendars = withContext(Dispatchers.IO) { EventCalendars.writable(context) }
            choosing = choose && calendars.isNotEmpty()
            if (choose && calendars.isEmpty()) problem = context.getString(R.string.commands_calendar_none)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (EventCalendars.allowed(context)) load(true) else problem = context.getString(R.string.commands_calendar_permission)
    }
    LaunchedEffect(Unit) {
        if (!yandex) policy = withContext(Dispatchers.IO) { CommandPolicy.refresh() }
        if (template == CommandTemplate.Calendar) load(false)
    }
    ProductScaffold(title = stringResource(template.title), onBack = onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Section)) {
            if (yandex) item {
                YandexCommandSetup(scenarioId, scenarioName, blockOnLockScreen, onValidated = { scenarioReady = it }, onSelected = {
                    if (name.isBlank() || name == context.getString(template.title) || name == scenarioName) name = it.name
                    scenarioId = it.id
                    scenarioName = it.name
                    scenarioReady = true
                    problem = null
                })
            }
            if (!yandex || YandexHome.enabled) item {
                SettingsSection("", showTitle = false) {
                    Column(Modifier.padding(ProductTokens.Spacing.Section), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Section)) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.commands_name)) },
                            supportingText = { Text(stringResource(R.string.commands_name_hint)) })
                        OutlinedTextField(phrase, { phrase = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.commands_trigger)) },
                            supportingText = {
                                Text(stringResource(R.string.commands_phrases_hint, stringResource(template.phraseExample)))
                            })
                    }
                    if (template == CommandTemplate.Calendar) {
                        SettingsLinkRow(PhosphorIcons.Regular.Lightning, stringResource(R.string.commands_calendar_target),
                            summary = stringResource(R.string.commands_calendar_sync_hint),
                            value = calendars.firstOrNull { it.key == target }?.name ?: stringResource(R.string.commands_calendar_choose),
                            onClick = { if (EventCalendars.allowed(context)) load(true) else permission.launch(EventCalendars.permissions) })
                    }
                    SwitchRow(PhosphorIcons.Regular.Bell, R.string.commands_notify, null, notify) { notify = it }
                    CommandLockScreenSwitch(blockOnLockScreen) { blockOnLockScreen = it }
                }
            }
            if (template.usesSmartFunctions) item {
                Column(Modifier.padding(horizontal = ProductTokens.Spacing.Section),
                    verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Sm)) {
                        Icon(PhosphorIcons.Regular.Sparkle, contentDescription = null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.commands_preset_smart_footer), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.commands_smart_limits, policy.maximumCharacters, policy.maximumRecordingSeconds),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Section)) {
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!yandex || YandexHome.enabled) Button(onClick = {
                        val phrases = phrase.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
                        val conflict = store.commands().filter { it.id != existing.id }.firstOrNull { other ->
                            other.phrases.any { old -> phrases.any { Matcher.phraseKey(old) == Matcher.phraseKey(it) } }
                        }
                        when {
                            name.isBlank() -> problem = context.getString(R.string.commands_need_name)
                            phrases.isEmpty() || phrases.any { Matcher.phraseKey(it).isEmpty() } -> problem = context.getString(R.string.commands_need_phrases)
                            conflict != null -> problem = context.getString(R.string.commands_trigger_used, conflict.name)
                            template == CommandTemplate.Calendar && calendars.none { it.key == target } -> problem = context.getString(R.string.commands_calendar_target_required)
                            yandex && !scenarioReady -> problem = context.getString(R.string.commands_yandex_choose)
                            else -> {
                                val values = when (template) {
                                    CommandTemplate.Calendar -> mapOf(EventCalendars.TARGET to target)
                                    CommandTemplate.Yandex -> mapOf(YandexHome.TARGET to scenarioId, YandexHome.TARGET_NAME to scenarioName)
                                    else -> emptyMap()
                                }
                                val command = existing.copy(name = name.trim(), phrases = phrases, recipeId = template.recipe, actionId = existing.actionId, prefix = !yandex, discard = false,
                                    values = values, notifyResult = notify, blockOnLockScreen = blockOnLockScreen)
                                if (notify && android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    pendingSave = command
                                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else { store.save(command); onSaved() }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = productButtonColors()) { Text(stringResource(R.string.action_save)) }
                    if (saved) TextButton(onClick = { store.delete(existing.id); onSaved() }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (choosing) ProductChoiceSheet(title = stringResource(R.string.commands_calendar_target), options = calendars,
        selected = calendars.firstOrNull { it.key == target } ?: calendars.first(), optionLabel = { "${it.name} · ${it.account}" },
        onDismissRequest = { choosing = false }, onSelected = { target = it.key; choosing = false; problem = null })
}

@Composable
internal fun CommandLockScreenSwitch(blocked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(PhosphorIcons.Regular.LockSimple, R.string.commands_block_on_lock_screen, null, blocked, onChange)
}
