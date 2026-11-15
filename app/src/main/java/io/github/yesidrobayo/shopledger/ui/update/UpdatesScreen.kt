package io.github.yesidrobayo.shopledger.ui.update

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp

/**
 * `Actualizaciones y verificar APK`, de arriba abajo:
 *
 * 1. La versión instalada y la huella de su firma, para compararla a mano con la publicada. La
 *    huella no se copia: solo la información para soporte usa el portapapeles.
 * 2. `Cómo actualizar`, con sus cuatro pasos en una hoja.
 * 3. `Verificar un APK`: `Elegir archivo` abre el selector del sistema, y debajo va el resultado.
 *    Con otra firma dice `No instalar este archivo` y que no se desinstale la app.
 *
 * La app nunca instala nada. Columna centrada de 600 dp en una ventana ancha.
 */
@Composable
fun UpdatesScreen(
    uiState: UpdatesUiState,
    onAction: (UpdatesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    var howOpen by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.update_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(UpdatesAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = { ScreenHelpLink(HelpTopic.UPDATES) },
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
                    if (uiState.installedLoading) {
                        InstalledSkeleton()
                    } else {
                        InstalledSection(uiState.installed)
                    }
                    SettingsRow(
                        title = stringResource(R.string.update_how_title),
                        action = SettingsRowAction.Navigation { howOpen = true },
                    )
                    VerifySection(uiState.verify, onAction)
                }
            }
        }
    }
    if (howOpen) HowToUpdateSheet(onDismiss = { howOpen = false })
}

/** Mientras se lee la app instalada: unas líneas grises tras 200 ms. */
@Composable
private fun InstalledSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.iconInline))
            }
        }
    }
}

/** La versión instalada y la huella de su firma. Lo que no se pudo leer muestra `—`. */
@Composable
private fun InstalledSection(installed: InstalledApp?) {
    val app = stringResource(R.string.app_name)
    val noValue = stringResource(R.string.a11y_no_value)
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        if (installed == null) {
            NoValueText(noValue, MaterialTheme.typography.titleMedium)
        } else {
            Text(
                stringResource(
                    R.string.update_version,
                    installed.versionName ?: NO_VALUE_TEXT,
                    installed.versionCode.toString(),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stringResource(R.string.update_fingerprint_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = AppTheme.spacing.s8),
        )
        val fingerprint = installed?.fingerprint
        if (fingerprint == null) {
            NoValueText(noValue, MaterialTheme.typography.bodySmall)
        } else {
            // Con letra grande la huella se parte entre grupos, nunca dentro de uno: tras cada `:`
            // va un espacio de ancho cero, que solo marca dónde se puede cortar.
            Text(
                fingerprint.replace(":", ":$BREAK_OPPORTUNITY"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(R.string.update_fingerprint_help, app),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `—` en pantalla, y para TalkBack que no hay dato. */
@Composable
private fun NoValueText(description: String, style: TextStyle) {
    Text(
        NO_VALUE_TEXT,
        style = style,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/** `Verificar un APK`: la explicación, `Elegir archivo` y el resultado del último archivo. */
@Composable
private fun VerifySection(verify: VerifyUi, onAction: (UpdatesAction) -> Unit) {
    val app = stringResource(R.string.app_name)
    val spacing = AppTheme.spacing
    SectionHeader(stringResource(R.string.verify_title))
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Text(
            stringResource(R.string.verify_body, app),
            style = MaterialTheme.typography.bodyMedium,
        )
        val checking = verify == VerifyUi.Checking
        PrimaryActionButton(
            text = stringResource(R.string.verify_choose_action),
            onClick = { if (!checking) onAction(UpdatesAction.ChooseFile) },
            variant = ActionVariant.OUTLINED,
            state = if (checking) ActionState.WORKING else ActionState.IDLE,
        )
        // TalkBack lo dice al aparecer, sin mover el foco.
        Column(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            when (verify) {
                VerifyUi.Idle -> Unit

                VerifyUi.Checking ->
                    Text(
                        stringResource(R.string.verify_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                is VerifyUi.Done -> VerifyResult(verify)
            }
        }
    }
}

/** El resultado de un archivo: el aviso con su tipo y, si se leyó, su SHA-256. */
@Composable
private fun VerifyResult(done: VerifyUi.Done) {
    val app = stringResource(R.string.app_name)
    when (val result = done.result) {
        is ApkCheckResult.Match -> {
            val lines =
                buildList {
                    if (result.older) {
                        add(stringResource(R.string.verify_older_title))
                        add(stringResource(R.string.verify_older_body))
                    } else {
                        add(stringResource(R.string.verify_ok))
                    }
                    result.versionName?.let { add(stringResource(R.string.verify_version, it)) }
                    if (done.manifestMatch) add(stringResource(R.string.verify_manifest_match))
                }
            NoticeCard(
                text = lines.joinToString("\n"),
                variant = if (result.older) NoticeVariant.WARNING else NoticeVariant.INFO,
                icon = if (result.older) null else R.drawable.ic_check_circle,
            )
            FileHash(result.sha256)
        }

        is ApkCheckResult.Mismatch -> {
            NoticeCard(
                text =
                    stringResource(R.string.verify_mismatch_title) + "\n" +
                        stringResource(R.string.verify_mismatch_body, app),
                variant = NoticeVariant.ERROR,
            )
            FileHash(result.sha256)
        }

        ApkCheckResult.NotApk ->
            NoticeCard(stringResource(R.string.verify_not_apk), NoticeVariant.ERROR)

        ApkCheckResult.ReadFailed ->
            NoticeCard(stringResource(R.string.verify_error_read), NoticeVariant.ERROR)
    }
}

/** La huella SHA-256 del archivo, para compararla con la publicada. */
@Composable
private fun FileHash(sha256: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            stringResource(R.string.verify_sha256_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(sha256, style = MaterialTheme.typography.bodySmall)
    }
}

/** `Cómo actualizar`: la copia primero, y nunca desinstalar. */
@Composable
private fun HowToUpdateSheet(onDismiss: () -> Unit) {
    val app = stringResource(R.string.app_name)
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.update_how_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            Text(
                stringResource(R.string.update_how_backup_note),
                style = MaterialTheme.typography.bodyLarge,
            )
            val steps =
                listOf(
                    stringResource(R.string.update_how_step_backup),
                    stringResource(R.string.update_how_step_download, app),
                    stringResource(R.string.update_how_step_verify),
                    stringResource(R.string.update_how_step_install, app),
                )
            for (step in steps) Text(step, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val SKELETON_ROWS = 3

/** Un espacio de ancho cero: deja cortar la línea ahí, sin verse. */
private const val BREAK_OPPORTUNITY = "\u200B"

@ThemePreviews
@Composable
private fun UpdatesScreenPreview() {
    PreviewTheme {
        UpdatesScreen(
            uiState =
                UpdatesUiState(
                    installedLoading = false,
                    installed =
                        InstalledApp(
                            versionName = "1.0.0",
                            versionCode = 1,
                            fingerprint = "3F:9A:21:7C:44:D0:8E:1B",
                        ),
                    verify = VerifyUi.Done(ApkCheckResult.Mismatch("0123456789abcdef")),
                ),
            onAction = {},
        )
    }
}
