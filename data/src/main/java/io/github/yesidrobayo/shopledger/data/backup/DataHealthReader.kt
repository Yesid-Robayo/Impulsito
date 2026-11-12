package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCheckFacts
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDrillResult
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthFacts
import io.github.yesidrobayo.shopledger.domain.port.DataHealthSource
import kotlin.time.Instant

/**
 * Lee lo que `Salud de mis datos` muestra aparte del estado: de la fila del respaldo, la revisión,
 * la lectura completa y el simulacro; del teléfono, el bloqueo de pantalla; y de la última sonda,
 * si se sabe el espacio del servicio. Nunca escribe, ni usa la red.
 *
 * @param state la fila del respaldo, o nula si la base no abre.
 * @param deviceLocked si el teléfono tiene bloqueo de pantalla.
 * @param serviceSpaceKnown si la última sonda supo el espacio libre del servicio.
 * @param recoveryCheck la prueba periódica del código, leída de `recovery/state.json`.
 */
internal class DataHealthReader(
    private val state: suspend () -> BackupStateEntity?,
    private val deviceLocked: () -> Boolean,
    private val serviceSpaceKnown: suspend () -> Boolean,
    private val recoveryCheck: suspend () -> RecoveryCheckFacts = { RecoveryCheckFacts.NONE },
) : DataHealthSource {
    override suspend fun facts(): HealthFacts? {
        val row = state() ?: return null
        val code = recoveryCheck()
        return HealthFacts(
            lastHumanCheckAt = row.lastHumanCheckAt?.let(Instant::fromEpochMilliseconds),
            lastFullVerifyAt = row.lastFullVerifyAt?.let(Instant::fromEpochMilliseconds),
            lastDrillAt = row.lastDrillAt?.let(Instant::fromEpochMilliseconds),
            // Un nombre que esta versión no conoce no se presenta como ningún resultado.
            lastDrillResult =
                BackupDrillResult.entries.firstOrNull { it.name == row.lastDrillResult },
            deviceLocked = deviceLocked(),
            serviceSpaceKnown = serviceSpaceKnown(),
            codeAvailable = code.active,
            codeCheckDue = code.due,
            lastCodeCheckAt = code.lastCheckAt,
        )
    }
}
