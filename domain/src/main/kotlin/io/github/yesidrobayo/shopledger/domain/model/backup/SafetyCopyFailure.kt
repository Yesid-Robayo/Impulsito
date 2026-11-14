package io.github.yesidrobayo.shopledger.domain.model.backup

/** Por qué no se guardó la copia local de seguridad previa a un cambio delicado. */
enum class SafetyCopyFailure {
    /** No hubo espacio en el teléfono para la copia. */
    NO_SPACE,

    /** Hay una restauración en curso o esperando una respuesta: la copia no se mezcla con ella. */
    BUSY,

    /**
     * Las dos copias de seguridad que ya hay guardan cambios que no llegaron al destino: la app
     * nunca descarta una sola sin preguntar.
     */
    UNSYNCED_COPIES,

    /** Otra falla del disco, o la base activa no se pudo copiar. */
    FAILED,
}
