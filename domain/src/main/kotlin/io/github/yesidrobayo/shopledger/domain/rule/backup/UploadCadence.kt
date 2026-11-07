package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadGateVerdict
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadSchedule
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * El ritmo de las copias automáticas: cuándo sale una subida y cuándo debe esperar.
 *
 * - **Con Wi-Fi**, poco después de cada cambio: la espera junta los cambios seguidos, y una racha
 *   no la pospone más de [BackupTimings.BACKUP_MAX_WAIT].
 * - **Con datos móviles**, a lo sumo una cada intervalo de la política, contado desde que terminó
 *   de enviarse la subida anterior, para no gastar el plan de la persona. Con `Solo con Wi-Fi`,
 *   ninguna.
 * - **Con la nube del teléfono**, a lo sumo una cada [BackupTimings.SLOT_MIN_INTERVAL], con
 *   cualquier red o sin ella: cada escritura es una subida completa de la otra app.
 *
 * **Un reloj raro nunca detiene una copia.** Una hora anterior a la que se guardó cuenta como plazo
 * vencido, igual que si no hubiera hora guardada.
 *
 * `Respaldar ahora` no pasa por aquí: sale con cualquier red y sin intervalo.
 */
object UploadCadence {
    /**
     * Los trabajos que se encolan con el destino [kind], la política [policy] y la última subida
     * terminada en [lastUploadFinishedAt].
     *
     * Con [pastInterval] el cambio más viejo sin copia ya lleva un día, o el reloj retrocedió: la
     * subida con cualquier red sale sin retraso. Con `Solo con Wi-Fi` igual no hay subida con datos
     * móviles, porque la política de la persona no la permite.
     */
    fun schedule(
        kind: BackupDestinationKind,
        policy: MeteredBackupPolicy,
        lastUploadFinishedAt: Instant?,
        now: Instant,
        pastInterval: Boolean,
    ): UploadSchedule = when (kind) {
        BackupDestinationKind.WEBDAV -> {
            val interval = BackupTimings.meteredInterval(policy)
            UploadSchedule(
                unmetered = true,
                anyNetworkDelay =
                    interval?.let {
                        if (pastInterval) {
                            Duration.ZERO
                        } else {
                            remaining(
                                it,
                                lastUploadFinishedAt,
                                now,
                            )
                        }
                    },
                anyNetworkNeedsNetwork = true,
            )
        }

        BackupDestinationKind.DOCUMENT_SLOTS ->
            UploadSchedule(
                unmetered = false,
                anyNetworkDelay =
                    if (pastInterval) {
                        Duration.ZERO
                    } else {
                        remaining(BackupTimings.SLOT_MIN_INTERVAL, lastUploadFinishedAt, now)
                    },
                anyNetworkNeedsNetwork = false,
            )
    }

    /**
     * Lo que decide la compuerta al empezar una subida automática que no espera un día: [metered] es
     * la red activa al empezar, medida o ninguna. Con la nube del teléfono la red no importa.
     */
    fun gate(
        kind: BackupDestinationKind,
        policy: MeteredBackupPolicy,
        metered: Boolean,
        lastUploadFinishedAt: Instant?,
        now: Instant,
    ): UploadGateVerdict {
        val interval =
            when (kind) {
                BackupDestinationKind.WEBDAV -> {
                    if (!metered) return UploadGateVerdict.PROCEED
                    BackupTimings.meteredInterval(policy)
                        ?: return UploadGateVerdict.WAIT_FOR_UNMETERED
                }

                BackupDestinationKind.DOCUMENT_SLOTS -> BackupTimings.SLOT_MIN_INTERVAL
            }
        return if (remaining(interval, lastUploadFinishedAt, now) == Duration.ZERO) {
            UploadGateVerdict.PROCEED
        } else {
            UploadGateVerdict.WAIT_FOR_INTERVAL
        }
    }

    /**
     * Si la racha de cambios ya esperó demasiado: el cambio más viejo sin copia, en
     * [firstUnsyncedAt], tiene [BackupTimings.BACKUP_MAX_WAIT] o más, o es del futuro. Sin cambio
     * sin copia no hay racha.
     */
    fun maxWaitReached(firstUnsyncedAt: Instant?, now: Instant): Boolean {
        val oldest = firstUnsyncedAt ?: return false
        return now < oldest || now - oldest >= BackupTimings.BACKUP_MAX_WAIT
    }

    /**
     * Lo que le falta a [interval] desde [since], o cero si ya venció, si no hay hora o si la hora
     * es anterior a [since].
     */
    private fun remaining(interval: Duration, since: Instant?, now: Instant): Duration {
        if (since == null || now < since) return Duration.ZERO
        val left = interval - (now - since)
        return if (left > Duration.ZERO) left else Duration.ZERO
    }
}
