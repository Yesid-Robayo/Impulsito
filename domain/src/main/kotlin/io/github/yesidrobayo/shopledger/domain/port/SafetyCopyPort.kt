package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.SafetyCopyFailure

/**
 * Guarda una copia local de seguridad de la base activa antes de un cambio delicado, como corregir
 * la moneda del negocio. Es la misma copia que se guarda antes de restaurar: vive en el teléfono,
 * nunca se sube y se conserva 30 días.
 */
fun interface SafetyCopyPort {
    /** Guarda la copia. Devuelve `null` si quedó guardada y comprobada, o por qué no. */
    suspend fun saveBeforeChange(): SafetyCopyFailure?
}
