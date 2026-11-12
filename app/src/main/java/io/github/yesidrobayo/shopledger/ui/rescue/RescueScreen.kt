package io.github.yesidrobayo.shopledger.ui.rescue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.format.DataSizeFormatter
import io.github.yesidrobayo.shopledger.format.DateFormatter
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreLayer
import io.github.yesidrobayo.shopledger.ui.backup.RestoreNotice
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRow
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRowData
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** Una línea de `Información para soporte`: la etiqueta y su valor, sin datos del negocio. */
data class SupportLine(val label: String, val value: String)

/**
 * La pantalla de rescate: reemplaza a toda la navegación cuando la base no abre. No lee la base, no
 * muestra cifras ni nombres del negocio y no navega: todo lo que ofrece vive aquí, en hojas y
 * diálogos.
 *
 * El orden, de arriba abajo: el icono, el título y la causa; `No desinstalar`, siempre; la base
 * dañada que se puede compartir, si existe; `Restaurar`, `Buscar un archivo`; `Información para
 * soporte` e `Idioma`. Todo se desplaza, y en tableta queda centrado a 600 dp.
 *
 * Atrás sale de la app, salvo mientras restaura: al abrirla otra vez, la compuerta decide de nuevo.
 *
 * @param support las líneas de `Información para soporte`.
 * @param languageSheet el selector de idioma, que no lee la base.
 * @param crashLog el registro local de fallos, en modo seguro, o nulo. Se muestra entero antes de
 *   ofrecer compartirlo.
 */
@Composable
fun RescueScreen(
    uiState: RescueUiState,
    support: List<SupportLine>,
    onAction: (RescueAction) -> Unit,
    languageSheet: @Composable () -> Unit = {},
    crashLog: String? = null,
) {
    val restore: (BackupAction) -> Unit = { onAction(RescueAction.Restore(it)) }
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(AppTheme.spacing.s24),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
            ) {
                Heading(
                    uiState.cause,
                    hasBeforeUpdate = uiState.beforeUpdate != null,
                    restorePending = uiState.onlyRetry,
                )
                NoticeCard(
                    text = stringResource(
                        R.string.rescue_warning,
                        stringResource(R.string.app_name),
                    ),
                    variant = NoticeVariant.WARNING,
                )
                if (uiState.damagedAvailable && !uiState.onlyRetry) {
                    NoticeCard(
                        text = stringResource(R.string.backup_damaged_notice),
                        variant = NoticeVariant.WARNING,
                        actionLabel = stringResource(R.string.backup_damaged_share_action),
                        onAction = { onAction(RescueAction.ShareDamaged) },
                    )
                }
                (uiState.restore as? RestoreUi.Stopped)?.let { RescueStoppedNotice(it, restore) }
                Actions(uiState, onAction)
            }
        }
    }
    if (uiState.copiesOpen) CopiesSheet(uiState, onAction)
    if (uiState.supportOpen) SupportSheet(support, crashLog, onAction)
    if (uiState.languageOpen) languageSheet()
    if (uiState.sharing) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_share_damaged_title),
            body = stringResource(R.string.dialog_share_damaged_body),
            confirmLabel = stringResource(R.string.action_share),
            onConfirm = { onAction(RescueAction.ConfirmShareDamaged) },
            onDismiss = { onAction(RescueAction.CloseShareDamaged) },
            variant = ConfirmVariant.DESTRUCTIVE,
            dismissLabel = stringResource(R.string.action_cancel),
        )
    }
    val formats = rememberRescueFormats()
    RestoreLayer(
        restore = uiState.restore,
        rowOf = { rescueCopyRow(it, formats) },
        dateOf = { formats.dateText(it) },
        onAction = restore,
    )
}

/** El icono, el título y la causa, en palabras que no prometen nada que no pasó. */
@Composable
private fun Heading(cause: RescueReason, hasBeforeUpdate: Boolean, restorePending: Boolean) {
    val app = stringResource(R.string.app_name)
    Icon(
        painter = painterResource(R.drawable.ic_error),
        contentDescription = null,
        tint = AppTheme.colors.backupError,
        modifier = Modifier.size(AppTheme.sizes.emptyStateIcon),
    )
    val title =
        when (cause) {
            is RescueReason.NotEnoughSpace -> stringResource(R.string.rescue_no_space_title)

            RescueReason.SafeMode -> stringResource(R.string.rescue_safe_mode_title)

            RescueReason.DatabaseMissing,
            RescueReason.NewerVersion,
            RescueReason.OpenFailed,
            RescueReason.MigrationFailed,
            RescueReason.PreMigrationCopyFailed,
            RescueReason.RestoreJournalUnreadable,
            RescueReason.RestoreLeftNoDatabase,
            -> stringResource(R.string.rescue_title)
        }
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    // Cada frase de la guía es su propio párrafo: nunca se pegan dos textos.
    val paragraphs =
        when (cause) {
            RescueReason.DatabaseMissing,
            RescueReason.OpenFailed,
            RescueReason.RestoreLeftNoDatabase,
            -> listOf(stringResource(R.string.rescue_detail_missing))

            // Con la copia previa, la salida es volver a ella; sin ella, esperar una versión corregida.
            RescueReason.MigrationFailed, RescueReason.PreMigrationCopyFailed ->
                if (hasBeforeUpdate) {
                    listOf(stringResource(R.string.rescue_detail_migration))
                } else {
                    listOf(
                        stringResource(R.string.rescue_detail_migration),
                        stringResource(R.string.rescue_no_premigration, app),
                    )
                }

            RescueReason.NewerVersion ->
                listOf(stringResource(R.string.rescue_premigration_incompatible, app))

            RescueReason.RestoreJournalUnreadable ->
                listOf(stringResource(R.string.rescue_detail_journal))

            RescueReason.SafeMode ->
                if (restorePending) {
                    listOf(
                        stringResource(R.string.rescue_safe_mode_body),
                        stringResource(R.string.rescue_detail_journal),
                    )
                } else {
                    listOf(stringResource(R.string.rescue_safe_mode_body))
                }

            is RescueReason.NotEnoughSpace -> {
                val formats = rememberRescueFormats()
                listOf(
                    stringResource(
                        R.string.rescue_no_space_body,
                        formats.size.wholeMegabytes(cause.missingBytes),
                    ),
                )
            }
        }
    paragraphs.forEach { paragraph ->
        Text(
            paragraph,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `Restaurar`, `Buscar un archivo` o `Reintentar`, y abajo las acciones de texto. */
@Composable
private fun Actions(uiState: RescueUiState, onAction: (RescueAction) -> Unit) {
    val findFile = { onAction(RescueAction.Restore(BackupAction.FindFile)) }
    val primary = uiState.primary
    val (primaryText, primaryClick) =
        when (primary) {
            RescuePrimary.RESTORE ->
                stringResource(R.string.backup_action_restore) to
                    { onAction(RescueAction.ShowCopies) }

            RescuePrimary.FIND_FILE -> stringResource(R.string.backup_action_find_file) to findFile

            RescuePrimary.RETRY ->
                stringResource(R.string.action_retry) to { onAction(RescueAction.Retry) }

            RescuePrimary.BEFORE_UPDATE ->
                stringResource(R.string.backup_undo_migration_label) to
                    { onAction(RescueAction.BeforeUpdate) }

            RescuePrimary.RETRY_OPEN ->
                stringResource(R.string.rescue_safe_mode_retry) to
                    { onAction(RescueAction.RetryOpen) }
        }
    if (uiState.onlyRetry) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            PrimaryActionButton(primaryText, primaryClick, ActionVariant.PRIMARY)
            PrimaryActionButton(
                stringResource(R.string.settings_support),
                { onAction(RescueAction.ShowSupport) },
                ActionVariant.TEXT,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        PrimaryActionButton(primaryText, primaryClick, ActionVariant.PRIMARY)
        val retrying = primary == RescuePrimary.RETRY || primary == RescuePrimary.RETRY_OPEN
        if (retrying && uiState.canRestoreFromDestination) {
            PrimaryActionButton(
                stringResource(R.string.backup_action_restore),
                { onAction(RescueAction.ShowCopies) },
                ActionVariant.OUTLINED,
            )
        }
        if (primary == RescuePrimary.BEFORE_UPDATE) {
            PrimaryActionButton(
                stringResource(R.string.backup_action_restore),
                { onAction(RescueAction.ShowCopies) },
                ActionVariant.OUTLINED,
            )
        }
        if (primary != RescuePrimary.FIND_FILE) {
            PrimaryActionButton(
                stringResource(R.string.backup_action_find_file),
                findFile,
                ActionVariant.OUTLINED,
            )
        }
        if (uiState.beforeUpdate != null && primary != RescuePrimary.BEFORE_UPDATE) {
            PrimaryActionButton(
                stringResource(R.string.backup_undo_migration_label),
                { onAction(RescueAction.BeforeUpdate) },
                ActionVariant.OUTLINED,
            )
        }
        PrimaryActionButton(
            stringResource(R.string.settings_support),
            { onAction(RescueAction.ShowSupport) },
            ActionVariant.TEXT,
        )
        PrimaryActionButton(
            stringResource(R.string.language_title),
            { onAction(RescueAction.ShowLanguage) },
            ActionVariant.TEXT,
        )
    }
}

/** La hoja con las copias del destino guardado, con sus estados. Nunca una lista vacía falsa. */
@Composable
private fun CopiesSheet(uiState: RescueUiState, onAction: (RescueAction) -> Unit) {
    val formats = rememberRescueFormats()
    AppBottomSheet(
        onDismissRequest = { onAction(RescueAction.CloseCopies) },
        title = stringResource(R.string.backup_copies_title),
    ) {
        val findFile = stringResource(R.string.backup_action_find_file)
        val find = {
            onAction(RescueAction.CloseCopies)
            onAction(RescueAction.Restore(BackupAction.FindFile))
        }
        when (val copies = uiState.copies) {
            // El esqueleto solo aparece si la lista tarda, como en el resto de la app.
            null ->
                DelayedSkeleton {
                    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                        repeat(SKELETON_ROWS) {
                            SkeletonBlock(
                                Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow),
                            )
                        }
                    }
                }

            BackupCopyList.NoInternet ->
                EmptyStateInline(
                    title = stringResource(R.string.rescue_no_internet),
                    action = { InlineTextButton(findFile, find) },
                )

            BackupCopyList.Failed ->
                EmptyStateInline(
                    title = stringResource(R.string.backup_list_error),
                    action = {
                        InlineTextButton(stringResource(R.string.action_retry), {
                            onAction(RescueAction.RetryCopies)
                        })
                    },
                )

            // Sin destino guardado, o sin copias en él: lo que queda es buscar un archivo.
            BackupCopyList.NotConnected ->
                EmptyStateInline(
                    title = stringResource(R.string.empty_backups_title),
                    action = { InlineTextButton(findFile, find) },
                )

            is BackupCopyList.Listed ->
                if (copies.copies.isEmpty()) {
                    EmptyStateInline(
                        title = stringResource(R.string.empty_backups_title),
                        action = { InlineTextButton(findFile, find) },
                    )
                } else {
                    copies.copies.forEach { copy ->
                        BackupCopyRow(
                            rescueCopyRow(copy, formats),
                            onClick =
                                if (copy.restorable) {
                                    {
                                        onAction(
                                            RescueAction.Restore(BackupAction.OpenCopy(copy.key)),
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                }
        }
    }
}

/** `Información para soporte`: lo técnico, con `Copiar`, y nunca un dato del negocio. */
@Composable
private fun SupportSheet(
    lines: List<SupportLine>,
    crashLog: String?,
    onAction: (RescueAction) -> Unit,
) {
    SupportInfoSheet(
        lines = lines,
        onCopy = { onAction(RescueAction.CopySupport) },
        onDismiss = { onAction(RescueAction.CloseSupport) },
    ) {
        if (!crashLog.isNullOrEmpty()) CrashLog(crashLog, onAction)
    }
}

/**
 * El registro local de fallos: qué contiene, el texto entero y `Compartir`. Solo clase de error,
 * versiones y pila ofuscada, nunca un mensaje ni un dato del negocio. Sale solo si la persona lo
 * comparte.
 */
@Composable
private fun CrashLog(text: String, onAction: (RescueAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        Text(
            stringResource(R.string.rescue_crash_log_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // La hoja ya se desplaza: el registro va entero, sin un segundo desplazamiento dentro.
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        InlineTextButton(
            stringResource(R.string.action_share),
            { onAction(RescueAction.ShareCrashLog) },
        )
    }
}

/** El aviso de una restauración que se detuvo: los datos siguen como estaban. */
@Composable
private fun RescueStoppedNotice(stopped: RestoreUi.Stopped, onAction: (BackupAction) -> Unit) {
    val app = stringResource(R.string.app_name)
    val formats = rememberRescueFormats()
    val text =
        when (stopped.notice) {
            RestoreNotice.DAMAGED -> stringResource(R.string.restore_error_damaged)

            RestoreNotice.TRY_ANOTHER_FILE -> stringResource(R.string.restore_error_slot_try_next)

            RestoreNotice.NEWER_VERSION -> stringResource(R.string.restore_error_newer, app)

            RestoreNotice.NO_INTERNET -> stringResource(R.string.restore_error_no_internet)

            RestoreNotice.NO_SPACE ->
                stringResource(
                    R.string.restore_error_no_space,
                    formats.size.wholeMegabytes(stopped.missingBytes),
                )

            RestoreNotice.TOO_LARGE -> stringResource(R.string.restore_error_too_large, app)

            RestoreNotice.NOT_A_BACKUP -> stringResource(R.string.restore_error_not_backup, app)

            RestoreNotice.OTHER -> stringResource(R.string.restore_error_other)

            RestoreNotice.CANCELLED -> stringResource(R.string.restore_error_cancelled)

            RestoreNotice.NEEDS_UPDATE -> stringResource(
                R.string.rescue_premigration_incompatible,
                app,
            )
        }
    NoticeCard(
        text = text,
        variant =
            when (stopped.notice) {
                RestoreNotice.CANCELLED -> NoticeVariant.INFO

                RestoreNotice.DAMAGED,
                RestoreNotice.TRY_ANOTHER_FILE,
                RestoreNotice.NEWER_VERSION,
                RestoreNotice.NO_INTERNET,
                RestoreNotice.NO_SPACE,
                RestoreNotice.TOO_LARGE,
                RestoreNotice.NOT_A_BACKUP,
                RestoreNotice.OTHER,
                RestoreNotice.NEEDS_UPDATE,
                -> NoticeVariant.ERROR
            },
        actionLabel = stringResource(R.string.action_close),
        onAction = { onAction(BackupAction.CloseRestore) },
    )
}

/**
 * Los formatos del rescate. El formato regional del negocio vive en la base, que aquí no se lee:
 * fechas y tamaños salen con el idioma de la interfaz, y las horas en la zona del teléfono. Ninguna
 * cifra del negocio se muestra con ellos.
 */
internal class RescueFormats(uiLocale: Locale) {
    private val formatLocale: FormatLocale =
        requireNotNull(FormatLocale.of(uiLocale.toLanguageTag())) {
            "El idioma de la interfaz siempre nombra un idioma"
        }
    val size = DataSizeFormatter(formatLocale, uiLocale)
    private val date = DateFormatter(formatLocale, uiLocale)
    private val zone = BusinessTimeZone.of(TimeZone.currentSystemDefault().id)

    /** El día y la hora de [instant], o un guion si no se sabe. */
    fun dateText(instant: Instant?): String {
        val known = zone ?: return NO_VALUE_TEXT
        return instant?.let { date.dayMonthTime(it, known) } ?: NO_VALUE_TEXT
    }
}

@Composable
internal fun rememberRescueFormats(): RescueFormats {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { RescueFormats(locale) }
}

/** Los textos de una copia en el rescate, sin los formatos del negocio. */
@Composable
internal fun rescueCopyRow(copy: BackupCopySummary, formats: RescueFormats): BackupCopyRowData {
    val slot = copy.slot
    val origin =
        when {
            slot != null -> stringResource(R.string.backup_slot_label, slot.name)
            copy.origin == CopyOrigin.THIS_PHONE -> stringResource(R.string.backup_device_this)
            else -> stringResource(R.string.backup_device_other)
        }
    val tags = buildList {
        if (copy.pinned) add(stringResource(R.string.backup_pinned_tag))
        if (copy.withDifferences) add(stringResource(R.string.backup_differences_tag))
        if (!copy.restorable) add(stringResource(R.string.backup_copy_not_restorable))
    }
    val records = copy.records?.let {
        val count = it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.backup_records_count, count, count)
    }
    return BackupCopyRowData(
        whenText = copy.createdAt?.let { formats.dateText(it) },
        sizeText = formats.size.megabytes(copy.sizeBytes),
        originText = origin,
        mostRecent = copy.newest,
        recordsText = records,
        tags = tags,
    )
}

private const val SKELETON_ROWS = 3
