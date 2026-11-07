package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings

/**
 * Lleva la cuenta de los fallos de transporte seguidos, para que un fallo sostenido no quede
 * invisible: cada uno se reintenta solo, y sin esta cuenta la persona vería para siempre una espera
 * normal. Al llegar al tope, el fallo queda a la vista como persistente, y el trabajo sigue
 * reintentando.
 *
 * Cuenta lo que no dejó nada y puede pasar solo: la red, el certificado, un error del servicio o un
 * servicio que pide esperar. También una ejecución que se quedó sin tiempo: una subida cortada no
 * se retoma, y sin contarla un enlace lento repetiría la subida entera sin fin y sin aviso. No
 * cuenta que el sistema pidiera parar, ni nada del teléfono o de una restauración.
 */
internal class TransportFailures(private val state: BackupStateDao) {
    /** Anota [run] si es un fallo de transporte. */
    suspend fun record(run: BackupRun) {
        val kind = failureKindOf(run) ?: return
        state.recordTransportFailure(
            kind = kind.name,
            limit = BackupTimings.TRANSPORT_FAILURES_BEFORE_PERSISTENT,
            persistentCode = BackupFailureCode.TRANSPORT_PERSISTENT.name,
        )
    }
}

/** La clase del fallo de transporte de [run], o nulo si no cuenta como uno. */
internal fun failureKindOf(run: BackupRun): BackupFailureKind? {
    if (run !is BackupRun.Retry) return null
    return when (run.reason) {
        RetryReason.TRANSIENT -> run.transientKind?.let { failureKindOf(it) }

        RetryReason.INTERRUPTED ->
            when (run.interruptReason) {
                InterruptReason.OUT_OF_TIME -> BackupFailureKind.NETWORK

                // Parar porque el sistema lo pidió, o porque la red pasó a medida, no es un fallo.
                InterruptReason.STOPPED, InterruptReason.METERED, null -> null
            }

        RetryReason.RESTORE_IN_PROGRESS,
        RetryReason.MIGRATION_PENDING,
        RetryReason.DATABASE_UNAVAILABLE,
        RetryReason.DESTINATION_NOT_READY,
        RetryReason.INCOMPLETE_LISTING,
        RetryReason.LOCAL_STATE_NOT_SAVED,
        RetryReason.LOCAL_FAILURE,
        -> null
    }
}

/** La clase guardada de un fallo pasajero del destino. */
internal fun failureKindOf(kind: TransientKind): BackupFailureKind = when (kind) {
    TransientKind.NETWORK -> BackupFailureKind.NETWORK
    TransientKind.TLS -> BackupFailureKind.TLS
    TransientKind.SERVER_ERROR -> BackupFailureKind.HTTP_5XX
    TransientKind.RATE_LIMIT -> BackupFailureKind.RATE_LIMIT
}
