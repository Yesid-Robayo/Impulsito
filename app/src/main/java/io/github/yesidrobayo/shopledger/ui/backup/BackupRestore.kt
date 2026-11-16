package io.github.yesidrobayo.shopledger.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeRequest
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.backup.UndoRestoreCopy
import io.github.yesidrobayo.shopledger.format.DataSizeFormatter
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.lock.SensitiveScreen
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
import io.github.yesidrobayo.shopledger.ui.components.RECOVERY_CODE_GROUPS
import io.github.yesidrobayo.shopledger.ui.components.RecoveryCodeField
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlin.time.Instant

/** De dónde sale lo que se restaura: de la lista de copias o de un archivo elegido. */
enum class RestoreOrigin {
    LIST,
    FILE,

    /** La copia previa a la última actualización, en este teléfono. */
    BEFORE_UPDATE,

    /** La copia local de seguridad de antes de restaurar, en este teléfono. */
    UNDO_RESTORE,
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

    /** La copia previa a la actualización no abre con esta versión: hay que instalar la corregida. */
    NEEDS_UPDATE,
}

/** En qué va la restauración en la pantalla del respaldo. Ninguno cambia los datos. */
sealed interface RestoreUi {
    data object Idle : RestoreUi

    /** La hoja de una copia, con sus datos y `Restaurar`. */
    data class CopySheet(val copy: BackupCopySummary) : RestoreUi

    /** `¿Restaurar esta copia ({date})?`. */
    data class ConfirmCopy(val copy: BackupCopySummary) : RestoreUi

    /** `¿Restaurar este archivo?`: un archivo de afuera, del que no se sabe quién lo hizo. */
    data class ConfirmFile(val uri: String) : RestoreUi

    /** `¿Volver a antes de la actualización?`, con el día de la copia previa. */
    data class ConfirmBeforeUpdate(val createdAt: Instant) : RestoreUi

    /** `¿Volver a como estaba antes de restaurar?`, con el día de la copia local. */
    data class ConfirmUndoRestore(val copy: UndoRestoreCopy) : RestoreUi

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

    /**
     * La hoja `Copia cifrada`: la copia necesita su código de recuperación, y nada se descargó ni
     * cambió. [typed] es lo escrito, en grupos de 4: vive solo en memoria y la forma de texto del
     * estado no lo muestra. [request] dice si hubo un error de escritura o es el código de otras
     * copias, y [noCodeHelp] si se abrió la ayuda de `Sin el código`. [retry] es lo que se vuelve a
     * empezar con el código. [replacesCurrent] avisa que ese código reemplazará al guardado aquí.
     */
    class AskCode(
        val origin: RestoreOrigin,
        val retry: CodeRetry,
        val request: RecoveryCodeRequest,
        val typed: List<String> = EMPTY,
        val noCodeHelp: Boolean = false,
        val replacesCurrent: Boolean = false,
    ) : RestoreUi {
        fun copy(
            typed: List<String> = this.typed,
            request: RecoveryCodeRequest = this.request,
            noCodeHelp: Boolean = this.noCodeHelp,
        ) = AskCode(origin, retry, request, typed, noCodeHelp, replacesCurrent)

        override fun toString(): String = "AskCode(origin=$origin, request=$request)"

        companion object {
            /** Los 7 grupos vacíos del código. */
            val EMPTY: List<String> = List(RECOVERY_CODE_GROUPS) { "" }
        }
    }
}

/** Lo que se vuelve a empezar con el código escrito: una copia de la lista o un archivo elegido. */
sealed interface CodeRetry {
    data class Copy(val key: String) : CodeRetry

    /** [uri] solo vive en memoria, como lo escrito. */
    class File(val uri: String) : CodeRetry {
        override fun toString(): String = "File"
    }
}

/** Lo que la persona hace en la hoja `Copia cifrada`. */
sealed interface RestoreCodeEvent {
    /** Escribió o borró: [groups] es el código en grupos. La forma de texto no lo muestra. */
    class Typed(val groups: List<String>) : RestoreCodeEvent {
        override fun toString(): String = "Typed"
    }

    /** `Abrir copia`: empieza de nuevo con lo escrito. */
    data object Open : RestoreCodeEvent

    /** `Sin el código`: explica qué hacer. */
    data object WithoutCode : RestoreCodeEvent
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
        is RestoreProgress.AskRecoveryCode,
        -> null
    }

private fun noticeOf(problem: RestoreProblem, origin: RestoreOrigin): RestoreNotice =
    when (problem) {
        RestoreProblem.NOT_A_BACKUP -> RestoreNotice.NOT_A_BACKUP

        // Con un archivo elegido no hay lista de la que ofrecer otra: se pide probar otro archivo.
        RestoreProblem.DAMAGED, RestoreProblem.MARKED_FATAL ->
            when (origin) {
                RestoreOrigin.FILE -> RestoreNotice.TRY_ANOTHER_FILE

                RestoreOrigin.LIST,
                RestoreOrigin.BEFORE_UPDATE,
                RestoreOrigin.UNDO_RESTORE,
                -> RestoreNotice.DAMAGED
            }

        RestoreProblem.NEWER_VERSION -> RestoreNotice.NEWER_VERSION

        RestoreProblem.TOO_LARGE -> RestoreNotice.TOO_LARGE

        RestoreProblem.NEEDS_UPDATE -> RestoreNotice.NEEDS_UPDATE

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
                sensitive = true,
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
                sensitive = true,
            )

        is RestoreUi.ConfirmBeforeUpdate ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_undo_migration_title),
                body =
                    stringResource(
                        R.string.dialog_undo_migration_body,
                        dateOf(restore.createdAt),
                        stringResource(R.string.app_name),
                    ),
                confirmLabel = stringResource(R.string.dialog_go_back_action),
                onConfirm = { onAction(BackupAction.StartRestore) },
                onDismiss = { onAction(BackupAction.CloseRestore) },
                variant = ConfirmVariant.DESTRUCTIVE,
                dismissLabel = stringResource(R.string.dialog_go_back_exit),
                sensitive = true,
            )

        // Como volver a antes de la actualización: dice a qué día vuelven los datos y que lo
        // registrado después no estará.
        is RestoreUi.ConfirmUndoRestore ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_undo_restore_title),
                body =
                    stringResource(
                        R.string.dialog_undo_restore_body,
                        dateOf(restore.copy.createdAt),
                    ),
                confirmLabel = stringResource(R.string.dialog_go_back_action),
                onConfirm = { onAction(BackupAction.StartRestore) },
                onDismiss = { onAction(BackupAction.CloseRestore) },
                variant = ConfirmVariant.DESTRUCTIVE,
                dismissLabel = stringResource(R.string.dialog_go_back_exit),
                sensitive = true,
            )

        is RestoreUi.Working -> Progress(restore.origin, onAction)

        is RestoreUi.Asking -> {
            Progress(restore.origin, onAction)
            Question(restore.question, dateOf, onAction)
        }

        RestoreUi.Ready -> Progress(RestoreOrigin.FILE, onAction = null)

        is RestoreUi.AskCode -> EncryptedCopyCode(restore, onAction)
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
            CopyProvenance(copy)
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
 * De dónde puede venir la copia, antes de restaurarla. Solo avisa: ninguna se rechaza por esto.
 *
 * - Una copia cifrada se comprueba con el código al restaurarla; una en claro no tiene cómo probar
 *   de dónde salió.
 * - Con el cifrado activo, una copia en claro más nueva que la última cifrada lleva advertencia.
 * - En un teléfono que nunca vio este destino no hay referencia para saber si faltan copias.
 */
@Composable
private fun CopyProvenance(copy: BackupCopySummary) {
    if (copy.encrypted) {
        StatusTag(
            stringResource(R.string.recovery_origin_verified),
            icon = R.drawable.ic_lock,
            tone = StatusTone.PROFIT,
        )
    } else {
        StatusTag(stringResource(R.string.recovery_origin_unverified))
    }
    if (copy.plainNewerThanEncrypted) {
        NoticeCard(stringResource(R.string.recovery_plain_newer_warning), NoticeVariant.WARNING)
    }
    if (!copy.anchorKnown) {
        Text(
            stringResource(R.string.recovery_no_anchor),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                sensitive = true,
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
                sensitive = true,
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
                sensitive = true,
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
        is RestoreProgress.AskRecoveryCode,
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
        sensitive = true,
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

            RestoreNotice.NEEDS_UPDATE -> stringResource(
                R.string.rescue_premigration_incompatible,
                app,
            )
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

/**
 * La hoja `Copia cifrada`, a pantalla entera: pide el código de recuperación antes de reemplazar
 * nada. Va en la ventana de la actividad y no en una hoja aparte, para que toda ella quede sin
 * capturas ni Recientes, sin superposiciones de otras apps y sin toques tapados mientras se escribe.
 *
 * Un error de escritura o el código de otras copias se dicen en el campo, sin borrar lo escrito y
 * sin límite de intentos. `Sin el código` explica que nadie puede abrir la copia sin él.
 */
@Composable
private fun EncryptedCopyCode(asking: RestoreUi.AskCode, onAction: (BackupAction) -> Unit) {
    var obscured by remember { mutableStateOf(false) }
    SensitiveScreen { obscured = true }
    BackHandler { onAction(BackupAction.CancelRestore) }
    val spacing = AppTheme.spacing
    val complete = asking.typed.sumOf { it.length } == RECOVERY_CODE_LENGTH
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.s24),
                verticalArrangement = Arrangement.spacedBy(spacing.s16),
            ) {
                Text(
                    stringResource(R.string.recovery_restore_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.recovery_restore_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecoveryCodeField(
                    groups = asking.typed,
                    onGroupsChange = {
                        onAction(BackupAction.RestoreCode(RestoreCodeEvent.Typed(it)))
                    },
                    label = stringResource(R.string.recovery_field_label),
                    error =
                        when (asking.request) {
                            RecoveryCodeRequest.NEEDED -> null

                            RecoveryCodeRequest.TYPO -> stringResource(
                                R.string.recovery_check_mismatch,
                            )

                            RecoveryCodeRequest.OTHER_CODE ->
                                stringResource(R.string.recovery_restore_other_key)
                        },
                )
                // El código de la copia reemplazará al de este teléfono: se dice antes de seguir.
                if (asking.replacesCurrent) {
                    NoticeCard(
                        stringResource(R.string.dialog_restore_other_code_notice),
                        NoticeVariant.WARNING,
                    )
                }
                if (obscured) {
                    NoticeCard(stringResource(R.string.security_obscured_touch), NoticeVariant.INFO)
                }
                if (asking.noCodeHelp) {
                    NoticeCard(stringResource(R.string.recovery_no_code_body), NoticeVariant.INFO)
                } else {
                    InlineTextButton(
                        stringResource(R.string.recovery_no_code_action),
                        { onAction(BackupAction.RestoreCode(RestoreCodeEvent.WithoutCode)) },
                    )
                }
            }
            Column(
                Modifier
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .keepsMessagesAbove()
                    .padding(horizontal = spacing.s24, vertical = spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.recovery_restore_open_action),
                    onClick = { onAction(BackupAction.RestoreCode(RestoreCodeEvent.Open)) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = complete,
                )
                InlineTextButton(
                    stringResource(R.string.action_cancel),
                    { onAction(BackupAction.CancelRestore) },
                )
            }
        }
    }
}

/** Los 28 caracteres del código impreso: 26 del código y 2 de control. */
private const val RECOVERY_CODE_LENGTH = 28
