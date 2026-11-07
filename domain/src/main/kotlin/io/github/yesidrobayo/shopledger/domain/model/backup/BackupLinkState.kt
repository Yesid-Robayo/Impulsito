package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * El enlace con el destino de las copias. Se guarda con su nombre en `backup_state.link_state`, así
 * que los valores no se renombran.
 */
enum class BackupLinkState {
    /** Nunca se conectó un destino, o la persona lo desconectó. No hay nada que subir. */
    NOT_CONNECTED,

    /** Hay un destino conectado, y el respaldo sube a él. */
    CONNECTED,

    /**
     * El destino ya no deja entrar. Nada sube hasta que la persona vuelva a conectar, y los cambios
     * se siguen contando.
     */
    PERMISSION_LOST,

    /** El destino tiene algo que la app no debe pisar sin preguntar. Nada sube hasta decidir. */
    CONFLICT,
}
