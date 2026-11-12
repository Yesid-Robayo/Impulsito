package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.HealthFacts

/**
 * Lo que `Salud de mis datos` lee aparte del estado del respaldo: la revisión, la lectura completa,
 * el simulacro, el bloqueo de pantalla y si se sabe el espacio del servicio. Solo lee, sin red.
 */
fun interface DataHealthSource {
    /** Lo leído, o nulo si la base no abre. */
    suspend fun facts(): HealthFacts?
}
