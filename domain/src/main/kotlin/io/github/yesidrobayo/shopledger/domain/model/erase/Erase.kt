package io.github.yesidrobayo.shopledger.domain.model.erase

/** Qué borra `Borrar mis datos`. Nunca hay uno elegido de antemano. */
enum class EraseScope {
    /** Solo lo de este teléfono. Las copias siguen en la nube y se pueden restaurar. */
    DEVICE_ONLY,

    /** Las copias y fotos del destino conectado ahora, y después todo lo de este teléfono. */
    DEVICE_AND_COPIES,
}

/**
 * En qué va un borrado ya confirmado, tal como queda guardado en el teléfono. Sobrevive a una
 * muerte del proceso: al volver, el borrado sigue desde aquí, nunca desde el principio ni más
 * adelante.
 */
enum class ErasePhase {
    /**
     * Borrando las copias del destino. Lo de este teléfono sigue intacto: si la nube falla, la
     * persona no se queda sin nada.
     */
    REMOTE,

    /** La nube ya está, o no se pidió: queda borrar este teléfono, y eso ya no se detiene. */
    LOCAL,
}

/** El destino que tiene el teléfono, visto por el borrado. */
enum class EraseDestination {
    /** Sin destino conectado: no hay copias que esta app alcance. */
    NONE,

    /** Un servicio WebDAV conectado, al que se llega por la red. */
    WEBDAV,

    /** Los tres archivos de la nube del teléfono: se le pide a esa app, sin red de esta. */
    SLOTS,

    /** Un destino cuyo acceso se perdió: la credencial no abre o los archivos no responden. */
    ACCESS_LOST,
}

/** Lo que el borrado necesita saber antes de empezar. */
data class EraseFacts(
    val destination: EraseDestination,
    val hasNetwork: Boolean,
    val restoreInProgress: Boolean,
)

/** Cómo terminó el pedido de borrar las copias del destino. */
enum class RemoteEraseResult {
    /** Ya no queda ninguna copia ni foto de esta app en el destino. */
    DELETED,

    /** Se borraron algunas y otras no: algo falló a mitad. */
    PARTIAL,

    /** No se llegó al destino: sin red, o el servicio no respondió. No se borró nada. */
    UNREACHABLE,

    /** El destino no deja borrar desde esta app. Se puede hacer a mano allí. */
    REFUSED,

    /** El destino ya no deja entrar: hay que reconectar. */
    ACCESS_LOST,
}

/** Por qué el borrado se detuvo. Salvo [LOCAL_FAILED], este teléfono no se tocó. */
enum class EraseStop {
    /** Hay una restauración en curso: borrar ahora la cruzaría. */
    RESTORE_IN_PROGRESS,

    /** Con WebDAV y sin internet no se llega a las copias. */
    OFFLINE,

    /** El acceso al destino se perdió: reconectar, o borrar solo este teléfono. */
    ACCESS_LOST,

    /** No se llegó al destino. */
    UNREACHABLE,

    /** Algunas copias siguen en el destino. */
    PARTIAL,

    /** El destino no deja borrar. */
    REFUSED,

    /** Lo de la nube ya se borró, pero el borrado local no terminó. Se retoma. */
    LOCAL_FAILED,
}

/** Cómo terminó un intento de `Borrar mis datos`. */
sealed interface EraseOutcome {
    /** Todo borrado: la app vuelve a empezar como recién instalada. */
    data object Finished : EraseOutcome

    /** Se detuvo por [reason]. El borrado guardado, si lo hay, sigue en espera. */
    data class Stopped(val reason: EraseStop) : EraseOutcome
}
