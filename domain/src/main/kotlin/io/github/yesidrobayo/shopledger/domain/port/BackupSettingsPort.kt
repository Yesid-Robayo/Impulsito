package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.PinRelease

/**
 * Los ajustes del respaldo que la pantalla del respaldo ofrece con WebDAV: el consumo del mes,
 * liberar una copia fijada y volver a programar la subida cuando cambia la política con datos
 * móviles. Lo implementa la capa de datos.
 */
interface BackupSettingsPort {
    /** Los bytes subidos este mes, en la zona del negocio; cero si no se subió nada. No usa la red. */
    suspend fun uploadedThisMonth(): Long

    /**
     * Quita la marca de fijada a la copia [key] del destino conectado. Solo por una acción explícita
     * de la persona: nada la libera por tiempo. Nunca borra la copia.
     */
    suspend fun releasePinnedCopy(key: String): PinRelease

    /** Cambió la política con datos móviles: la subida en espera se encola con la nueva. */
    suspend fun onMeteredPolicyChanged()
}
