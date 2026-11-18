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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpArticle
import io.github.yesidrobayo.shopledger.ui.help.HelpBlock
import io.github.yesidrobayo.shopledger.ui.help.HelpSpan
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import kotlin.time.Instant

/**
 * `Actualizaciones y verificar APK`, de arriba abajo:
 *
 * 1. La versión instalada y la huella de su firma, para compararla a mano con la publicada. La
 *    huella no se copia: solo la información para soporte usa el portapapeles.
 * 2. `Buscar actualización`, solo con la búsqueda permitida, con la fecha de la última búsqueda.
 *    Apagada, dice que la app no se conecta para esto.
 * 3. `Permitir buscar actualizaciones`, con él encendido `Buscar actualizaciones
 *    automáticamente`, una vez al día, y debajo el resultado de la búsqueda de esta visita.
 *    Con una versión nueva, `Actualizar` es la acción principal: baja el APK con su progreso y
 *    `Cancelar`, lo compara, pide una vez el permiso de Android si falta y lo pasa al instalador.
 *    Cada falla dice cuál fue, con `Reintentar`.
 * 4. `Cómo actualizar`, con sus cuatro pasos en una hoja, y `Novedades`, con lo que trajo cada
 *    versión, en otra.
 * 5. `Verificar un APK`: `Elegir archivo` abre el selector del sistema, y debajo va el resultado.
 *    Con otra firma dice `No instalar este archivo` y que no se desinstale la app.
 *
 * Nada bloquea la app ni los datos, y nada se instala sin la confirmación de Android. Columna
 * centrada de 600 dp en una ventana ancha.
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
                    SearchSection(uiState, onAction, onOpenHow = { howOpen = true })
                    SettingsRow(
                        title = stringResource(R.string.update_how_title),
                        action = SettingsRowAction.Navigation { howOpen = true },
                    )
                    SettingsRow(
                        title = stringResource(R.string.update_whats_new_title),
                        action =
                            SettingsRowAction.Navigation { onAction(UpdatesAction.OpenWhatsNew) },
                    )
                    VerifySection(uiState.verify, onAction)
                }
            }
        }
    }
    if (howOpen) HowToUpdateSheet(onDismiss = { howOpen = false })
    if (uiState.whatsNew != WhatsNewUi.Closed) {
        WhatsNewSheet(uiState.whatsNew, onDismiss = { onAction(UpdatesAction.CloseWhatsNew) })
    }
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

/**
 * `Buscar actualización`, su interruptor y el resultado. Mientras no se saben los ajustes, no se
 * ofrece nada: ni un botón que quizá no se debe mostrar, ni un interruptor con un valor inventado.
 */
@Composable
private fun SearchSection(
    uiState: UpdatesUiState,
    onAction: (UpdatesAction) -> Unit,
    onOpenHow: () -> Unit,
) {
    val enabled = uiState.searchEnabled ?: return
    val app = stringResource(R.string.app_name)
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        if (enabled) {
            val searching = uiState.search == SearchUi.Searching
            PrimaryActionButton(
                text = stringResource(R.string.update_search_action),
                onClick = { if (!searching) onAction(UpdatesAction.Search) },
                variant = ActionVariant.TONAL,
                state = if (searching) ActionState.WORKING else ActionState.IDLE,
            )
            Text(
                lastSearchText(uiState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.update_search_off, app),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    SettingsRow(
        title = stringResource(R.string.update_allow_search),
        summary = stringResource(R.string.update_allow_search_help),
        action =
            SettingsRowAction.Toggle(enabled) { onAction(UpdatesAction.SetSearchEnabled(it)) },
    )
    // La automática solo tiene sentido con la búsqueda permitida; mientras se lee, no se inventa.
    val automatic = uiState.automatic
    if (enabled && automatic != null) {
        SettingsRow(
            title = stringResource(R.string.update_automatic),
            summary = stringResource(R.string.update_automatic_help),
            action =
                SettingsRowAction.Toggle(automatic) { onAction(UpdatesAction.SetAutomatic(it)) },
        )
    }
    if (enabled) {
        // TalkBack lo dice al aparecer, sin mover el foco.
        Column(
            Modifier
                .padding(horizontal = spacing.s16, vertical = spacing.s8)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            SearchResult(uiState, onAction, onOpenHow)
        }
        // Fuera de la región que TalkBack anuncia: el avance de la descarga no interrumpe.
        if ((uiState.search as? SearchUi.Done)?.outcome is UpdateCheckOutcome.NewVersion) {
            Column(
                Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                InstallSection(uiState.install, onAction)
            }
        }
    }
}

/** `Última búsqueda: 28 oct`, o que todavía no se buscó. */
@Composable
private fun lastSearchText(uiState: UpdatesUiState): String {
    val at = uiState.lastSearchAt ?: return stringResource(R.string.update_empty)
    return stringResource(R.string.update_last_search, shortDate(at, uiState))
}

/** La fecha de [at] en la zona del negocio, sin el año si es de este año. */
@Composable
private fun shortDate(at: Instant, uiState: UpdatesUiState): String {
    val zone = uiState.zone
    val today = BusinessDate.of(uiState.now ?: at, zone)
    return LocalAppFormats.current.date.shortDate(BusinessDate.of(at, zone), today)
}

/** El resultado de la búsqueda de esta visita. Ninguno bloquea nada. */
@Composable
private fun SearchResult(
    uiState: UpdatesUiState,
    onAction: (UpdatesAction) -> Unit,
    onOpenHow: () -> Unit,
) {
    val retry = stringResource(R.string.action_retry)
    when (val search = uiState.search) {
        SearchUi.Idle -> Unit

        SearchUi.Searching ->
            Text(
                stringResource(R.string.update_searching),
                style = MaterialTheme.typography.bodyMedium,
            )

        is SearchUi.Done -> when (val outcome = search.outcome) {
            is UpdateCheckOutcome.Latest ->
                NoticeCard(
                    text = stringResource(R.string.update_latest),
                    variant = NoticeVariant.INFO,
                    icon = R.drawable.ic_check_circle,
                )

            is UpdateCheckOutcome.NewVersion -> {
                NoticeCard(
                    text =
                        stringResource(R.string.update_new, outcome.versionName) + "\n" +
                            stringResource(
                                R.string.update_new_date,
                                shortDate(outcome.publishedAt, uiState),
                            ),
                    variant = NoticeVariant.INFO,
                    actionLabel = stringResource(R.string.update_view_how_action),
                    onAction = onOpenHow,
                )
                if (outcome.important) {
                    NoticeCard(
                        text =
                            stringResource(R.string.update_important_title) + "\n" +
                                stringResource(R.string.update_important_body),
                        variant = NoticeVariant.WARNING,
                    )
                }
            }

            UpdateCheckOutcome.BadSignature ->
                NoticeCard(stringResource(R.string.update_bad_signature), NoticeVariant.WARNING)

            UpdateCheckOutcome.Offline ->
                NoticeCard(
                    text = stringResource(R.string.state_no_connection),
                    variant = NoticeVariant.ERROR,
                    actionLabel = retry,
                    onAction = { onAction(UpdatesAction.Search) },
                )

            UpdateCheckOutcome.Unreadable ->
                NoticeCard(
                    text = stringResource(R.string.update_error_read),
                    variant = NoticeVariant.ERROR,
                    actionLabel = retry,
                    onAction = { onAction(UpdatesAction.Search) },
                )
        }
    }
}

/**
 * `Actualizar` y lo que pasa después. Es la única acción principal de la pantalla, y solo aparece
 * con una versión nueva. Mientras trabaja no atiende otro toque.
 */
@Composable
private fun InstallSection(install: InstallUi, onAction: (UpdatesAction) -> Unit) {
    val app = stringResource(R.string.app_name)
    val retry = stringResource(R.string.action_retry)
    when (install) {
        InstallUi.Idle -> UpdateButton(working = false, onAction)

        is InstallUi.Downloading -> DownloadProgress(install, onAction)

        InstallUi.Verifying,
        InstallUi.Opening,
        -> {
            UpdateButton(working = true, onAction)
            Text(
                stringResource(R.string.update_verifying),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        InstallUi.NeedsPermission ->
            NoticeCard(
                text = stringResource(R.string.update_permission_body, app),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.update_permission_action),
                onAction = { onAction(UpdatesAction.AllowInstalls) },
            )

        InstallUi.HandedToInstaller ->
            NoticeCard(
                text = stringResource(R.string.update_handed),
                variant = NoticeVariant.INFO,
                icon = R.drawable.ic_check_circle,
                actionLabel = stringResource(R.string.update_install_again_action),
                onAction = { onAction(UpdatesAction.OpenInstallerAgain) },
            )

        is InstallUi.Failed -> {
            // Otra firma es un archivo ajeno: el aviso dice que no se instale, sin ofrecer repetir.
            val retries = install.reason != InstallFailure.WRONG_SIGNATURE
            NoticeCard(
                text = failureText(install.reason),
                variant = NoticeVariant.ERROR,
                actionLabel = retry.takeIf { retries },
                onAction = { onAction(UpdatesAction.StartUpdate) },
            )
        }
    }
}

@Composable
private fun UpdateButton(working: Boolean, onAction: (UpdatesAction) -> Unit) {
    PrimaryActionButton(
        text = stringResource(R.string.update_install_action),
        onClick = { if (!working) onAction(UpdatesAction.StartUpdate) },
        variant = ActionVariant.PRIMARY,
        state = if (working) ActionState.WORKING else ActionState.IDLE,
    )
}

/**
 * La barra con lo bajado y `Cancelar`. Sin el tamaño declarado, la barra no promete un avance: solo
 * dice que se está bajando.
 */
@Composable
private fun DownloadProgress(
    downloading: InstallUi.Downloading,
    onAction: (UpdatesAction) -> Unit,
) {
    val total = downloading.totalBytes?.takeIf { it > 0 }
    val text =
        if (total == null) {
            stringResource(R.string.update_downloading_unknown)
        } else {
            val done = downloading.downloadedBytes.coerceIn(0, total)
            val share = BasisPoints(done * FULL_BASIS_POINTS / total)
            val percent = LocalAppFormats.current.percent.format(share)
            stringResource(R.string.update_downloading, percent)
        }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        // La barra lee el avance a TalkBack: el texto no se repite como otro foco.
        val barModifier = Modifier.fillMaxWidth().semantics { stateDescription = text }
        if (total == null) {
            LinearProgressIndicator(barModifier)
        } else {
            LinearProgressIndicator(
                progress = { downloading.downloadedBytes.coerceIn(0, total).toFloat() / total },
                modifier = barModifier,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics {},
        )
        InlineTextButton(
            stringResource(R.string.action_cancel),
            { onAction(UpdatesAction.CancelUpdate) },
        )
    }
}

/** Qué falló, en palabras. Con otra firma, lo mismo que dice `Verificar un APK`. */
@Composable
private fun failureText(reason: InstallFailure): String = when (reason) {
    InstallFailure.NO_CONNECTION -> stringResource(R.string.state_no_connection)

    InstallFailure.DOWNLOAD_FAILED -> stringResource(R.string.update_error_download)

    InstallFailure.TOO_LARGE -> stringResource(R.string.update_error_too_big)

    InstallFailure.HASH_MISMATCH -> stringResource(R.string.update_error_hash)

    InstallFailure.WRONG_SIGNATURE ->
        stringResource(R.string.verify_mismatch_title) + "\n" +
            stringResource(R.string.verify_mismatch_body, stringResource(R.string.app_name))

    InstallFailure.NOT_NEWER -> stringResource(R.string.update_error_not_newer)

    InstallFailure.NO_INSTALLER -> stringResource(R.string.update_error_installer)
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

/**
 * `Novedades`: lo que trajo cada versión, de la más nueva a la más antigua, con el mismo dibujo
 * que la ayuda. Se lee de los recursos, sin red.
 */
@Composable
private fun WhatsNewSheet(whatsNew: WhatsNewUi, onDismiss: () -> Unit) {
    AppBottomSheet(onDismissRequest = onDismiss, title = null) {
        when (whatsNew) {
            // No llega aquí: cerrada, la hoja no existe.
            WhatsNewUi.Closed -> Unit

            WhatsNewUi.Loading -> InstalledSkeleton()

            is WhatsNewUi.Ready ->
                HelpArticle(
                    title = stringResource(R.string.update_whats_new_title),
                    blocks = whatsNew.blocks,
                    // Las novedades no enlazan artículos de la ayuda.
                    onOpenArticle = {},
                )

            WhatsNewUi.Failed ->
                NoticeCard(stringResource(R.string.update_whats_new_error), NoticeVariant.ERROR)
        }
    }
}

private const val SKELETON_ROWS = 3

/** Un porcentaje entero en puntos básicos. */
private const val FULL_BASIS_POINTS = 10_000L

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
                    searchEnabled = true,
                    automatic = true,
                    lastSearchAt = Instant.parse("2026-10-28T15:00:00Z"),
                    search =
                        SearchUi.Done(
                            UpdateCheckOutcome.NewVersion(
                                versionName = "1.0.1",
                                publishedAt = Instant.parse("2026-11-10T12:00:00Z"),
                                apkSha256 = "0123456789abcdef",
                                important = true,
                                versionCode = 5,
                            ),
                        ),
                    now = Instant.parse("2026-11-15T12:00:00Z"),
                    verify = VerifyUi.Done(ApkCheckResult.Mismatch("0123456789abcdef")),
                ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun UpdatesScreenDownloadingPreview() {
    PreviewTheme {
        UpdatesScreen(
            uiState =
                UpdatesUiState(
                    installedLoading = false,
                    installed = InstalledApp("1.0.1", 4, "3F:9A:21:7C:44:D0:8E:1B"),
                    searchEnabled = true,
                    search =
                        SearchUi.Done(
                            UpdateCheckOutcome.NewVersion(
                                versionName = "1.0.2",
                                publishedAt = Instant.parse("2026-11-16T12:00:00Z"),
                                apkSha256 = "0123456789abcdef",
                                important = false,
                                versionCode = 5,
                            ),
                        ),
                    now = Instant.parse("2026-11-16T12:00:00Z"),
                    install = InstallUi.Downloading(6_400_000, 16_000_000),
                ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun UpdatesScreenFailedPreview() {
    PreviewTheme {
        UpdatesScreen(
            uiState =
                UpdatesUiState(
                    installedLoading = false,
                    installed = InstalledApp("1.0.1", 4, "3F:9A:21:7C:44:D0:8E:1B"),
                    searchEnabled = true,
                    search =
                        SearchUi.Done(
                            UpdateCheckOutcome.NewVersion(
                                versionName = "1.0.2",
                                publishedAt = Instant.parse("2026-11-16T12:00:00Z"),
                                apkSha256 = "0123456789abcdef",
                                important = false,
                                versionCode = 5,
                            ),
                        ),
                    now = Instant.parse("2026-11-16T12:00:00Z"),
                    install = InstallUi.Failed(InstallFailure.HASH_MISMATCH),
                ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun WhatsNewSheetPreview() {
    PreviewTheme {
        HelpArticle(
            title = stringResource(R.string.update_whats_new_title),
            blocks =
                listOf(
                    HelpBlock.Heading(2, listOf(HelpSpan.Plain("1.0.2"))),
                    HelpBlock.ListBlock(
                        numbered = false,
                        items = listOf(listOf(HelpSpan.Plain(PreviewSamples.LONG_NAME))),
                    ),
                ),
            onOpenArticle = {},
        )
    }
}
