package io.github.yesidrobayo.shopledger.data.destination

/**
 * El resultado de una operación sobre el destino de las copias.
 *
 * Un fallo esperado nunca es una excepción: llega como [Failure], con lo que el motor de respaldo
 * necesita para decidir, y sin ningún texto del servicio ni de la plataforma. Así nada de lo que
 * diga el servidor, ni su dirección, llega a un log o a la pantalla.
 */
internal sealed interface DestinationResult<out T> {
    data class Success<out T>(val value: T) : DestinationResult<T>

    data class Failure(val failure: DestinationFailure) : DestinationResult<Nothing>
}

/**
 * Por qué falló una operación, ya traducido a lo que el motor hace con ello. Cada caso dice qué
 * estado deja el respaldo y si el trabajo se reintenta.
 */
internal sealed interface DestinationFailure {
    /**
     * El destino ya no deja entrar: la credencial no abre o fue rechazada, o el documento de una
     * ranura perdió el permiso o ya no está. El estado pasa a acceso perdido, el trabajo termina
     * sin reintento y nunca abre una pantalla: la persona vuelve a conectar cuando quiera.
     *
     * @property sessionRequired verdadero si la otra app pide iniciar sesión: la salida es abrir
     *   esa app, no volver a elegir los documentos.
     */
    data class PermissionLost(val sessionRequired: Boolean = false) : DestinationFailure

    /**
     * El destino no sirve para las copias, o respondió algo que la app no acepta. Termina sin
     * reintento, sin confirmar ni podar nada.
     */
    data class Unsupported(val reason: UnsupportedReason) : DestinationFailure

    /** No queda espacio en el destino. Nunca lleva a podar para hacer sitio. */
    data object QuotaExceeded : DestinationFailure

    /**
     * No se pudo guardar en el teléfono el estado que acompaña al destino, como el de las ranuras:
     * falló el disco del teléfono, no el destino ni la red. Lo guardado antes sigue intacto, y si
     * era la marca de una escritura, el documento no llegó a abrirse. El trabajo se reintenta más
     * tarde, y nunca se cuenta como un fallo de red.
     */
    data object LocalStorage : DestinationFailure

    /**
     * Lo que se subió no está, o no es lo que se subió. La copia nunca se confirma, ni después por
     * otra vía.
     */
    data object VerifyMismatch : DestinationFailure

    /**
     * Un documento de las ranuras cambió por fuera de la app: otro teléfono, o la persona, lo
     * escribió. No se escribe nada hasta que la persona decida.
     */
    data object ChangedOutside : DestinationFailure

    /**
     * Las ranuras no dejan ningún lugar donde escribir sin tapar algo que hay que conservar. La
     * copia nueva tiene menos datos que lo guardado, y la persona decide.
     */
    data object NoSafeSlot : DestinationFailure

    /**
     * La respuesta se leyó bien, pero traía un error dentro. Una lista así nunca significa carpeta
     * vacía, y con ella no se decide ni se poda: el trabajo se reintenta.
     */
    data object Incomplete : DestinationFailure

    /**
     * No hubo respuesta útil y puede pasar sola: red caída, certificado, tiempo agotado, o el
     * servicio pidió esperar. Los reintentos dentro de la ejecución ya se hicieron: el trabajo se
     * reintenta más tarde y suma un fallo de transporte.
     */
    data class Transient(val kind: TransientKind) : DestinationFailure

    /**
     * La operación se detuvo a propósito, sin dejar nada con nombre final a medias. El archivo
     * local se conserva para el siguiente intento.
     */
    data class Interrupted(val reason: InterruptReason) : DestinationFailure
}

/** Por qué un destino no sirve, para decir en qué paso falló. */
internal enum class UnsupportedReason {
    /** El servicio rechazó el nombre del archivo. */
    NAME_REJECTED,

    /** El servicio prohíbe esa operación fuera del listado. */
    FORBIDDEN,

    /** El servicio no admite ese método. */
    METHOD_NOT_ALLOWED,

    /** El archivo es demasiado grande para el servicio. */
    TOO_LARGE,

    /** El servicio no implementa ese método. */
    NOT_IMPLEMENTED,

    /** Una redirección que no es a la misma dirección. Ninguna se sigue. */
    REDIRECT,

    /** Falta una carpeta de la dirección base, o no se pudo crear la de las copias. */
    BASE_URL_INVALID,

    /** El XML, la codificación, el rango o el tamaño de la respuesta no se aceptan. */
    INVALID_RESPONSE,

    /** Un código sin regla propia, o un éxito que no corresponde a ese método. */
    UNEXPECTED_STATUS,

    /** Un documento de ranura sin tamaño, o un proveedor que rechaza la operación. */
    PROVIDER_REJECTED,
}

/** Qué clase de fallo pasajero fue, para el contador de fallos seguidos. */
internal enum class TransientKind {
    /** Red caída, conexión cortada o tiempo de espera agotado por la red. */
    NETWORK,

    /** El certificado no se pudo comprobar. Nunca se ofrece seguir. */
    TLS,

    /** El servicio respondió con un error propio. */
    SERVER_ERROR,

    /** El servicio pidió esperar, o el recurso estaba bloqueado. */
    RATE_LIMIT,
}

/** Por qué se detuvo una operación antes de terminar. */
internal enum class InterruptReason {
    /** El sistema pidió detener el trabajo. No suma un fallo. */
    STOPPED,

    /** Se agotó el tiempo de la ejecución. Suma un fallo de red: una subida cortada no dejó nada. */
    OUT_OF_TIME,

    /** La red pasó a medida durante una subida que solo debía ir por red no medida. */
    METERED,
}
