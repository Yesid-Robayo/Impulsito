package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Un servicio WebDAV sugerido. La lista es fija, viene con la app y no se traduce: son nombres
 * propios, y un servicio solo entra si pasó la prueba de servicios en un teléfono. La app acepta
 * cualquier otro servicio WebDAV.
 *
 * @property key su clave estable, que no cambia con el idioma.
 * @property url la dirección que llena, ya con `https://`.
 * @property usesAccountPassword si el servicio pide aquí la contraseña normal de la cuenta, que da
 *   entrada a toda la cuenta.
 * @property declaresLimits si su página declara límites propios que pueden detener las copias.
 */
data class WebDavSuggestion(
    val key: String,
    val name: String,
    val url: String,
    val usesAccountPassword: Boolean,
    val declaresLimits: Boolean,
)

/**
 * Lo que la persona escribió para conectar el servicio. La contraseña solo vive en memoria: nunca
 * va a un estado guardado, al disco ni a un registro.
 */
class WebDavLogin(val url: String, val username: String, val password: String) {
    /** Sin la contraseña ni la dirección: un `toString()` nunca debe llevarlas a un registro. */
    override fun toString(): String = "WebDavLogin"
}

/** Lo que dice una dirección antes de usarla, sin abrir ninguna conexión. */
sealed interface WebDavAddress {
    /** Se puede usar. [asciiHost] es el servidor tal como viajará, en ASCII, para confirmarlo. */
    data class Accepted(val asciiHost: String) : WebDavAddress

    /** No se puede usar, por [problem]. */
    data class Rejected(val problem: WebDavAddressProblem) : WebDavAddress
}

/** Por qué no se usa una dirección. */
enum class WebDavAddressProblem {
    /** `http://` u otro esquema: solo se admite `https://`. */
    NOT_HTTPS,

    /** No es una dirección, o lleva usuario, contraseña, `?` o `#`. */
    INVALID,
}

/** Los siete pasos de la prueba de conexión, en el orden en que corren. */
enum class WebDavTestStep {
    /** `PROPFIND`: leer la carpeta. */
    LIST,

    /** `MKCOL`: crear la carpeta, si no existe. */
    CREATE_FOLDER,

    /** `PUT` con el nombre temporal. */
    UPLOAD,

    /** `MOVE` al nombre final. */
    MOVE,

    /** Otro `PUT` y `MOVE`, que el servicio debe rechazar para no pisar. */
    NO_OVERWRITE,

    /** `GET` de lo escrito. */
    DOWNLOAD,

    /** `DELETE` del archivo de prueba. */
    DELETE,
}

/** Por qué falló la prueba, en lo que la pantalla le dice a la persona. */
enum class WebDavTestFailure {
    /** `401`: el usuario o la contraseña no sirven. */
    CREDENTIAL_REJECTED,

    /** `403` al listar: no deja entrar a esa carpeta. */
    FORBIDDEN,

    /** `429`: el servicio detuvo los intentos por un rato. No se reintenta solo. */
    RATE_LIMITED,

    /** Sin respuesta, o el servicio falló: se puede reintentar. */
    UNREACHABLE,

    /**
     * El servicio respondía, pero la prueba entera pasó de su tope de tiempo. Se detuvo sin guardar
     * nada, y se puede reintentar más tarde.
     */
    TIMED_OUT,

    /** El certificado no se pudo comprobar. Nunca se ofrece seguir. */
    INSECURE,

    /** `507`: no queda espacio. */
    NO_SPACE,

    /** La dirección no lleva a una carpeta del servicio, como un `409` al crear la carpeta. */
    BASE_URL,

    /** Una redirección a otro servidor, que la app no siguió. */
    REDIRECT,

    /** El servicio no sirve para las copias: rechazó o respondió mal un paso. */
    UNSUPPORTED,
}

/**
 * Un servicio que pasó la prueba, todavía sin guardar. No lleva la contraseña.
 *
 * @property destinationId el identificador del destino, de la dirección y el usuario.
 * @property folderName la carpeta de copias que eligió la prueba, dentro de la dirección.
 * @property hasCopies si esa carpeta ya tenía copias de esta app.
 */
data class TestedWebDav(val destinationId: String, val folderName: String, val hasCopies: Boolean)

/** Cómo terminó la prueba de conexión. Nada de esto guarda algo en el teléfono. */
sealed interface WebDavTestResult {
    data class Passed(val tested: TestedWebDav) : WebDavTestResult

    /** La dirección no se acepta, y no se abrió ninguna conexión. */
    data class AddressRejected(val problem: WebDavAddressProblem) : WebDavTestResult

    /** Falta la dirección, el usuario o la contraseña. No se abrió ninguna conexión. */
    data object MissingField : WebDavTestResult

    /** Falló [step], por [failure]. El archivo de prueba se borró si alcanzó a crearse. */
    data class Failed(val step: WebDavTestStep, val failure: WebDavTestFailure) : WebDavTestResult
}

/** Cómo terminó guardar un servicio ya probado. */
sealed interface WebDavSaveResult {
    /** Quedó conectado, y la primera copia se programó. */
    data class Connected(val hasCopies: Boolean) : WebDavSaveResult

    /** No se pudo guardar, y no quedó nada guardado: ni la credencial ni el destino. */
    data object SaveFailed : WebDavSaveResult
}

/**
 * El destino conectado ahora, leído sin red, para saber si conectar otro es cambiar de destino y
 * para llenar `Reconectar`.
 *
 * @property baseUrl con WebDAV, la dirección que se escribió, sin la carpeta de copias.
 * @property account con WebDAV, el usuario.
 * @property label con WebDAV, el servidor; con la nube del teléfono, su nombre, si se sabe.
 */
data class CurrentDestination(
    val kind: BackupDestinationKind,
    val destinationId: String,
    val baseUrl: String?,
    val account: String?,
    val label: String?,
)
