package io.github.yesidrobayo.shopledger.domain.model.backup

/** Cómo terminó la última comprobación completa de la cabeza, el simulacro sobre el destino. */
enum class BackupDrillResult {
    /** La cabeza se bajó entera, su suma coincidió y el validador la dejó restaurable. */
    PASSED,

    /** La cabeza no se pudo comprobar: no coincidió, no se abrió o el validador la rechazó. */
    FAILED,

    /** No hubo espacio en el teléfono para bajarla: no dice nada de la copia. */
    SKIPPED,
}
