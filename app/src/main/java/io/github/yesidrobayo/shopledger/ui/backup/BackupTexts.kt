package io.github.yesidrobayo.shopledger.ui.backup

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.StaleReason
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusLook
import io.github.yesidrobayo.shopledger.ui.components.BackupTone
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Lo que la pantalla necesita para decir el estado del respaldo, además del estado: el nombre de la
 * nube del teléfono, si hay ranuras, y la zona del negocio, para decir `ayer, 21:14`.
 *
 * @param sessionRequired si la sesión de la nube del teléfono se cerró: sin acceso, la salida es
 *   iniciar sesión allí, no crear archivos nuevos.
 */
data class BackupWording(
    val overview: BackupOverview,
    val providerLabel: String?,
    val zone: BusinessTimeZone?,
    val sessionRequired: Boolean = false,
) {
    /** Con la nube del teléfono, la app entrega y no confirma: cambian las palabras. */
    val slots: Boolean get() = overview.destinationKind == BackupDestinationKind.DOCUMENT_SLOTS
}

/**
 * Si la pantalla ofrece `Guardar en {provider}` como acción principal: con la nube del teléfono no
 * hay copias automáticas, y con cambios sin copia es lo único que los lleva a esa nube. Mientras
 * sube o restaura, o con un estado que pide otra acción, no.
 */
fun BackupWording.offersSaveToProvider(): Boolean {
    val report = overview.report
    if (!slots || report.pendingChanges <= 0) return false
    return when (report.status) {
        BackupStatus.PENDING,
        BackupStatus.STALE,
        BackupStatus.FAILED,
        BackupStatus.CHECK_DUE,
        -> true

        BackupStatus.RESTORING,
        BackupStatus.NOT_CONNECTED,
        BackupStatus.POLICY_PENDING,
        BackupStatus.PERMISSION_LOST,
        BackupStatus.CONFLICT,
        BackupStatus.NOTHING_TO_BACK_UP,
        BackupStatus.UPLOADING,
        BackupStatus.HANDED_OFF,
        BackupStatus.UP_TO_DATE,
        BackupStatus.BLOCKED_BY_DATA_SAVER,
        BackupStatus.WAITING_FOR_WIFI,
        BackupStatus.WAITING_FOR_NETWORK,
        -> false
    }
}

/**
 * Si tocar `Guardar en {provider}` dice que la hora del teléfono parece atrasada: con la nube del
 * teléfono y un día sin copia porque la hora de ahora es anterior a la última entrega o a un cambio
 * sin copia. Con esa hora la entrega puede no salir, y el toque no puede quedar en silencio.
 *
 * El botón sigue activo a propósito: un reloj raro nunca detiene una copia, y la misma señal sale
 * cuando la hora estuvo adelantada y ya se corrigió, con registros que quedaron en el futuro.
 * Apagarlo dejaría sin forma de guardar hasta que la hora real los alcance.
 */
fun BackupWording.warnsClockOnSave(): Boolean = slots && overview.staleReason == StaleReason.CLOCK

/**
 * El nombre del botón que pide una copia: `Respaldar ahora`, o con la nube del teléfono
 * `Guardar en {provider}`, porque allí cada copia es a mano.
 */
@Composable
fun backupNowLabel(wording: BackupWording?): String = if (wording?.slots == true) {
    stringResource(
        R.string.backup_action_save_to_provider,
        wording.providerLabel ?: NO_VALUE_TEXT,
    )
} else {
    stringResource(R.string.backup_action_now)
}

/** El tono de cada estado, como lo fija el sistema de diseño. */
fun BackupStatus.tone(): BackupTone = when (this) {
    BackupStatus.UP_TO_DATE -> BackupTone.OK

    BackupStatus.NOTHING_TO_BACK_UP -> BackupTone.NEUTRAL

    BackupStatus.HANDED_OFF,
    BackupStatus.PENDING,
    BackupStatus.UPLOADING,
    BackupStatus.RESTORING,
    BackupStatus.WAITING_FOR_NETWORK,
    BackupStatus.WAITING_FOR_WIFI,
    -> BackupTone.PENDING

    BackupStatus.NOT_CONNECTED,
    BackupStatus.POLICY_PENDING,
    BackupStatus.CHECK_DUE,
    BackupStatus.BLOCKED_BY_DATA_SAVER,
    BackupStatus.STALE,
    -> BackupTone.WARNING

    BackupStatus.FAILED, BackupStatus.PERMISSION_LOST, BackupStatus.CONFLICT -> BackupTone.ERROR
}

/** El icono de cada estado. Subiendo y restaurando llevan el indicador en su lugar. */
@DrawableRes
fun BackupStatus.icon(): Int = when (this) {
    BackupStatus.NOT_CONNECTED, BackupStatus.WAITING_FOR_NETWORK -> R.drawable.ic_cloud_off

    BackupStatus.POLICY_PENDING -> R.drawable.ic_pause_circle

    BackupStatus.NOTHING_TO_BACK_UP, BackupStatus.UP_TO_DATE -> R.drawable.ic_cloud_done

    BackupStatus.HANDED_OFF -> R.drawable.ic_outbox

    BackupStatus.CHECK_DUE -> R.drawable.ic_fact_check

    BackupStatus.PENDING,
    BackupStatus.UPLOADING,
    BackupStatus.RESTORING,
    -> R.drawable.ic_cloud_upload

    BackupStatus.WAITING_FOR_WIFI -> R.drawable.ic_wifi_off

    BackupStatus.BLOCKED_BY_DATA_SAVER -> R.drawable.ic_data_saver_on

    BackupStatus.STALE -> R.drawable.ic_history

    BackupStatus.FAILED, BackupStatus.PERMISSION_LOST, BackupStatus.CONFLICT -> R.drawable.ic_error
}

/**
 * Si el estado pide atención en las raíces: el banner. Las esperas normales y lo que está bien
 * nunca lo muestran: avisar un estado normal lo haría sonar a problema.
 */
fun BackupStatus.needsBanner(): Boolean = when (this) {
    BackupStatus.NOT_CONNECTED,
    BackupStatus.POLICY_PENDING,
    BackupStatus.CHECK_DUE,
    BackupStatus.BLOCKED_BY_DATA_SAVER,
    BackupStatus.STALE,
    BackupStatus.FAILED,
    BackupStatus.PERMISSION_LOST,
    BackupStatus.CONFLICT,
    -> true

    BackupStatus.RESTORING,
    BackupStatus.NOTHING_TO_BACK_UP,
    BackupStatus.UPLOADING,
    BackupStatus.HANDED_OFF,
    BackupStatus.UP_TO_DATE,
    BackupStatus.WAITING_FOR_WIFI,
    BackupStatus.WAITING_FOR_NETWORK,
    BackupStatus.PENDING,
    -> false
}

/**
 * El estado en pocas palabras, para el chip de Inicio. Con WebDAV y todo comprobado dice cuándo fue
 * la última copia; con las esperas, cuántos cambios faltan; con lo demás, el título del estado.
 * Nunca dice `Todo guardado` con ranuras.
 */
@Composable
fun backupChipLook(wording: BackupWording): BackupStatusLook {
    val report = wording.overview.report
    val status = report.status
    val text =
        when (status) {
            BackupStatus.UP_TO_DATE -> lastCopyText(wording) ?: backupTitle(wording)

            BackupStatus.PENDING, BackupStatus.WAITING_FOR_NETWORK, BackupStatus.WAITING_FOR_WIFI ->
                pendingChanges(report.pendingChanges)

            // Cada estado se nombra: uno nuevo no debe caer en silencio en el título genérico.
            BackupStatus.RESTORING,
            BackupStatus.NOT_CONNECTED,
            BackupStatus.POLICY_PENDING,
            BackupStatus.PERMISSION_LOST,
            BackupStatus.CONFLICT,
            BackupStatus.NOTHING_TO_BACK_UP,
            BackupStatus.UPLOADING,
            BackupStatus.STALE,
            BackupStatus.FAILED,
            BackupStatus.CHECK_DUE,
            BackupStatus.HANDED_OFF,
            BackupStatus.BLOCKED_BY_DATA_SAVER,
            -> backupTitle(wording)
        }
    return BackupStatusLook(
        text = text,
        tone = status.tone(),
        icon = status.icon(),
        busy = status == BackupStatus.UPLOADING || status == BackupStatus.RESTORING,
    )
}

/** El estado con su título, para el banner y el bloque de la pantalla del respaldo. */
@Composable
fun backupTitleLook(wording: BackupWording): BackupStatusLook {
    val status = wording.overview.report.status
    return BackupStatusLook(
        text = backupTitle(wording),
        tone = status.tone(),
        icon = status.icon(),
        busy = status == BackupStatus.UPLOADING || status == BackupStatus.RESTORING,
    )
}

/**
 * El estado para el banner de Inicio: el chip ya lo nombra, así que el banner lo explica con su
 * detalle, como la última copia y el motivo de un día sin copia. Sin detalle, el título.
 */
@Composable
fun backupBannerLook(wording: BackupWording): BackupStatusLook {
    val look = backupTitleLook(wording)
    return backupDetail(wording)?.let { look.copy(text = it) } ?: look
}

/** El título del estado, el de la tabla de la pantalla del respaldo. */
@Composable
fun backupTitle(wording: BackupWording): String {
    val report = wording.overview.report
    val provider = wording.providerLabel ?: NO_VALUE_TEXT
    val count = report.pendingChanges.toCount()
    return when (report.status) {
        BackupStatus.NOT_CONNECTED -> stringResource(R.string.backup_state_not_connected)

        BackupStatus.POLICY_PENDING -> stringResource(R.string.backup_state_policy_pending)

        BackupStatus.NOTHING_TO_BACK_UP -> stringResource(R.string.backup_state_nothing)

        BackupStatus.UP_TO_DATE -> stringResource(R.string.backup_state_up_to_date_title)

        BackupStatus.HANDED_OFF -> stringResource(R.string.backup_state_handed_off, provider)

        BackupStatus.CHECK_DUE -> stringResource(R.string.backup_state_check_due, provider)

        BackupStatus.PENDING -> pendingChanges(report.pendingChanges)

        BackupStatus.WAITING_FOR_NETWORK ->
            pluralStringResource(R.plurals.backup_waiting_network_title, count, count)

        BackupStatus.WAITING_FOR_WIFI ->
            pluralStringResource(R.plurals.backup_waiting_wifi_title, count, count)

        BackupStatus.BLOCKED_BY_DATA_SAVER ->
            pluralStringResource(R.plurals.backup_data_saver_title, count, count)

        BackupStatus.UPLOADING -> stringResource(R.string.backup_state_uploading)

        BackupStatus.RESTORING -> stringResource(R.string.backup_state_restoring)

        BackupStatus.STALE -> stringResource(R.string.backup_state_stale)

        BackupStatus.FAILED -> failureTitle(wording)

        BackupStatus.PERMISSION_LOST ->
            if (wording.sessionRequired) {
                stringResource(R.string.backup_session_lost_title, provider)
            } else {
                stringResource(R.string.backup_state_permission_lost)
            }

        BackupStatus.CONFLICT -> stringResource(R.string.backup_state_conflict)
    }
}

/** El detalle bajo el título: qué pasó, cuánto está en riesgo, o cuándo fue la última copia. */
@Composable
fun backupDetail(wording: BackupWording): String? {
    val overview = wording.overview
    val report = overview.report
    val app = stringResource(R.string.app_name)
    val provider = wording.providerLabel ?: NO_VALUE_TEXT
    return when (report.status) {
        BackupStatus.NOT_CONNECTED -> stringResource(R.string.backup_state_not_connected_detail)

        BackupStatus.POLICY_PENDING -> stringResource(R.string.backup_policy_pending_detail)

        BackupStatus.NOTHING_TO_BACK_UP ->
            if (wording.slots) {
                stringResource(R.string.backup_state_nothing_slots_detail, provider)
            } else {
                stringResource(R.string.backup_state_nothing_detail)
            }

        BackupStatus.UP_TO_DATE, BackupStatus.HANDED_OFF -> lastCopyText(wording)

        BackupStatus.CHECK_DUE -> stringResource(R.string.backup_check_due_detail, app)

        // Con la nube del teléfono nada sale solo: se entrega al tocar `Guardar en {provider}`.
        BackupStatus.PENDING ->
            when {
                wording.slots -> stringResource(
                    R.string.backup_state_pending_slots_detail,
                    provider,
                )

                !report.savingSoon -> stringResource(R.string.backup_state_pending_late_detail)

                else -> stringResource(R.string.backup_state_pending_detail)
            }

        BackupStatus.WAITING_FOR_NETWORK -> stringResource(R.string.backup_waiting_network_detail)

        BackupStatus.WAITING_FOR_WIFI -> waitingWifiDetail(overview)

        BackupStatus.BLOCKED_BY_DATA_SAVER -> stringResource(R.string.backup_data_saver_detail, app)

        BackupStatus.UPLOADING -> stringResource(R.string.backup_uploading_detail)

        BackupStatus.RESTORING -> stringResource(R.string.backup_restoring_detail)

        BackupStatus.STALE -> staleDetail(wording, app)

        BackupStatus.FAILED -> failureBody(wording, app)

        BackupStatus.PERMISSION_LOST ->
            if (wording.sessionRequired) {
                stringResource(R.string.backup_session_lost_detail, provider)
            } else {
                stringResource(R.string.backup_permission_lost_detail)
            }

        BackupStatus.CONFLICT -> stringResource(R.string.backup_conflict_detail)
    }
}

/**
 * `Última copia: hace 12 min`, o `Última entrega:` con ranuras, desde la última copia comprobada.
 * Nulo si nunca hubo una.
 */
@Composable
fun lastCopyText(wording: BackupWording): String? {
    val last = wording.overview.lastSuccessAt ?: return null
    val moment = relativeMoment(last, wording.overview.computedAt, wording.zone)
    return when {
        moment == null && wording.slots -> stringResource(R.string.backup_last_handoff_moment)
        moment == null -> stringResource(R.string.backup_last_copy_moment)
        wording.slots -> stringResource(R.string.backup_last_handoff, moment)
        else -> stringResource(R.string.backup_last_copy, moment)
    }
}

/**
 * Cuánto hace de [then], con la escala de la guía de redacción: nulo antes de un minuto, para la
 * frase `justo ahora`; minutos y horas dentro del mismo día; `ayer, 21:14`; y antes, la fecha con la
 * hora. Con el reloj atrasado, o sin la zona del negocio, la fecha con la hora o las horas.
 */
@Composable
fun relativeMoment(then: Instant, now: Instant, zone: BusinessTimeZone?): String? {
    val date = LocalAppFormats.current.date
    val elapsed = now - then
    if (elapsed < 0.minutes) {
        return zone?.let { date.dayMonthTime(then, it) } ?: date.hoursAgo(0)
    }
    if (elapsed < 1.minutes) return null
    if (elapsed < 1.hours) return date.minutesAgo(elapsed.inWholeMinutes)
    if (zone == null) return date.hoursAgo(elapsed.inWholeHours)
    val today = BusinessDate.of(now, zone)
    val day = BusinessDate.of(then, zone)
    return when {
        day == today && elapsed < 1.days -> date.hoursAgo(elapsed.inWholeHours)

        day == today.plusDays(-1) ->
            stringResource(R.string.format_yesterday_at, date.time(then, zone))

        else -> date.dayMonthTime(then, zone)
    }
}

@Composable
private fun pendingChanges(pending: Long): String {
    val count = pending.toCount()
    return pluralStringResource(R.plurals.backup_pending_changes, count, count)
}

@Composable
private fun waitingWifiDetail(overview: BackupOverview): String {
    val next = overview.nextMeteredUploadAt
    return if (overview.meteredPolicy != MeteredBackupPolicy.WIFI_ONLY && next != null &&
        next > overview.computedAt
    ) {
        // Se redondea hacia arriba: decir 0 min mientras todavía falta medio minuto sería falso.
        val minutes = (next - overview.computedAt).inWholeSeconds.let {
            (it + SECONDS - 1) / SECONDS
        }
        stringResource(R.string.backup_waiting_wifi_interval_detail, minutes.toString())
    } else {
        stringResource(R.string.backup_waiting_wifi_only_detail)
    }
}

@Composable
private fun staleDetail(wording: BackupWording, app: String): String {
    val overview = wording.overview
    val last = overview.lastSuccessAt
    val date =
        when {
            last == null -> NO_VALUE_TEXT
            wording.zone != null -> LocalAppFormats.current.date.dayMonthTime(last, wording.zone)
            else -> NO_VALUE_TEXT
        }
    val reason =
        when (overview.staleReason) {
            StaleReason.CLOCK -> stringResource(R.string.backup_reason_clock)
            StaleReason.NO_WIFI -> stringResource(R.string.backup_reason_no_wifi)
            StaleReason.NETWORK -> stringResource(R.string.backup_reason_network)
            null -> ""
        }
    val text =
        if (wording.slots) {
            stringResource(R.string.backup_stale_slots_detail, date, reason, app)
        } else {
            stringResource(R.string.backup_stale_detail, date, reason)
        }
    // Sin motivo conocido, el hueco del motivo no deja un espacio doble.
    return text.replace("  ", " ")
}

@Composable
private fun failureTitle(wording: BackupWording): String {
    val app = stringResource(R.string.app_name)
    return when (wording.overview.failureCode) {
        BackupFailureCode.QUOTA_EXCEEDED -> stringResource(
            R.string.backup_failure_quota_exceeded_title,
        )

        BackupFailureCode.LOCAL_CORRUPT -> stringResource(
            R.string.backup_failure_local_corrupt_title,
        )

        BackupFailureCode.LOCAL_STORAGE_LOW ->
            stringResource(R.string.backup_failure_local_storage_low_title)

        BackupFailureCode.LOCAL_INVARIANT ->
            if (wording.slots) {
                stringResource(R.string.backup_failure_local_invariant_slots_title)
            } else {
                stringResource(R.string.backup_failure_local_invariant_title)
            }

        BackupFailureCode.LOCAL_INVARIANT_FATAL ->
            if (wording.slots) {
                stringResource(R.string.backup_failure_local_invariant_fatal_slots_title)
            } else {
                stringResource(R.string.backup_failure_local_invariant_fatal_title)
            }

        BackupFailureCode.VERIFY_MISMATCH ->
            stringResource(R.string.backup_failure_verify_mismatch_title)

        BackupFailureCode.BACKUP_TOO_LARGE ->
            stringResource(R.string.backup_failure_backup_too_large_title)

        BackupFailureCode.CLOUD_SCHEMA_NEWER ->
            stringResource(R.string.backup_failure_cloud_schema_newer_title, app)

        BackupFailureCode.DESTINATION_UNSUPPORTED ->
            if (wording.slots) {
                stringResource(R.string.backup_failure_destination_unsupported_slots_title)
            } else {
                stringResource(R.string.backup_failure_destination_unsupported_title)
            }

        BackupFailureCode.TRANSPORT_PERSISTENT ->
            stringResource(R.string.backup_failure_transport_persistent_title)

        BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE ->
            stringResource(R.string.backup_failure_encryption_key_unavailable_title)

        BackupFailureCode.UNEXPECTED, null -> stringResource(
            R.string.backup_failure_unexpected_title,
        )
    }
}

/**
 * El cuerpo de cada fallo. `LOCAL_INVARIANT` cuenta los productos afectados; si el número no se
 * sabe, el estado va sin cuerpo antes que con un número inventado.
 */
@Composable
private fun failureBody(wording: BackupWording, app: String): String? {
    val provider = wording.providerLabel ?: NO_VALUE_TEXT
    val last = wording.overview.lastSuccessAt
    val zone = wording.zone
    val lastDate = if (last != null &&
        zone != null
    ) {
        LocalAppFormats.current.date.dayMonthTime(last, zone)
    } else {
        null
    }
    return when (wording.overview.failureCode) {
        BackupFailureCode.QUOTA_EXCEEDED -> stringResource(
            R.string.backup_failure_quota_exceeded_body,
        )

        BackupFailureCode.LOCAL_CORRUPT -> stringResource(
            R.string.backup_failure_local_corrupt_body,
        )

        BackupFailureCode.LOCAL_STORAGE_LOW ->
            stringResource(R.string.backup_failure_local_storage_low_body)

        BackupFailureCode.LOCAL_INVARIANT -> invariantBody(wording.overview.invariantFailures, app)

        BackupFailureCode.LOCAL_INVARIANT_FATAL ->
            when {
                wording.slots && lastDate != null ->
                    stringResource(
                        R.string.backup_failure_local_invariant_fatal_slots_body,
                        provider,
                        lastDate,
                    )

                wording.slots ->
                    stringResource(R.string.backup_failure_local_invariant_fatal_slots_body_no_date)

                lastDate != null ->
                    stringResource(R.string.backup_failure_local_invariant_fatal_body, lastDate)

                else -> stringResource(R.string.backup_failure_local_invariant_fatal_body_no_date)
            }

        BackupFailureCode.VERIFY_MISMATCH ->
            if (wording.slots) {
                stringResource(R.string.backup_failure_verify_mismatch_slots_body, provider, app)
            } else {
                stringResource(R.string.backup_failure_verify_mismatch_body)
            }

        BackupFailureCode.BACKUP_TOO_LARGE ->
            stringResource(R.string.backup_failure_backup_too_large_body, app)

        BackupFailureCode.CLOUD_SCHEMA_NEWER ->
            stringResource(R.string.backup_failure_cloud_schema_newer_body)

        BackupFailureCode.DESTINATION_UNSUPPORTED ->
            if (wording.slots) {
                stringResource(R.string.backup_failure_destination_unsupported_slots_body, provider)
            } else {
                stringResource(R.string.backup_failure_destination_unsupported_body, provider)
            }

        BackupFailureCode.TRANSPORT_PERSISTENT ->
            stringResource(R.string.backup_failure_transport_persistent_body)

        BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE ->
            stringResource(R.string.backup_failure_encryption_key_unavailable_body)

        BackupFailureCode.UNEXPECTED, null -> stringResource(
            R.string.backup_failure_unexpected_body,
            app,
        )
    }
}

/** Un conteo para un plural: nunca pasa de lo que cabe en un `Int`. */
private fun Long.toCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private const val SECONDS = 60L

/**
 * Cuántos productos tienen un stock que no coincide con su historial, y que la app no corrige sola,
 * o `null` si la verificación no dejó el número.
 */
@Composable
fun invariantBody(count: Long, app: String): String? {
    if (count <= 0) return null
    val shown = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(R.plurals.backup_failure_local_invariant_body, shown, shown, app)
}
