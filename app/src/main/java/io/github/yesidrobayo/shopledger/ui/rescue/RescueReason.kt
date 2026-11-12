package io.github.yesidrobayo.shopledger.ui.rescue

/**
 * Por qué la app muestra la pantalla de rescate, en los términos de la interfaz. La actividad lo
 * traduce de lo que dijo la compuerta del arranque: las pantallas nunca ven la capa de datos.
 */
sealed interface RescueReason {
    /** La base ya había sido creada en este teléfono y su archivo falta, o está vacío. */
    data object DatabaseMissing : RescueReason

    /** La base viene de una versión más nueva de la app. */
    data object NewerVersion : RescueReason

    /** La base no abrió: está dañada, no es una base de la app, o el disco falló al leerla. */
    data object OpenFailed : RescueReason

    /** Una migración falló, y la base sigue en su versión anterior. */
    data object MigrationFailed : RescueReason

    /** No se pudo guardar la copia previa a migrar, así que no se migró. */
    data object PreMigrationCopyFailed : RescueReason

    /** No cabe la copia previa a migrar. [missingBytes] es cuánto hay que liberar. */
    data class NotEnoughSpace(val missingBytes: Long) : RescueReason

    /** El diario de una restauración no se entiende, y se apartó sin tocar ninguna base. */
    data object RestoreJournalUnreadable : RescueReason

    /** Una restauración se deshizo y no había una base anterior que devolver. */
    data object RestoreLeftNoDatabase : RescueReason

    /**
     * La app se cerró tres veces seguidas al abrir: el modo seguro. La base no se abrió, y nada se
     * restaura ni se borra solo.
     */
    data object SafeMode : RescueReason
}

/** Lo que hace la compuerta mientras la app espera la base, en los términos de la interfaz. */
enum class OpeningStage {
    /** Abre la base: se ve solo el papel. */
    OPENING,

    /** Copia y migra: `Actualizando los datos`. */
    MIGRATING,

    /** Termina el reemplazo de una restauración: `Restaurando los datos`. */
    RESTORING,
}
