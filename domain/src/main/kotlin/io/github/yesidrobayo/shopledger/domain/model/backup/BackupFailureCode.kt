package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Por qué no quedó una copia nueva en la última ejecución del respaldo. Se guarda con su nombre en
 * `backup_state.failure_code`, que la guarda de la tabla limita a esta lista, así que los valores no
 * se renombran.
 *
 * El acceso perdido y el conflicto no están aquí: son estados del enlace con el destino, en
 * [BackupLinkState], porque esperan una decisión de la persona y no un reintento.
 */
enum class BackupFailureCode {
    /** El destino no tiene espacio. Nunca se poda para hacer sitio. */
    QUOTA_EXCEEDED,

    /** La base del teléfono está dañada: la instantánea o su comprobación fallaron. No se sube. */
    LOCAL_CORRUPT,

    /** No hay espacio en el teléfono para armar la copia. */
    LOCAL_STORAGE_LOW,

    /** La copia tiene un fallo que la restauración repara: sube igual, sin contar como válida. */
    LOCAL_INVARIANT,

    /** La copia tiene un fallo que la app no puede restaurar: los cambios siguen pendientes. */
    LOCAL_INVARIANT_FATAL,

    /** Lo que se subió no está en el destino, o no es lo que se subió. Nunca se confirma. */
    VERIFY_MISMATCH,

    /** La base, o la copia comprimida, pasa del tope que la restauración acepta. */
    BACKUP_TOO_LARGE,

    /** La cabeza del destino viene de una versión más nueva de la app. Hay que actualizarla. */
    CLOUD_SCHEMA_NEWER,

    /** El destino no sirve para las copias, o respondió algo que la app no acepta. */
    DESTINATION_UNSUPPORTED,

    /** Varios fallos de transporte seguidos. El trabajo sigue reintentando. */
    TRANSPORT_PERSISTENT,

    /** El cifrado está activo y su clave no está en el teléfono. Nunca se sube en claro. */
    ENCRYPTION_KEY_UNAVAILABLE,

    /** Algo que la app no esperaba. Solo la clase de la falla queda en el registro. */
    UNEXPECTED,
}
