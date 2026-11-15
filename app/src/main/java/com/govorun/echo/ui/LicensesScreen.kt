package com.govorun.echo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Code
import com.adamglin.phosphoricons.regular.Copyright
import com.adamglin.phosphoricons.regular.Globe
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Package
import com.govorun.echo.R
import com.product.ui.FeedCard
import com.product.ui.ProductDivider
import com.product.ui.ProductScaffold
import com.product.ui.ProductTokens
import com.product.ui.SettingsInfoRow
import com.product.ui.SettingsLinkRow
import com.product.ui.SettingsSection
import com.product.ui.productScreenPadding

/**
 * The app's own licence and the open source parts inside it, read from `assets/licenses.txt`. That file is the notice itself:
 * the "# " heading is the app, a "## " heading starts either a third-party component (copyright lines, "License: <name>", a link,
 * artifacts, notes) or the text of a licence a component names. A release-like build checks that the artifacts listed there are
 * exactly the ones shipped.
 */
internal class Notices(
    val app: Component?,
    val components: List<Component>,
    val licenses: Map<String, String>,
) {
    class Component(
        val name: String,
        val copyrights: List<String>,
        val license: String,
        val link: String?,
        val artifacts: List<String>,
        val notes: List<String>,
    )

    companion object {
        const val FILE = "licenses.txt"
        private const val LICENSE = "License: "
        private val HEADING = Regex("(?m)^(#{1,2}) (.+)$")
        private val ARTIFACT = Regex("[\\w.-]+:[\\w.-]+:[\\w.-]+")

        fun parse(text: String): Notices {
            val headings = HEADING.findAll(text).toList()
            val blocks =
                headings.mapIndexed { index, heading ->
                    val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
                    Triple(heading.groupValues[1] == "#", heading.groupValues[2].trim(), text.substring(heading.range.last + 1, end))
                }
            val (parts, licenses) = blocks.partition { (_, _, body) -> body.lines().any { it.startsWith(LICENSE) } }
            val components = parts.map { (top, name, body) -> top to component(name, body) }
            return Notices(
                app = components.firstOrNull { it.first }?.second,
                components = components.filterNot { it.first }.map { it.second },
                licenses = licenses.associate { (_, name, body) -> name to body.trim('\n') },
            )
        }

        private fun component(
            name: String,
            body: String,
        ): Component {
            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val known = { line: String -> line.startsWith("Copyright") || line.startsWith(LICENSE) || line.startsWith("https://") || ARTIFACT.matches(line) }
            return Component(
                name = name,
                copyrights = lines.filter { it.startsWith("Copyright") },
                license = lines.first { it.startsWith(LICENSE) }.removePrefix(LICENSE),
                link = lines.firstOrNull { it.startsWith("https://") },
                artifacts = lines.filter { ARTIFACT.matches(it) },
                notes = lines.filterNot(known),
            )
        }

        fun load(context: Context): Notices = parse(context.assets.open(FILE).bufferedReader().use { it.readText() })

        /** Licence texts are wrapped for 80 columns: on a phone a paragraph reads better as one line, without ruler lines. */
        fun unwrap(text: String): String =
            text.split(Regex("\n\\s*\n")).map { paragraph ->
                paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.all { c -> c == '-' } }.joinToString(" ")
            }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}

@Composable
internal fun LicensesScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val notices = remember { Notices.load(context) }
    ProductScaffold(title = stringResource(R.string.licenses_screen), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md),
        ) {
            notices.app?.let { app ->
                item {
                    FeedCard {
                        SettingsLinkRow(PhosphorIcons.Regular.Code, app.name, app.license, onClick = { onOpen(app.name) })
                    }
                }
            }
            item {
                SettingsSection(stringResource(R.string.licenses_third_party)) {
                    notices.components.forEachIndexed { index, component ->
                        if (index > 0) ProductDivider()
                        SettingsLinkRow(PhosphorIcons.Regular.Package, component.name, component.license, onClick = { onOpen(component.name) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun LicenseScreen(
    name: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val notices = remember { Notices.load(context) }
    val component = (listOfNotNull(notices.app) + notices.components).firstOrNull { it.name == name }
    ProductScaffold(title = name, onBack = onBack) { padding ->
        if (component == null) return@ProductScaffold
        val text = remember(component) { Notices.unwrap(notices.licenses[component.license].orEmpty()) }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = productScreenPadding(),
            verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md),
        ) {
            item {
                FeedCard {
                    Column {
                        component.copyrights.forEachIndexed { index, line ->
                            if (index > 0) ProductDivider()
                            SettingsInfoRow(PhosphorIcons.Regular.Copyright, line)
                        }
                        component.link?.let { link ->
                            ProductDivider()
                            val title = if (component == notices.app) {
                                stringResource(R.string.licenses_echo_source)
                            } else {
                                link.removePrefix("https://")
                            }
                            SettingsLinkRow(PhosphorIcons.Regular.Globe, title, onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                            })
                        }
                        component.notes.forEach {
                            ProductDivider()
                            SettingsInfoRow(PhosphorIcons.Regular.Info, it)
                        }
                        if (component.artifacts.isNotEmpty()) {
                            ProductDivider()
                            SettingsInfoRow(PhosphorIcons.Regular.Package, stringResource(R.string.licenses_artifacts), component.artifacts.joinToString("\n"))
                        }
                    }
                }
            }
            item {
                SettingsSection(component.license) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = ProductTokens.Spacing.Section, end = ProductTokens.Spacing.Section, bottom = ProductTokens.Spacing.Section),
                    )
                }
            }
        }
    }
}
