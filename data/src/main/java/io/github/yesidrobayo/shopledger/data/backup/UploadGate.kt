package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadGateVerdict
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.backup.UploadCadence
import kotlin.time.Instant

/**
 * La compuerta de red del paso 0: lo único que hace cumplir el intervalo entre copias automáticas.
 * Encolar sin reemplazar puede dejar vivo un trabajo programado antes de que otra subida terminara,
 * y por eso cada ejecución automática vuelve a mirar al empezar.
 */
internal fun interface UploadGate {
    /**
     * Lo que decide para el destino [kind], con la última subida terminada en
     * [lastUploadFinishedAt], en milisegundos, y la red [metered], medida o ninguna.
     */
    suspend fun check(
        kind: BackupDestinationKind,
        lastUploadFinishedAt: Long?,
        metered: Boolean,
    ): UploadGateVerdict

    companion object {
        /** Deja pasar todo: para las pruebas del motor que no miran el ritmo. */
        val Open = UploadGate { _, _, _ -> UploadGateVerdict.PROCEED }
    }
}

/**
 * La compuerta con la política con datos móviles de la persona y el reloj del teléfono.
 *
 * @param policy cada cuánto se respalda con datos móviles. Lee la base.
 */
internal class PolicyUploadGate(
    private val policy: suspend () -> MeteredBackupPolicy,
    private val clock: Clock,
) : UploadGate {
    override suspend fun check(
        kind: BackupDestinationKind,
        lastUploadFinishedAt: Long?,
        metered: Boolean,
    ): UploadGateVerdict = UploadCadence.gate(
        kind = kind,
        policy = policy(),
        metered = metered,
        lastUploadFinishedAt = lastUploadFinishedAt?.let(Instant::fromEpochMilliseconds),
        now = clock.now(),
    )
}

/**
 * El control de una ejecución, con el corte por red medida que decide la compuerta: una subida
 * que empezó en una red sin datos móviles, y que con datos móviles habría tenido que esperar, se
 * corta si la red pasa a medida. Así un cambio de red a mitad no gasta datos móviles sin contar
 * el intervalo.
 */
internal class RunControl(private val base: TransferControl) : TransferControl by base {
    /** Si la red que pasa a medida corta la subida. Lo fija la compuerta al empezar. */
    @Volatile var cutOnMetered: Boolean = false

    override fun meteredStop(): Boolean = base.meteredStop() || (cutOnMetered && base.isMetered())
}

/**
 * Con el servicio lleno, si una copia automática sale sin generar nada: generarla y subirla
 * gastaría la subida entera para recibir el mismo rechazo.
 *
 * - `Respaldar ahora` siempre sigue: la cuota avisa, no decide.
 * - Con un espacio libre informado, sale si la copia esperada no cabe. Sin tamaño esperado, sigue.
 * - Sin espacio informado, o con uno negativo, no hay tope conocido: lo intenta una vez cada
 *   [BackupTimings.QUOTA_RETRY] desde el intento anterior. Un reloj atrasado cuenta como vencido.
 */
internal object QuotaGate {
    /**
     * @param failureCode el fallo guardado.
     * @param quotaAvailableBytes el espacio libre que informó el servicio al listar, o nulo.
     * @param expectedBytes lo que pesaría la copia: la subida a medias o la última copia, o nulo.
     * @param lastAttemptAt el intento anterior a esta ejecución, en milisegundos, o nulo.
     */
    fun holds(
        failureCode: String?,
        backupNow: Boolean,
        quotaAvailableBytes: Long?,
        expectedBytes: Long?,
        lastAttemptAt: Long?,
        nowMillis: Long,
    ): Boolean {
        if (backupNow || failureCode != BackupFailureCode.QUOTA_EXCEEDED.name) return false
        if (quotaAvailableBytes != null && quotaAvailableBytes >= 0) {
            return expectedBytes != null && expectedBytes > quotaAvailableBytes
        }
        val last = lastAttemptAt ?: return false
        if (nowMillis < last) return false
        return nowMillis - last < BackupTimings.QUOTA_RETRY.inWholeMilliseconds
    }
}
