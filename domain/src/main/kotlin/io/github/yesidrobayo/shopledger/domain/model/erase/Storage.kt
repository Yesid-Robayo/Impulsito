package io.github.yesidrobayo.shopledger.domain.model.erase

/**
 * Lo que ocupa la app en el teléfono, en bytes. Cada valor es `null` si no se pudo medir: la fila
 * dice `No disponible` y nada se bloquea.
 *
 * @property businessBytes la base del negocio, con su `-wal` y su `-shm`.
 * @property photoBytes las fotos de `media/`.
 * @property safetyCopyBytes las copias locales de seguridad: la de antes de restaurar o de un
 *   cambio delicado, la previa a migrar y la base dañada que se apartó.
 * @property otherBytes lo demás: ajustes del teléfono, temporales y archivos para compartir.
 * @property freeBytes lo libre en el teléfono para la app.
 */
data class StorageUsage(
    val businessBytes: Long?,
    val photoBytes: Long?,
    val safetyCopyBytes: Long?,
    val otherBytes: Long?,
    val freeBytes: Long?,
)

/**
 * Lo que `Borrar mis datos` muestra antes de confirmar, sin montos ni nombres.
 *
 * @property records los registros del negocio, con el mismo conteo neutro de las copias.
 * @property products lo que hay en el catálogo.
 * @property photos las fotos guardadas en el teléfono.
 * @property unsyncedChanges los cambios que todavía no tienen copia, y se perderían.
 * @property photosPending las fotos sin respaldar en el servicio WebDAV, o `null` si no aplica.
 * @property providerLabel el nombre de la nube del teléfono, con ranuras.
 * @property backupFailed si la última copia falló: lo no respaldado se pierde al borrar.
 * @property pending un borrado confirmado que quedó en espera, si lo hay.
 */
data class EraseSummary(
    val records: Long,
    val products: Long,
    val photos: Int,
    val unsyncedChanges: Long,
    val photosPending: Int?,
    val destination: EraseDestination,
    val providerLabel: String?,
    val backupFailed: Boolean,
    val restoreInProgress: Boolean,
    val pending: ErasePhase?,
)
