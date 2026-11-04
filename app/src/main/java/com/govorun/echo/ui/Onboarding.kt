package com.govorun.echo.ui

import androidx.compose.foundation.background
import com.product.ui.SettingsLinkRow
import com.adamglin.phosphoricons.regular.GithubLogo
import com.adamglin.phosphoricons.regular.Storefront
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.SquaresFour
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.Warning
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.TextAa
import com.govorun.echo.R
import com.product.ui.FeedCard
import com.product.ui.ProductDivider
import com.product.ui.ProductTokens
import com.product.ui.SettingsInfoRow
import com.product.ui.productButtonColors

internal enum class OnboardingStep { Welcome, Govorun, Permissions, Quick }

internal data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val govorun: GovorunState = GovorunState.Checking,
    val govorunVersion: String? = null,
    val install: InstallInfo = InstallInfo.DEFAULT,
    val microphone: Boolean = false,
    val selfTest: String? = null,
    val selfTestRunning: Boolean = false,
    val hotkeyServiceOn: Boolean = false,
) {
    val canContinue: Boolean
        get() = step != OnboardingStep.Permissions || microphone
}

internal class OnboardingActions(
    val onBack: () -> Unit,
    val onNext: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onAuthorizeGovorun: () -> Unit,
    val onSelfTest: () -> Unit,
    val onRequestPermissions: () -> Unit,
    val onAddTile: () -> Unit,
    val onOpenAccessibility: () -> Unit,
)

/** The first run, in the same cards, icons and colours as the rest of the app. */
@Composable
internal fun OnboardingScreen(
    state: OnboardingState,
    actions: OnboardingActions,
) {
    val steps = OnboardingStep.entries
    val index = steps.indexOf(state.step)
    // The screen's own Surface, as a Scaffold gives the others: titles take the text colour of the background, not black.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            StepDots(index, steps.size)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                when (state.step) {
                    OnboardingStep.Welcome -> WelcomeStep()
                    OnboardingStep.Govorun -> GovorunStep(state, actions)
                    OnboardingStep.Permissions -> PermissionsStep(state, actions)
                    OnboardingStep.Quick -> QuickStep(actions)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (index > 0) TextButton(onClick = actions.onBack, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.action_back)) }
                Button(onClick = actions.onNext, enabled = state.canContinue, modifier = Modifier.weight(1f).height(52.dp), colors = productButtonColors()) {
                    Text(stringResource(if (state.step == steps.last()) R.string.onb_go else R.string.onb_next), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun StepDots(
    current: Int,
    total: Int,
) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(if (i == current) 24.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun Hero(
    visual: @Composable () -> Unit,
    title: Int,
    body: Int? = null,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Lg)) {
        visual()
        Spacer(Modifier.height(4.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        if (body != null) Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A round badge with an icon: the accent of every step. */
@Composable
private fun IconBadge(icon: ImageVector) {
    Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
    }
}

/** The real Govorun mark on a plain disc, as on Govorun's own icon. */
@Composable
private fun GovorunBadge() {
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_govorun_mark), contentDescription = null, colorFilter = ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFF1F1F1F)), modifier = Modifier.size(52.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomeStep() {
    Hero({ Image(painterResource(R.drawable.ic_echo_art), contentDescription = null, modifier = Modifier.size(104.dp)) }, R.string.onb_welcome_title, R.string.onb_welcome_body)
    FeedCard {
        Column {
            SettingsInfoRow(PhosphorIcons.Regular.TextAa, stringResource(R.string.onb_feat_text_title), stringResource(R.string.onb_feat_text_text))
        }
    }
    Note(stringResource(R.string.onb_welcome_note))
}

@Composable
private fun GovorunStep(
    state: OnboardingState,
    actions: OnboardingActions,
) {
    var showSources by remember(state.govorun) { mutableStateOf(false) }
    val needsInstall = state.govorun == GovorunState.NotInstalled || state.govorun == GovorunState.TooOld
    val title = when (state.govorun) {
        GovorunState.NotInstalled -> R.string.onb_govorun_title
        GovorunState.TooOld -> R.string.onb_govorun_update_title
        GovorunState.Checking -> R.string.onb_govorun_checking
        GovorunState.Error -> R.string.onb_govorun_error
        else -> R.string.onb_govorun_installed
    }
    Hero({ GovorunBadge() }, title, if (state.govorun == GovorunState.NotInstalled) R.string.onb_govorun_body else null)
    val ready = state.govorun == GovorunState.Ready
    val status =
        when (state.govorun) {
            GovorunState.Ready -> stringResource(R.string.onb_govorun_ready)
            GovorunState.NeedsAccess -> stringResource(R.string.onb_govorun_access)
            GovorunState.ModelUnavailable -> stringResource(R.string.onb_govorun_model)
            GovorunState.TooOld -> stringResource(R.string.onb_govorun_old)
            GovorunState.Checking -> stringResource(R.string.onb_govorun_checking)
            GovorunState.Error -> stringResource(R.string.onb_govorun_error)
            else -> stringResource(R.string.onb_govorun_missing)
        }
    FeedCard {
        Column {
            SettingsInfoRow(if (ready) PhosphorIcons.Regular.CheckCircle else PhosphorIcons.Regular.Warning, status)
            if (state.govorun == GovorunState.NeedsAccess) {
                Row(Modifier.padding(start = ProductTokens.Spacing.Section, end = ProductTokens.Spacing.Section, bottom = ProductTokens.Spacing.Lg)) {
                    Button(onClick = actions.onAuthorizeGovorun, colors = productButtonColors()) { Text(stringResource(R.string.action_allow)) }
                }
            }
            if (ready) {
                ProductDivider()
                Column(Modifier.padding(ProductTokens.Spacing.Section), verticalArrangement = Arrangement.spacedBy(ProductTokens.Spacing.Md)) {
                    OutlinedButton(onClick = actions.onSelfTest, enabled = !state.selfTestRunning) { Text(stringResource(R.string.onb_selftest)) }
                    state.selfTest?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    if (needsInstall && state.install.notice.isNotBlank()) {
        FeedCard { Text(state.install.notice, Modifier.padding(ProductTokens.Spacing.Section), style = MaterialTheme.typography.bodyMedium) }
    }
    if (needsInstall) {
        FeedCard {
            SettingsLinkRow(
                PhosphorIcons.Regular.Storefront,
                stringResource(R.string.onb_get_rustore),
                value = if (state.install.rustoreAvailable) null else stringResource(R.string.onb_rustore_soon),
                enabled = state.install.rustoreAvailable,
                onClick = { actions.onOpenUrl(state.install.rustoreUrl) },
            )
        }
        TextButton(onClick = { showSources = !showSources }) { Text(stringResource(R.string.onb_other_sources)) }
        if (showSources) {
            FeedCard {
                SettingsLinkRow(PhosphorIcons.Regular.GithubLogo, stringResource(R.string.onb_get_github), onClick = { actions.onOpenUrl(state.install.githubUrl) })
            }
        }
        Note(stringResource(R.string.onb_govorun_later))
    }
}

@Composable
private fun PermissionsStep(
    state: OnboardingState,
    actions: OnboardingActions,
) {
    Hero({ IconBadge(PhosphorIcons.Regular.Microphone) }, R.string.onb_perm_title, R.string.onb_perm_body)
    FeedCard {
        Column {
            SettingsInfoRow(
                PhosphorIcons.Regular.Microphone,
                stringResource(R.string.onb_perm_mic),
                trailing = {
                    if (state.microphone) {
                        Icon(PhosphorIcons.Regular.CheckCircle, contentDescription = stringResource(R.string.onb_perm_ok), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = actions.onRequestPermissions, colors = productButtonColors()) { Text(stringResource(R.string.action_allow)) }
                    }
                },
            )
            ProductDivider()
            SettingsInfoRow(PhosphorIcons.Regular.Bell, stringResource(R.string.onb_perm_notifications))
        }
    }
}

@Composable
private fun QuickStep(
    actions: OnboardingActions,
) {
    Hero({ IconBadge(PhosphorIcons.Regular.SquaresFour) }, R.string.onb_tile_title, if (com.govorun.echo.BuildConfig.HAS_HOTKEY) R.string.onb_tile_body else R.string.onb_tile_body_tile)
    FeedCard {
        Column {
            SettingsInfoRow(
                PhosphorIcons.Regular.SquaresFour,
                stringResource(R.string.onb_tile_title_row),
                stringResource(R.string.onb_tile_offer_text),
                trailing = { Button(onClick = actions.onAddTile, colors = productButtonColors()) { Text(stringResource(R.string.action_add)) } },
            )
        }
    }
    // An optional lock, offered once here and kept in Settings: the notes are the person's own.
    FeedCard { Column { AppLockSetting() } }
    Note(stringResource(if (com.govorun.echo.BuildConfig.HAS_HOTKEY) R.string.onb_quick_skip else R.string.onb_quick_skip_tile))
}
