package io.github.yesidrobayo.shopledger.data.log

import io.github.yesidrobayo.shopledger.domain.port.LogEvent

/** Lo que registra la capa de datos. Cada texto es fijo y no lleva datos del negocio. */
internal enum class DataEvent(override val text: String) : LogEvent {
    DATABASE_OPENED("Base abierta"),
    DATABASE_OPEN_FAILED("La base no abrió"),
    DATABASE_MISSING("Falta la base, que ya había sido creada, y no se abre"),
    DATABASE_FROM_NEWER_APP("La base es de una versión más nueva de la app, y no se abre"),
    MIGRATION_LEFT_TO_APP("Migración pendiente, que espera a la app"),
    PRE_MIGRATION_COPY_MISSING("Sin copia previa, no se migra"),
    MIGRATION_FAILED("La migración falló, y la base sigue en su versión"),
    DATABASE_MIGRATED("Base migrada"),
    INITIALIZED_MARK_FAILED("No se pudo dejar la marca de base creada"),
}
