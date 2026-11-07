package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Qué clase de conflicto detuvo las copias. Se guarda con su nombre en
 * `backup_state.conflict_kind`, así que los valores no se renombran.
 */
enum class BackupConflictKind {
    /** El destino tiene una copia más nueva de este mismo teléfono. */
    CLOUD_AHEAD,

    /** El destino tiene una copia de otro teléfono, o un documento cambió por fuera de la app. */
    OTHER_DEVICE,

    /** La copia nueva tendría menos datos que lo guardado. */
    LOCAL_SHRUNK,
}
