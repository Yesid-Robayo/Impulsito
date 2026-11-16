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
 * - **Con la nube del teléfono, ninguna.** La prueba con Drive y la app cerrada no dio escrituras
 *   válidas: nada garantiza que esa nube reciba un archivo escrito en segundo plano. Cada copia la
 *   pide la persona con `Guardar en {provider}`, y solo se escribe con la app a la vista
 *   ([manualGate]). Con la app cerrada no se programa ni se escribe nada en esa nube.
 *
 * **Un reloj raro nunca detiene una copia.** Una hora anterior a la que se guardó cuenta como plazo
 * vencido, igual que si no hubiera hora guardada.
 *
 * `Respaldar ahora` no pasa por aquí: sale con cualquier red y sin intervalo.
 */
object UploadCadence {
    /**
     * Si el destino [kind] tiene copias automáticas, que salen sin que la persona las pida. Solo
     * WebDAV: con la nube del teléfono, cada copia es a mano.
     */
    fun automatic(kind: BackupDestinationKind): Boolean = kind == BackupDestinationKind.WEBDAV

    /**
     * Los trabajos que se encolan con el destino [kind], la política [policy] y la última subida
     * terminada en [lastUploadFinishedAt].
     *
     * Con [pastInterval] el cambio más viejo sin copia ya lleva un día, o el reloj retrocedió: la
     * subida con cualquier red sale sin retraso. Con `Solo con Wi-Fi` igual no hay subida con datos
     * móviles, porque la política de la persona no la permite.
     *
     * Con la nube del teléfono no se encola ninguna: ni con un día sin copia.
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
                anyNetworkDelay = null,
                anyNetworkNeedsNetwork = false,
            )
    }

    /**
     * Lo que decide la compuerta al empezar una subida automática que no espera un día: [metered] es
     * la red activa al empezar, medida o ninguna. Con la nube del teléfono nunca deja pasar una
     * automática: espera a la persona.
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

                BackupDestinationKind.DOCUMENT_SLOTS -> return UploadGateVerdict.WAIT_FOR_PERSON
            }
        return if (remaining(interval, lastUploadFinishedAt, now) == Duration.ZERO) {
            UploadGateVerdict.PROCEED
        } else {
            UploadGateVerdict.WAIT_FOR_INTERVAL
        }
    }

    /**
     * La compuerta de una escritura en la nube del teléfono: solo sale la que pidió la persona
     * ([requested]), y solo con la app a la vista ([appVisible]). Así ningún trabajo escribe
     * con la app cerrada, tampoco el reintento de una copia pedida que el sistema corre más
     * tarde, ni uno que quedó encolado de una versión anterior.
     */
    fun manualGate(requested: Boolean, appVisible: Boolean): UploadGateVerdict =
        if (requested && appVisible) {
            UploadGateVerdict.PROCEED
        } else {
            UploadGateVerdict.WAIT_FOR_PERSON
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
