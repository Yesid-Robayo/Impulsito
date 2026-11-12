package io.github.yesidrobayo.shopledger.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.format.DataSizeFormatter
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRow
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRowData
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlin.time.Instant

/** De dónde sale lo que se restaura: de la lista de copias o de un archivo elegido. */
enum class RestoreOrigin {
    LIST,
    FILE,
}

/** Por qué se detuvo una restauración, ya en lo que dice la pantalla. */
enum class RestoreNotice {
    DAMAGED,

    /** Un archivo elegido que no abre: puede ser una ranura a medias, y conviene probar otra. */
    TRY_ANOTHER_FILE,
    NEWER_VERSION,
    NO_INTERNET,
    NO_SPACE,
    TOO_LARGE,
    NOT_A_BACKUP,
    OTHER,
    CANCELLED,
}

/** En qué va la restauración en la pantalla del respaldo. Ninguno cambia los datos. */
sealed interface RestoreUi {
    data object Idle : RestoreUi

    /** La hoja de una copia, con sus datos y `Restaurar`. */
    data class CopySheet(val copy: BackupCopySummary) : RestoreUi

    /** `¿Restaurar la copia del {date}?`. */
    data class ConfirmCopy(val copy: BackupCopySummary) : RestoreUi

    /** `¿Restaurar este archivo?`: un archivo de afuera, del que no se sabe quién lo hizo. */
    data class ConfirmFile(val uri: String) : RestoreUi

    /** Revisando y preparando: se puede cancelar, no navegar. */
    data class Working(val origin: RestoreOrigin) : RestoreUi

    /** La restauración espera una respuesta. */
    data class Asking(val question: RestoreProgress, val origin: RestoreOrigin) : RestoreUi

    /** Se detuvo sin tocar los datos. [missingBytes] solo con [RestoreNotice.NO_SPACE]. */
    data class Stopped(
        val notice: RestoreNotice,
        val origin: RestoreOrigin,
        val missingBytes: Long = 0,
    ) : RestoreUi

    /** Lista: falta reiniciar la app, que hace el reemplazo al arrancar. */
    data object Ready : RestoreUi
}

/** El aviso que corresponde a un resultado que detuvo la restauración, o nulo si no la detuvo. */
internal fun noticeOf(progress: RestoreProgress, origin: RestoreOrigin): RestoreNotice? =
    when (progress) {
        is RestoreProgress.Refused -> noticeOf(progress.problem, origin)

        is RestoreProgress.NotEnoughSpace -> RestoreNotice.NO_SPACE

        RestoreProgress.Failed, RestoreProgress.Busy -> RestoreNotice.OTHER

        is RestoreProgress.AskRepair,
        RestoreProgress.AskEmptyCopy,
        RestoreProgress.AskWithoutSafetyCopy,
        is RestoreProgress.AskDiscardSafetyCopy,
        is RestoreProgress.ReadyToRestart,
        -> null
    }

private fun noticeOf(problem: RestoreProblem, origin: RestoreOrigin): RestoreNotice =
    when (problem) {
        RestoreProblem.NOT_A_BACKUP -> RestoreNotice.NOT_A_BACKUP

        // Con un archivo elegido no hay lista de la que ofrecer otra: se pide probar otro archivo.
        RestoreProblem.DAMAGED, RestoreProblem.MARKED_FATAL ->
            when (origin) {
                RestoreOrigin.FILE -> RestoreNotice.TRY_ANOTHER_FILE
                RestoreOrigin.LIST -> RestoreNotice.DAMAGED
            }

        RestoreProblem.NEWER_VERSION -> RestoreNotice.NEWER_VERSION

        RestoreProblem.TOO_LARGE -> RestoreNotice.TOO_LARGE

        RestoreProblem.ENCRYPTED,
        RestoreProblem.NOT_CONNECTED,
        RestoreProblem.NOT_AVAILABLE,
        RestoreProblem.OTHER,
        -> RestoreNotice.OTHER
    }

/**
 * La hoja, los diálogos y la pantalla de progreso de la restauración, según [restore].
 *
 * @param canRelease si la hoja de una copia fijada ofrece liberarla: solo en la pantalla del
 *   respaldo con WebDAV, nunca al traer los datos en un teléfono nuevo.
 */
@Composable
internal fun RestoreLayer(
    restore: RestoreUi,
    rowOf: @Composable (BackupCopySummary) -> BackupCopyRowData,
    dateOf: @Composable (Instant?) -> String,
    onAction: (BackupAction) -> Unit,
    canRelease: Boolean = false,
) {
    when (restore) {
        RestoreUi.Idle, is RestoreUi.Stopped -> Unit

        is RestoreUi.CopySheet ->
            CopySheet(restore.copy, rowOf(restore.copy), canRelease, onAction)

        is RestoreUi.ConfirmCopy ->
            ConfirmDialog(
                title = stringResource(
                    R.string.dialog_restore_title,
                    dateOf(restore.copy.createdAt),
                ),
                body = stringResource(R.string.dialog_restore_body),
                confirmLabel = stringResource(R.string.dialog_restore_action),
                onConfirm = { onAction(BackupAction.StartRestore) },
                onDismiss = { onAction(BackupAction.CloseRestore) },
                variant = ConfirmVariant.DESTRUCTIVE,
                dismissLabel = stringResource(R.string.action_cancel),
            )

        is RestoreUi.ConfirmFile ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_restore_file_title),
                body =
                    stringResource(
                        R.string.dialog_restore_file_body,
                        stringResource(R.string.app_name),
                    ),
                confirmLabel = stringResource(R.string.dialog_restore_file_action),
                onConfirm = { onAction(BackupAction.StartRestore) },
                onDismiss = { onAction(BackupAction.CloseRestore) },
                variant = ConfirmVariant.DESTRUCTIVE,
                dismissLabel = stringResource(R.string.action_cancel),
            )

        is RestoreUi.Working -> Progress(restore.origin, onAction)

        is RestoreUi.Asking -> {
            Progress(restore.origin, onAction)
            Question(restore.question, dateOf, onAction)
        }

        RestoreUi.Ready -> Progress(RestoreOrigin.FILE, onAction = null)
    }
}

/** La hoja de una copia: sus datos, lo que se reemplaza y `Restaurar`. */
@Composable
private fun CopySheet(
    copy: BackupCopySummary,
    row: BackupCopyRowData,
    canRelease: Boolean,
    onAction: (BackupAction) -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = { onAction(BackupAction.CloseRestore) },
        title = null,
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_restore),
                onClick = { onAction(BackupAction.ConfirmCopy(copy.key)) },
                variant = ActionVariant.PRIMARY,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            BackupCopyRow(row)
            Text(
                stringResource(R.string.dialog_restore_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Una copia fijada nunca se poda: solo esta acción la libera, nunca el tiempo.
            if (copy.pinned && canRelease) {
                Text(
                    stringResource(R.string.backup_pinned_help, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InlineTextButton(
                    stringResource(R.string.backup_action_release),
                    { onAction(BackupAction.ReleaseCopy(copy.key)) },
                )
            }
        }
    }
}

/**
 * La pantalla de progreso: tapa todo, sin navegar, con `Cancelar` mientras se revisa. Con la base
 * ya lista para reemplazar no hay salida: la app se reinicia.
 */
@Composable
private fun Progress(origin: RestoreOrigin, onAction: ((BackupAction) -> Unit)?) {
    // Mientras dura, atrás no navega: solo `Cancelar` sale.
    BackHandler { onAction?.invoke(BackupAction.CancelRestore) }
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .padding(AppTheme.spacing.s24),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val stage =
                    when {
                        onAction == null -> R.string.restore_after_restart
                        origin == RestoreOrigin.LIST -> R.string.restore_stage_downloading
                        else -> R.string.restore_stage_checking
                    }
                Text(
                    stringResource(stage),
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (onAction == null) {
                    Text(
                        stringResource(R.string.restore_no_exit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    InlineTextButton(
                        stringResource(R.string.action_cancel),
                        { onAction(BackupAction.CancelRestore) },
                    )
                }
            }
        }
    }
}

/** Lo que pregunta la restauración antes de seguir. */
@Composable
private fun Question(
    question: RestoreProgress,
    dateOf: @Composable (Instant?) -> String,
    onAction: (BackupAction) -> Unit,
) {
    val cancel = stringResource(R.string.action_cancel)
    val dismiss = { onAction(BackupAction.CancelRestore) }
    when (question) {
        is RestoreProgress.AskRepair -> {
            val rows = question.rows.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            ConfirmDialog(
                title = stringResource(R.string.dialog_restore_differences_title),
                body = pluralStringResource(R.plurals.dialog_restore_differences_body, rows, rows),
                confirmLabel = stringResource(R.string.dialog_restore_differences_action),
                onConfirm = { onAction(BackupAction.Answer(RestoreAnswer.Repair)) },
                onDismiss = dismiss,
                dismissLabel = cancel,
            )
        }

        RestoreProgress.AskEmptyCopy ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_restore_empty_title),
                body = stringResource(R.string.dialog_restore_empty_body),
                confirmLabel = stringResource(R.string.dialog_restore_empty_action),
                onConfirm = { onAction(BackupAction.Answer(RestoreAnswer.ReplaceWithEmptyCopy)) },
                onDismiss = dismiss,
                variant = ConfirmVariant.DESTRUCTIVE,
                dismissLabel = cancel,
            )

        RestoreProgress.AskWithoutSafetyCopy ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_no_safety_copy_title),
                body = stringResource(R.string.dialog_no_safety_copy_body),
                confirmLabel = stringResource(R.string.dialog_no_safety_copy_action),
                onConfirm = {
                    onAction(BackupAction.Answer(RestoreAnswer.ContinueWithoutSafetyCopy))
                },
                onDismiss = dismiss,
                variant = ConfirmVariant.DESTRUCTIVE,
                dismissLabel = cancel,
            )

        is RestoreProgress.AskDiscardSafetyCopy -> {
            val dates = question.copies.map { dateOf(it.createdAt) }
            DiscardQuestion(dates, onAction)
        }

        RestoreProgress.Busy,
        is RestoreProgress.NotEnoughSpace,
        is RestoreProgress.Refused,
        RestoreProgress.Failed,
        is RestoreProgress.ReadyToRestart,
        -> Unit
    }
}

/** Cuál de las dos copias de seguridad descartar: la app nunca descarta una sola. */
@Composable
private fun DiscardQuestion(dates: List<String>, onAction: (BackupAction) -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.dialog_discard_safety_title),
        body = stringResource(R.string.dialog_discard_safety_body),
        confirmLabel = stringResource(
            R.string.dialog_discard_safety_option,
            dates.getOrElse(0) {
                NO_VALUE_TEXT
            },
        ),
        onConfirm = { onAction(BackupAction.Answer(RestoreAnswer.DiscardSafetyCopy(0))) },
        onDismiss = { onAction(BackupAction.CancelRestore) },
        variant = ConfirmVariant.DESTRUCTIVE,
        dismissLabel = stringResource(R.string.action_cancel),
        secondaryLabel = stringResource(
            R.string.dialog_discard_safety_option,
            dates.getOrElse(1) {
                NO_VALUE_TEXT
            },
        ),
        onSecondary = { onAction(BackupAction.Answer(RestoreAnswer.DiscardSafetyCopy(1))) },
    )
}

/** El aviso de una restauración detenida: los datos siguen intactos, y cómo seguir. */
@Composable
internal fun RestoreStoppedNotice(stopped: RestoreUi.Stopped, onAction: (BackupAction) -> Unit) {
    val app = stringResource(R.string.app_name)
    val formats = LocalAppFormats.current
    val text =
        when (stopped.notice) {
            RestoreNotice.DAMAGED -> stringResource(R.string.restore_error_damaged)

            RestoreNotice.TRY_ANOTHER_FILE -> stringResource(R.string.restore_error_slot_try_next)

            RestoreNotice.NEWER_VERSION -> stringResource(R.string.restore_error_newer, app)

            RestoreNotice.NO_INTERNET -> stringResource(R.string.restore_error_no_internet)

            RestoreNotice.NO_SPACE ->
                stringResource(
                    R.string.restore_error_no_space,
                    DataSizeFormatter(formats.date.formatLocale, formats.uiLocale)
                        .wholeMegabytes(stopped.missingBytes),
                )

            RestoreNotice.TOO_LARGE -> stringResource(R.string.restore_error_too_large, app)

            RestoreNotice.NOT_A_BACKUP -> stringResource(R.string.restore_error_not_backup, app)

            RestoreNotice.OTHER -> stringResource(R.string.restore_error_other)

            RestoreNotice.CANCELLED -> stringResource(R.string.restore_error_cancelled)
        }
    // Con un archivo que no sirvió, la salida es elegir otro; con la lista, otra copia de la lista.
    val findFile = stopped.notice == RestoreNotice.TRY_ANOTHER_FILE ||
        stopped.notice == RestoreNotice.NOT_A_BACKUP
    NoticeCard(
        text = text,
        variant =
            if (stopped.notice ==
                RestoreNotice.CANCELLED
            ) {
                NoticeVariant.INFO
            } else {
                NoticeVariant.ERROR
            },
        actionLabel =
            if (findFile) {
                stringResource(R.string.backup_action_find_file)
            } else {
                stringResource(R.string.action_close)
            },
        onAction = {
            onAction(if (findFile) BackupAction.FindFile else BackupAction.CloseRestore)
        },
    )
}
