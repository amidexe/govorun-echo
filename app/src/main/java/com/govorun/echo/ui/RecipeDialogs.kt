package com.govorun.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.govorun.echo.R
import com.govorun.echo.commands.Recipe
import com.govorun.echo.commands.Recipes
import com.govorun.echo.commands.Runner
import com.product.ui.productButtonColors

/** What a recipe does, as written: to read, to copy as a starting point for your own, and to delete if it is yours. */
@Composable
internal fun RecipeDialog(
    recipe: Recipe,
    removable: Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showJson by remember(recipe) { mutableStateOf(false) }
    val shown = remember(recipe) { runCatching { org.json.JSONObject(recipe.source).toString(2) }.getOrDefault(recipe.source) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recipe.name) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showJson) {
                    Text(shown, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                } else {
                    for (action in recipe.actions) {
                        Text(action.name, style = MaterialTheme.typography.titleSmall)
                        if (action.request != null || action.intent != null) {
                            val target = remember(action) { runCatching { Runner.destination(action, emptyMap()) }.getOrNull() }
                            Text(target?.label ?: stringResource(R.string.commands_recipe_destination_setup),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(stringResource(R.string.commands_recipe_use), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showJson = !showJson }) {
                    Text(stringResource(if (showJson) R.string.commands_recipe_hide_json else R.string.commands_recipe_show_json))
                }
            }
        },
        confirmButton = {
            Row {
                if (removable) {
                    TextButton(onClick = {
                        onRemove()
                        onDismiss()
                    }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                if (showJson) {
                    TextButton(onClick = {
                        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("recipe", shown))
                        android.widget.Toast.makeText(context, R.string.toast_copied, android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.action_copy)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
internal fun PasteRecipe(
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.commands_recipe_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.commands_recipe_import_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth(), minLines = 6, maxLines = 12, label = { Text("JSON") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { Recipes.addUser(context, text) }
                    .onSuccess {
                        onAdded()
                        onDismiss()
                    }.onFailure { error = it.message }
            }, colors = productButtonColors()) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
