package io.github.yesidrobayo.shopledger.data.destination

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * La prueba de conexión de un servicio WebDAV, antes de guardar nada: comprueba que la dirección y
 * la credencial sirven, que se puede crear la carpeta, escribir con el sufijo temporal, pasar un
 * archivo a su nombre final, que el servicio no pisa un archivo que ya existe, leer y borrar.
 *
 * - Ninguna petición se reintenta sola. Un `429` en particular detiene la prueba: algunos servicios
 *   bloquean un rato tras una contraseña mala, y repetir solo alarga el bloqueo.
 * - Si algo falla, borra `.sltest` y `.sltest.sltmp` si llegaron a existir, nunca la carpeta, y dice
 *   en qué paso falló.
 * - **La prueba entera tiene un tope de tiempo,** [CHECK_TIME_LIMIT_MILLIS]. Algunos servicios
 *   tardan minutos en un paso y después pasan, así que el tope es holgado; pero sin él, un servicio
 *   que responde muy despacio dejaría la pantalla esperando sin fin. Cada petición lleva lo que
 *   queda como tope de su llamada, y ninguna sale con el tiempo agotado: la prueba se detiene con
 *   [ConnectionFailure.TimedOut], sin guardar nada. El borrado final tiene su propio tope,
 *   [CLEANUP_TIME_LIMIT_MILLIS], para intentar borrar también lo que dejó una prueba cortada.
 * - La carpeta es la del nombre de la app, o la del primer nombre anterior que ya tenga copias de
 *   esta app, si la del nombre actual no tiene ninguna. Así un cambio de nombre comercial no deja
 *   las copias en una carpeta que la app ya no mira.
 *
 * @param random de dónde salen los bytes de prueba, distintos en cada ejecución.
 * @param elapsedMillis un reloj monótono en milisegundos, que nunca retrocede. Las pruebas lo
 *   cambian para simular un servicio lento sin esperar de verdad.
 */
internal class WebDavConnectionCheck(
    private val client: WebDavClient,
    private val random: SecureRandom = SecureRandom(),
    private val elapsedMillis: () -> Long = ::monotonicMillis,
) {
    /**
     * Corre la prueba. [folderNames] son los nombres de carpeta posibles, el actual primero y
     * después los anteriores, ya limpios con [DestinationIdentity.folderName].
     *
     * @param onStep se llama al empezar cada paso, para que la pantalla muestre en cuál va. Crear
     *   la carpeta se salta si ya existe.
     */
    suspend fun run(
        folderNames: List<String>,
        onStep: (ConnectionStep) -> Unit = {},
    ): ConnectionCheck {
        require(folderNames.isNotEmpty()) { "Hace falta al menos el nombre actual" }
        val deadline = Deadline(elapsedMillis, CHECK_TIME_LIMIT_MILLIS)
        onStep(ConnectionStep.ADDRESS)
        when (
            val base =
                client.propfind(WebDavPath(emptyList()), WebDavClient.Depth.ZERO, deadline)
        ) {
            is PropfindResult.Listed -> Unit

            PropfindResult.NotFound ->
                return failed(
                    ConnectionStep.ADDRESS,
                    ConnectionFailure.Unsupported(UnsupportedReason.BASE_URL_INVALID),
                )

            is PropfindResult.Failed -> return failed(ConnectionStep.ADDRESS, base.failure)
        }
        onStep(ConnectionStep.FIND_FOLDER)
        val folder =
            when (val found = chooseFolder(folderNames, deadline)) {
                is Found.Folder -> found
                is Found.Failed -> return found.check
            }
        if (!folder.exists) {
            onStep(ConnectionStep.CREATE_FOLDER)
            when (val made = client.mkcol(folder.path, deadline)) {
                MkcolResult.Created, MkcolResult.AlreadyExists -> Unit

                MkcolResult.MissingAncestor ->
                    return failed(
                        ConnectionStep.CREATE_FOLDER,
                        ConnectionFailure.Unsupported(UnsupportedReason.BASE_URL_INVALID),
                    )

                is MkcolResult.Failed -> return failed(ConnectionStep.CREATE_FOLDER, made.failure)
            }
        }
        val result = writeAndRead(folder.path, deadline, onStep)
        // Lo que haya quedado de la prueba se borra pase lo que pase, también con el tiempo ya
        // agotado: por eso el borrado lleva su propio tope. Un fallo al borrar solo cuenta si la
        // prueba iba bien: es lo que el paso de borrar comprueba.
        if (result == null) onStep(ConnectionStep.DELETE)
        val cleanup = cleanUp(folder.path, Deadline(elapsedMillis, CLEANUP_TIME_LIMIT_MILLIS))
        return when {
            result != null -> result
            cleanup != null -> failed(ConnectionStep.DELETE, cleanup)
            else -> ConnectionCheck.Passed(folder.path, folder.hasCopies)
        }
    }

    private sealed interface Found {
        data class Folder(val path: WebDavPath, val exists: Boolean, val hasCopies: Boolean) : Found

        data class Failed(val check: ConnectionCheck.Failed) : Found
    }

    private suspend fun chooseFolder(names: List<String>, deadline: Deadline): Found {
        var current: Found.Folder? = null
        for ((index, name) in names.withIndex()) {
            val path = WebDavPath(listOf(name))
            val listed =
                when (val found = client.propfind(path, WebDavClient.Depth.ONE, deadline)) {
                    is PropfindResult.Listed -> found.listing

                    PropfindResult.NotFound -> null

                    is PropfindResult.Failed -> return Found.Failed(
                        failed(ConnectionStep.FIND_FOLDER, found.failure),
                    )
                }
            if (listed != null && !listed.complete) {
                return Found.Failed(
                    failed(ConnectionStep.FIND_FOLDER, ConnectionFailure.Incomplete),
                )
            }
            val hasCopies = listed?.children.orEmpty().any {
                !it.isCollection &&
                    BackupFileName.parse(it.name) != null
            }
            val folder = Found.Folder(path, exists = listed != null, hasCopies = hasCopies)
            if (index == 0) current = folder
            if (hasCopies) return folder
        }
        return requireNotNull(current)
    }

    /** Los pasos que escriben, mueven y leen. Devuelve `null` si todos pasaron. */
    private suspend fun writeAndRead(
        folder: WebDavPath,
        deadline: Deadline,
        onStep: (ConnectionStep) -> Unit,
    ): ConnectionCheck.Failed? {
        val temporary = folder.file(TEST_FILE + BackupFileName.TEMPORARY_SUFFIX)
        val final = folder.file(TEST_FILE)
        // Lo que haya dejado una prueba anterior se borra antes: si no, el MOVE daría 412 de entrada.
        onStep(ConnectionStep.WRITE)
        cleanUp(folder, deadline)?.let { return failed(ConnectionStep.WRITE, it) }
        val first = ByteArray(TEST_BYTES).also(random::nextBytes)
        put(temporary, first, deadline)?.let { return failed(ConnectionStep.WRITE, it) }
        onStep(ConnectionStep.MOVE)
        when (val moved = client.move(temporary, final, control = deadline)) {
            is MoveResult.Moved -> Unit

            MoveResult.NotFound, MoveResult.Conflict, MoveResult.TargetExists ->
                return failed(
                    ConnectionStep.MOVE,
                    ConnectionFailure.Unsupported(UnsupportedReason.UNEXPECTED_STATUS),
                )

            is MoveResult.Failed -> return failed(ConnectionStep.MOVE, moved.failure)
        }
        onStep(ConnectionStep.NO_OVERWRITE)
        val second = ByteArray(TEST_BYTES).also(random::nextBytes)
        put(temporary, second, deadline)?.let { return failed(ConnectionStep.WRITE, it) }
        when (val again = client.move(temporary, final, control = deadline)) {
            // Lo esperado: el servicio no pisa un destino que existe.
            MoveResult.TargetExists -> Unit

            is MoveResult.Moved -> return failed(
                ConnectionStep.NO_OVERWRITE,
                ConnectionFailure.OverwriteNotRespected,
            )

            MoveResult.NotFound, MoveResult.Conflict ->
                return failed(
                    ConnectionStep.NO_OVERWRITE,
                    ConnectionFailure.Unsupported(UnsupportedReason.UNEXPECTED_STATUS),
                )

            is MoveResult.Failed -> return failed(ConnectionStep.NO_OVERWRITE, again.failure)
        }
        onStep(ConnectionStep.READ_BACK)
        val sink = ByteArrayOutputStream()
        return when (val read = client.get(final, sink, MAX_TEST_READ_BYTES, deadline)) {
            is GetResult.Downloaded ->
                if (sink.toByteArray().contentEquals(first)) {
                    null
                } else {
                    failed(ConnectionStep.READ_BACK, ConnectionFailure.ReadBackMismatch)
                }

            GetResult.NotFound, GetResult.TooLarge -> failed(
                ConnectionStep.READ_BACK,
                ConnectionFailure.ReadBackMismatch,
            )

            is GetResult.Failed -> failed(ConnectionStep.READ_BACK, read.failure)
        }
    }

    private suspend fun put(
        path: WebDavPath,
        bytes: ByteArray,
        deadline: Deadline,
    ): ConnectionFailure? =
        when (val put = client.putBytes(path, bytes, TEST_CONTENT_TYPE, deadline)) {
            is PutResult.Stored -> null

            is PutResult.MissingFolder -> ConnectionFailure.Unsupported(
                UnsupportedReason.BASE_URL_INVALID,
            )

            is PutResult.Failed -> ConnectionFailure.of(put.failure)
        }

    /** Borra los dos archivos de la prueba. Devuelve el primer fallo, o `null`. */
    private suspend fun cleanUp(folder: WebDavPath, deadline: Deadline): ConnectionFailure? {
        var failure: ConnectionFailure? = null
        for (name in listOf(TEST_FILE, TEST_FILE + BackupFileName.TEMPORARY_SUFFIX)) {
            val deleted = client.delete(folder.file(name), deadline)
            if (deleted is DeleteResult.Failed &&
                failure == null
            ) {
                failure = ConnectionFailure.of(deleted.failure)
            }
        }
        return failure
    }

    private fun failed(step: ConnectionStep, failure: WebDavFailure) =
        failed(step, ConnectionFailure.of(failure))

    private fun failed(step: ConnectionStep, failure: ConnectionFailure) =
        ConnectionCheck.Failed(step, failure)

    /**
     * Un tope de [limitMillis] desde ahora, en el reloj [elapsedMillis]. No se detiene por otra
     * cosa: la persona cancela la prueba cancelando la corrutina.
     */
    private class Deadline(private val elapsedMillis: () -> Long, limitMillis: Long) :
        TransferControl {
        private val endsAt = elapsedMillis() + limitMillis

        override fun isStopped(): Boolean = false

        override fun remainingMillis(): Long = (endsAt - elapsedMillis()).coerceAtLeast(0)

        override fun meteredStop(): Boolean = false

        override fun isMetered(): Boolean = false
    }

    companion object {
        /** El archivo de la prueba. Solo la prueba lo crea y lo borra. */
        const val TEST_FILE = ".sltest"

        /**
         * Lo más que dura la prueba entera, sin el borrado final: cinco minutos. Lo normal son unos
         * segundos, y en un simulacro con Koofr un solo paso tardó unos dos minutos y pasó.
         */
        const val CHECK_TIME_LIMIT_MILLIS = 5L * 60 * 1_000

        /** Lo más que dura el borrado final de los archivos de la prueba: un minuto. */
        const val CLEANUP_TIME_LIMIT_MILLIS = 60L * 1_000

        private const val TEST_BYTES = 64
        private const val MAX_TEST_READ_BYTES = 16L * 1024
        private const val TEST_CONTENT_TYPE = "application/octet-stream"
    }
}

/** El resultado de la prueba de conexión. Ninguno lleva texto del servicio. */
internal sealed interface ConnectionCheck {
    /**
     * Todo pasó.
     *
     * @property folder la carpeta de copias elegida.
     * @property hasCopies si esa carpeta ya tiene copias de esta app: la persona elige restaurar o
     *   empezar de cero.
     */
    data class Passed(val folder: WebDavPath, val hasCopies: Boolean) : ConnectionCheck

    /** Falló [step], por [failure]. No se guardó nada. */
    data class Failed(val step: ConnectionStep, val failure: ConnectionFailure) : ConnectionCheck
}

/** Los pasos de la prueba, para decir en cuál falló. */
internal enum class ConnectionStep {
    /** La dirección y la credencial. */
    ADDRESS,

    /** Buscar la carpeta de copias. */
    FIND_FOLDER,

    /** Crear la carpeta. */
    CREATE_FOLDER,

    /** Escribir con el sufijo temporal. */
    WRITE,

    /** Pasar al nombre final. */
    MOVE,

    /** No pisar un archivo que existe. */
    NO_OVERWRITE,

    /** Leer lo escrito. */
    READ_BACK,

    /** Borrar lo escrito. */
    DELETE,
}

/** Por qué falló la prueba, ya en lo que la pantalla le dice a la persona. */
internal sealed interface ConnectionFailure {
    /** `401`: el usuario o la contraseña no sirven. */
    data object CredentialRejected : ConnectionFailure

    /** `403` al listar: la credencial sirve, pero el servicio no deja entrar a esa carpeta. */
    data object Forbidden : ConnectionFailure

    /**
     * `429`: el servicio detuvo los intentos por un rato, a veces tras una contraseña que no sirvió.
     * No se reintenta solo, y no se guarda nada.
     */
    data object RateLimited : ConnectionFailure

    /** `423` o `5xx`: el servicio falló. La persona puede probar más tarde. */
    data object ServerError : ConnectionFailure

    /** Sin respuesta. Con [TransientKind.TLS], el certificado no se pudo comprobar, y nunca se ofrece seguir. */
    data class Unreachable(val kind: TransientKind) : ConnectionFailure

    /** No queda espacio en el servicio. */
    data object QuotaExceeded : ConnectionFailure

    /** El servicio no sirve para las copias. */
    data class Unsupported(val reason: UnsupportedReason) : ConnectionFailure

    /** El servicio pisó un archivo que existía aunque se le pidió que no. */
    data object OverwriteNotRespected : ConnectionFailure

    /** Lo que se leyó no es lo que se escribió. */
    data object ReadBackMismatch : ConnectionFailure

    /** Una lista con un error dentro. */
    data object Incomplete : ConnectionFailure

    /**
     * La prueba entera pasó de su tope de tiempo: el servicio respondía, pero muy despacio. No se
     * guardó nada, y se puede reintentar.
     */
    data object TimedOut : ConnectionFailure

    companion object {
        fun of(failure: WebDavFailure): ConnectionFailure = when (failure) {
            is WebDavFailure.PermissionLost ->
                if (failure.status == FORBIDDEN_STATUS) Forbidden else CredentialRejected

            is WebDavFailure.Unsupported -> Unsupported(failure.reason)

            is WebDavFailure.QuotaExceeded -> QuotaExceeded

            is WebDavFailure.Transient -> if (failure.status == 429) RateLimited else ServerError

            is WebDavFailure.Unreachable ->
                Unreachable(
                    if (failure.kind ==
                        TransportFailure.TLS
                    ) {
                        TransientKind.TLS
                    } else {
                        TransientKind.NETWORK
                    },
                )

            is WebDavFailure.Interrupted ->
                when (failure.reason) {
                    InterruptReason.OUT_OF_TIME -> TimedOut

                    // La prueba no se detiene por pedido del sistema ni mira la red medida: no
                    // llega aquí, y si llegara se dice como una falta de respuesta.
                    InterruptReason.STOPPED, InterruptReason.METERED ->
                        Unreachable(TransientKind.NETWORK)
                }
        }

        private const val FORBIDDEN_STATUS = 403
    }
}

/** Un reloj monótono en milisegundos: no cambia con la hora del teléfono. */
internal fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L
