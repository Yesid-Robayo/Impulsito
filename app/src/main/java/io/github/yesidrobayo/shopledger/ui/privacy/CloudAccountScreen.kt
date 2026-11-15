package io.github.yesidrobayo.shopledger.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * `Seguridad de la cuenta de la nube`: ayuda a proteger la cuenta de la que dependen las copias.
 *
 * 1. Siempre arriba, la frase contra la suplantación: la app nunca pide la contraseña de Google, y
 *    la única que se escribe en ella es la de un servicio WebDAV, al conectarlo.
 * 2. La cuenta conectada, o `Sin cuenta conectada` con `Elegir dónde guardar`. Si se perdió el
 *    acceso, `Reconectar la nube` va sobre la lista.
 * 3. `Lista de la cuenta`: tres casillas que la persona marca, porque la app no puede saberlo.
 *    Sin destino no se marcan. Con la nube del teléfono, `Abrir {provider}`.
 * 4. `Para no perder la cuenta`: inactividad, espacio y qué hacer si algo pide la contraseña. Con
 *    WebDAV, la contraseña para apps; con la nube del teléfono, las guías de Google, que abre el
 *    navegador solo al tocarlas.
 *
 * Columna centrada de 600 dp en una ventana ancha. Las casillas funcionan sin internet.
 */
@Composable
fun CloudAccountScreen(
    uiState: CloudAccountUiState,
    onAction: (CloudAccountAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val app = stringResource(R.string.app_name)
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.account_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(CloudAccountAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = { ScreenHelpLink(HelpTopic.CLOUD_ACCOUNT) },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth()
                        .padding(bottom = spacing.s24),
                ) {
                    NoticeCard(
                        text = stringResource(R.string.account_password_notice, app),
                        variant = NoticeVariant.INFO,
                        modifier =
                            Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                    )
                    AccountSection(uiState, onAction)
                    ChecklistSection(uiState, app, onAction)
                    KeepSection(uiState, app, onAction)
                }
            }
        }
    }
}

/** La cuenta conectada, sin ella, o que no se pudo leer; y la salida si se perdió el acceso. */
@Composable
private fun AccountSection(uiState: CloudAccountUiState, onAction: (CloudAccountAction) -> Unit) {
    // Mientras se lee, nada: la guía de abajo ya está.
    if (uiState.loading) return
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        when (val destination = uiState.destination) {
            is Fact.Known -> when (val value = destination.value) {
                is BackupDestinationInfo.WebDav ->
                    AccountLine(value.account ?: value.host ?: NO_VALUE_TEXT)

                is BackupDestinationInfo.Slots ->
                    AccountLine(value.providerLabel ?: NO_VALUE_TEXT)

                null -> {
                    Text(
                        stringResource(R.string.account_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    PrimaryActionButton(
                        text = stringResource(R.string.backup_action_choose_destination),
                        onClick = { onAction(CloudAccountAction.ChooseDestination) },
                        variant = ActionVariant.TONAL,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Fact.Unavailable ->
                NoticeCard(
                    text = stringResource(R.string.state_error_read),
                    variant = NoticeVariant.ERROR,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(CloudAccountAction.Refresh) },
                )
        }
        if (uiState.accessLost) {
            PrimaryActionButton(
                text = stringResource(R.string.backup_state_permission_lost),
                onClick = { onAction(CloudAccountAction.Reconnect) },
                variant = ActionVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AccountLine(account: String) {
    Text(
        stringResource(R.string.account_connected, account),
        style = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * `Lista de la cuenta`: cuántos van marcados y las tres casillas con su ayuda. Mientras se lee,
 * su esqueleto tras 200 ms.
 */
@Composable
private fun ChecklistSection(
    uiState: CloudAccountUiState,
    app: String,
    onAction: (CloudAccountAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val counts = LocalAppFormats.current.quantity
    SectionHeader(stringResource(R.string.account_list_title))
    if (uiState.loading) {
        DelayedSkeleton {
            Column(
                Modifier.padding(horizontal = spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                repeat(ITEMS.size) {
                    SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.touchTarget))
                }
            }
        }
        return
    }
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        Text(
            stringResource(
                R.string.account_list_progress,
                counts.formatCount(uiState.checked.size.toLong()),
                counts.formatCount(ITEMS.size.toLong()),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (item in ITEMS) {
            CheckboxRow(
                text = stringResource(item.title),
                checked = item.check in uiState.checked,
                onCheckedChange = { onAction(CloudAccountAction.Mark(item.check, it)) },
                supporting = stringResource(item.help),
                enabled = uiState.canMark,
            )
        }
        Text(
            stringResource(R.string.account_self_marked, app),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val slots = uiState.destination.knownOrNull() as? BackupDestinationInfo.Slots
        if (slots != null) {
            PrimaryActionButton(
                text =
                    stringResource(
                        R.string.backup_check_open_action,
                        slots.providerLabel ?: NO_VALUE_TEXT,
                    ),
                onClick = { onAction(CloudAccountAction.OpenProvider) },
                variant = ActionVariant.TONAL,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.s8),
            )
        }
    }
}

/**
 * `Para no perder la cuenta`. Con WebDAV suma la contraseña para apps. Con la nube del teléfono,
 * que hoy solo es Drive, suma las guías de Google, que abre el navegador: la app no las lee.
 */
@Composable
private fun KeepSection(
    uiState: CloudAccountUiState,
    app: String,
    onAction: (CloudAccountAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val destination = uiState.destination.knownOrNull()
    SectionHeader(stringResource(R.string.account_keep_title))
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        GuideText(
            stringResource(R.string.account_inactivity_title),
            stringResource(R.string.account_inactivity_body),
        )
        GuideText(
            stringResource(R.string.account_quota_title),
            stringResource(R.string.account_quota_body),
        )
        GuideText(
            stringResource(R.string.account_password_title),
            stringResource(R.string.account_password_body, app),
        )
        if (destination is BackupDestinationInfo.WebDav) {
            GuideText(
                stringResource(R.string.account_app_password_title),
                stringResource(R.string.account_app_password_body),
            )
        }
    }
    if (destination is BackupDestinationInfo.Slots) {
        SectionHeader(stringResource(R.string.account_guides_title))
        Column(Modifier.padding(horizontal = spacing.s16)) {
            Text(
                stringResource(R.string.account_guides_help, app),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for ((guide, label) in GUIDES) {
                InlineTextButton(
                    stringResource(label),
                    { onAction(CloudAccountAction.OpenGuide(guide)) },
                )
            }
        }
    }
}

@Composable
private fun GuideText(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Un punto de la lista, con su texto y su ayuda. */
private class ChecklistItem(val check: CloudAccountCheck, val title: Int, val help: Int)

private val ITEMS =
    listOf(
        ChecklistItem(
            CloudAccountCheck.TWO_STEP,
            R.string.account_item_two_step,
            R.string.account_item_two_step_help,
        ),
        ChecklistItem(
            CloudAccountCheck.RECOVERY_EMAIL,
            R.string.account_item_email,
            R.string.account_item_email_help,
        ),
        ChecklistItem(
            CloudAccountCheck.RECOVERY_PHONE,
            R.string.account_item_phone,
            R.string.account_item_phone_help,
        ),
    )

/** Las guías de la nube del teléfono, en el orden de la lista. */
private val GUIDES =
    listOf(
        AccountGuide.TWO_STEP to R.string.account_guide_two_step,
        AccountGuide.INACTIVITY to R.string.account_guide_inactivity,
        AccountGuide.QUOTA to R.string.account_guide_quota,
    )

/** La dirección de cada guía. Todas son de la fuente de la seguridad de la cuenta. */
fun AccountGuide.urlRes(): Int = when (this) {
    AccountGuide.TWO_STEP -> R.string.account_guide_two_step_url
    AccountGuide.INACTIVITY -> R.string.account_guide_inactivity_url
    AccountGuide.QUOTA -> R.string.account_guide_quota_url
}

@ThemePreviews
@Composable
private fun CloudAccountScreenPreview() {
    PreviewTheme {
        CloudAccountScreen(
            CloudAccountUiState(
                loading = false,
                destination = Fact.Known(BackupDestinationInfo.Slots(providerLabel = "Drive")),
                checked = setOf(CloudAccountCheck.TWO_STEP),
            ),
            onAction = {},
        )
    }
}
