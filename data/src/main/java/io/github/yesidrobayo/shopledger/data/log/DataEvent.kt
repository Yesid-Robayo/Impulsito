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
    PRE_MIGRATION_NO_SPACE("Sin espacio para la copia previa, no se migra"),
    PRE_MIGRATION_COPIED("Copia previa guardada"),
    PRE_MIGRATION_COPY_KEPT("Copia previa conservada"),
    PRE_MIGRATION_COPY_FAILED("La copia previa falló"),
    MIGRATION_FAILED("La migración falló, y la base sigue en su versión"),
    DATABASE_MIGRATED("Base migrada"),
    INITIALIZED_MARK_FAILED("No se pudo dejar la marca de base creada"),
    RESTORE_FINISHED("Restauración terminada"),
    RESTORE_ROLLED_BACK("Restauración deshecha, con la base anterior"),
    RESTORE_LEFT_TO_APP("Restauración pendiente, que espera a la app"),
    RESTORE_JOURNAL_UNREADABLE("Diario de restauración ilegible, apartado sin tocar ninguna base"),
    RESTORE_LEFT_NO_DATABASE("Restauración deshecha sin base que devolver, y no se abre"),
    RESTORE_PREPARED("Restauración preparada, falta reiniciar"),
    RESTORE_CANCELLED("Restauración cancelada, la base no cambió"),
    RESTORE_REFUSED("Copia rechazada, la base no cambió"),
    RESTORE_NO_SPACE("Sin espacio para restaurar, la base no cambió"),
    RESTORE_NEEDS_CODE("Copia cifrada sin su código en este teléfono, la base no cambió"),
    RESTORE_FAILED("La preparación de la restauración falló, la base no cambió"),
    RESTORE_CLOSE_FAILED("No se pudo cerrar la base tras preparar la restauración"),
    RESTORE_AUDIT_FAILED("No se pudo anotar la restauración en la auditoría"),
    ENCRYPTION_AUDIT_FAILED("No se pudo anotar el apagado del cifrado en la auditoría"),
    POLICY_CONSENT_SAVED("Aceptación de la política guardada"),
    POLICY_CONSENT_NOT_SAVED("No se pudo guardar la aceptación de la política"),
    DESTINATION_DISCONNECTED("Destino desconectado"),
    DESTINATION_AUDIT_FAILED("No se pudo anotar el cambio del destino en la auditoría"),
    DESTINATION_TIDY_FAILED(
        "No se pudo terminar de soltar el destino anterior; se repite al abrir",
    ),
    SLOT_SESSION_RESUMED("La nube del teléfono volvió tras iniciar sesión"),
}
