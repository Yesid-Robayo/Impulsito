package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * El estado del respaldo calculado, con lo que la pantalla necesita para decirlo.
 *
 * @property status el estado que gana por prioridad.
 * @property pendingChanges los cambios que todavía no tienen copia comprobada. No depende del
 *   destino.
 * @property savingSoon con [BackupStatus.PENDING], la espera todavía es la corta y el texto puede
 *   decir que se guardan en un momento. Pasado ese margen, dice que todavía no se pudo hacer la
 *   copia. Con cualquier otro estado es falso.
 */
data class BackupStatusReport(
    val status: BackupStatus,
    val pendingChanges: Long,
    val savingSoon: Boolean,
)
