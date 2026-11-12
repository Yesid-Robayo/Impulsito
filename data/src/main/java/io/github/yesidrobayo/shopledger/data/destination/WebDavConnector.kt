package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Conecta un servicio WebDAV para producción: revisa la dirección, corre la prueba de conexión y,
 * solo si todo pasó, sella la credencial, deja el destino en la base y escribe `link.json`.
 *
 * - **Nada se guarda si algo falla.** La credencial se sella después de la prueba, y si la base no
 *   queda escrita se borra de nuevo.
 * - **Solo `https://`.** Una dirección con `http://`, con usuario o contraseña dentro, o con `?` o
 *   `#`, se rechaza sin abrir ninguna conexión.
 * - **La carpeta sale del nombre de la app,** que nunca se traduce, o de un nombre anterior que ya
 *   tenga copias. Es la misma en cualquier idioma.
 * - **`link.json` va de último:** si algo se corta antes, el arranque ve que difiere de la base.
 *
 * Quien llama corre esto bajo el cerrojo del respaldo, y la pantalla ya pidió confirmar el host.
 *
 * @param http el único cliente de la app. Las pruebas pasan uno que lleva las peticiones a un
 *   servidor falso, sin cambiar nada de lo que decide este código.
 */
internal class WebDavConnector(
    private val http: OkHttpClient,
    private val credentials: WebDavCredentialStore,
    private val links: DestinationLinkStore,
) {
    /**
     * Revisa la dirección y corre la prueba de conexión completa, sin guardar nada.
     *
     * @param appName el nombre de la app, del recurso que nunca se traduce.
     * @param previousAppNames los nombres comerciales anteriores, en el orden del recurso.
     * @param onStep se llama al empezar cada paso de la prueba.
     */
    suspend fun check(
        url: String,
        username: String,
        password: String,
        appName: String,
        previousAppNames: List<String>,
        onStep: (ConnectionStep) -> Unit = {},
    ): CheckOutcome {
        val endpoint =
            when (val parsed = WebDavEndpoint.parse(url)) {
                is EndpointParse.Accepted -> parsed.endpoint

                is EndpointParse.Rejected -> return CheckOutcome.Stopped(
                    ConnectResult.AddressRejected(parsed.reason),
                )
            }
        if (username.isEmpty() || password.isEmpty()) {
            return CheckOutcome.Stopped(ConnectResult.MissingField)
        }
        val folderNames =
            (listOf(appName) + previousAppNames).mapNotNull(DestinationIdentity::folderName)
                .distinct()
        if (folderNames.firstOrNull() != DestinationIdentity.folderName(appName)) {
            return CheckOutcome.Stopped(ConnectResult.InvalidAppName)
        }
        val client = WebDavClient(endpoint, WebDavCredential(username, password), http)
        return when (val check = WebDavConnectionCheck(client).run(folderNames, onStep)) {
            is ConnectionCheck.Passed ->
                CheckOutcome.Passed(
                    CheckedWebDav(
                        endpoint = endpoint,
                        username = username,
                        folder = check.folder,
                        hasCopies = check.hasCopies,
                    ),
                )

            is ConnectionCheck.Failed ->
                CheckOutcome.Stopped(ConnectResult.CheckFailed(check.step, check.failure))
        }
    }

    /**
     * Guarda una conexión que ya pasó la prueba: sella la credencial, deja el destino en el estado
     * del respaldo y escribe `link.json`, en ese orden. Si la base no queda escrita, la credencial
     * se borra de nuevo.
     *
     * @param password la contraseña con que pasó la prueba. Solo viaja a la credencial sellada.
     * @param nowMillis el reloj de pared, en milisegundos UTC, para `connected_at`.
     * @param record deja el destino en el estado del respaldo, en una transacción. Devuelve
     *   `false` si no quedó escrito.
     */
    suspend fun save(
        checked: CheckedWebDav,
        password: String,
        nowMillis: Long,
        record: suspend (DestinationLink) -> Boolean,
    ): ConnectResult {
        val link = checked.linkAt(nowMillis)
        val stored = checked.credentialWith(password)
        when (credentials.save(stored)) {
            CredentialSave.Saved -> Unit
            is CredentialSave.Rejected -> return ConnectResult.SaveFailed
            is CredentialSave.Failed -> return ConnectResult.SaveFailed
        }
        if (!record(link)) {
            // Sin el destino en la base, la credencial no sirve a nadie: no se queda en el teléfono.
            credentials.delete()
            return ConnectResult.SaveFailed
        }
        val linkSaved =
            try {
                links.write(link)
                true
            } catch (_: IOException) {
                false
            }
        return ConnectResult.Connected(link, checked.hasCopies, linkSaved)
    }

    /**
     * Revisa, prueba y, solo si todo pasó, guarda: [check] y luego [save].
     *
     * @param nowMillis el reloj de pared, en milisegundos UTC, para `connected_at`.
     * @param record deja el destino en el estado del respaldo, en una transacción. Devuelve
     *   `false` si no quedó escrito.
     */
    suspend fun connect(
        url: String,
        username: String,
        password: String,
        appName: String,
        previousAppNames: List<String>,
        nowMillis: Long,
        record: suspend (DestinationLink) -> Boolean,
    ): ConnectResult =
        when (val outcome = check(url, username, password, appName, previousAppNames)) {
            is CheckOutcome.Stopped -> outcome.result
            is CheckOutcome.Passed -> save(outcome.checked, password, nowMillis, record)
        }
}

/**
 * Un servicio que pasó la prueba de conexión, todavía sin guardar. No lleva la contraseña: quien
 * guarda la vuelve a dar.
 *
 * @property folder la carpeta de copias que eligió la prueba.
 * @property hasCopies si esa carpeta ya tenía copias de esta app.
 */
internal data class CheckedWebDav(
    val endpoint: WebDavEndpoint,
    val username: String,
    val folder: WebDavPath,
    val hasCopies: Boolean,
) {
    /** El identificador del destino, de la dirección y el usuario. */
    val destinationId: String get() = DestinationIdentity.destinationId(endpoint, username)

    /** Lo que `link.json` y la base guardan de este destino, conectado a las [nowMillis]. */
    fun linkAt(nowMillis: Long): DestinationLink = DestinationLink(
        kind = BackupDestinationKind.WEBDAV,
        account = username,
        destinationId = destinationId,
        folderUrl = endpoint.urlOf(folder).toString(),
        connectedAtMillis = nowMillis,
    )

    /** La credencial que se sella para este destino, con [password]. */
    fun credentialWith(password: String): StoredWebDavCredential =
        StoredWebDavCredential(endpoint.baseUrl.toString(), username, password, destinationId)
}

/** Cómo terminó la prueba antes de guardar. */
internal sealed interface CheckOutcome {
    data class Passed(val checked: CheckedWebDav) : CheckOutcome

    /** No pasó, o ni siquiera empezó. [result] nunca es [ConnectResult.Connected]. */
    data class Stopped(val result: ConnectResult) : CheckOutcome
}

/** El resultado de conectar. Ninguno lleva la contraseña ni texto del servicio. */
internal sealed interface ConnectResult {
    /**
     * Conectado. [hasCopies] dice si la carpeta ya tenía copias de esta app, para ofrecer restaurar
     * o empezar de cero. Si [linkSaved] es falso, `link.json` quedó atrás, y el arranque lo completa
     * desde la base.
     */
    data class Connected(
        val link: DestinationLink,
        val hasCopies: Boolean,
        val linkSaved: Boolean,
    ) : ConnectResult

    /** La dirección no se acepta, y no se abrió ninguna conexión. */
    data class AddressRejected(val reason: EndpointRejection) : ConnectResult

    /** Falta el usuario o la contraseña. No se abrió ninguna conexión. */
    data object MissingField : ConnectResult

    /** El nombre de la app no sirve como carpeta: es un error de la app, no de la persona. */
    data object InvalidAppName : ConnectResult

    /** La prueba de conexión falló en [step]. No se guardó nada. */
    data class CheckFailed(val step: ConnectionStep, val failure: ConnectionFailure) : ConnectResult

    /** La prueba pasó, pero no se pudo guardar. No quedó nada guardado. */
    data object SaveFailed : ConnectResult
}

/**
 * Abre el destino WebDAV de producción para el trabajo de respaldo: lee la credencial sellada del
 * destino que recuerda la base y arma el destino sobre la carpeta guardada.
 *
 * La credencial solo viaja a la dirección con que se selló: una carpeta guardada fuera de esa
 * dirección no se usa.
 */
internal class WebDavDestinationOpener(
    private val http: OkHttpClient,
    private val credentials: WebDavCredentialStore,
    private val usage: UploadUsageMeter = UploadUsageMeter.None,
) {
    /**
     * @param destinationId el `destination_id` del estado del respaldo.
     * @param folderUrl el `destination_folder` del estado del respaldo.
     */
    suspend fun open(destinationId: String, folderUrl: String): DestinationOpen {
        val first = credentials.read(destinationId)
        // Un cambio de destino que la base confirmó y un corte dejó sin renombrar se termina aquí,
        // bajo el cerrojo del respaldo: la credencial nueva es la de este destino.
        val read =
            if (first is CredentialRead.PermissionLost && credentials.promoteNext(destinationId)) {
                credentials.read(destinationId)
            } else {
                first
            }
        val credential =
            when (read) {
                is CredentialRead.Usable -> read.credential
                is CredentialRead.PermissionLost -> return DestinationOpen.PermissionLost
                is CredentialRead.RetryLater -> return DestinationOpen.RetryLater
            }
        val endpoint =
            (WebDavEndpoint.parse(credential.url) as? EndpointParse.Accepted)?.endpoint
                ?: return DestinationOpen.PermissionLost
        val folder = folderUrl.toHttpUrlOrNull()?.let { relativeFolder(endpoint, it) }
            ?: return DestinationOpen.PermissionLost
        val client =
            WebDavClient(endpoint, WebDavCredential(credential.username, credential.password), http)
        return DestinationOpen.Opened(WebDavDestination(client, folder, usage))
    }

    /** La carpeta [url] relativa a la dirección base, o `null` si no está debajo de ella. */
    private fun relativeFolder(endpoint: WebDavEndpoint, url: HttpUrl): WebDavPath? {
        val base = endpoint.baseUrl
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return null
        if (url.query != null || url.fragment != null || !url.encodedPath.endsWith("/")) return null
        val baseSegments = base.pathSegments.dropLast(1)
        val segments = url.pathSegments.dropLast(1)
        if (segments.size <= baseSegments.size ||
            segments.subList(0, baseSegments.size) != baseSegments
        ) {
            return null
        }
        val relative = segments.drop(baseSegments.size)
        if (!relative.all(WebDavPath::isSafeSegment)) return null
        return WebDavPath(relative)
    }
}

/** El destino de producción, o por qué no abre. */
internal sealed interface DestinationOpen {
    data class Opened(val destination: BackupDestination) : DestinationOpen

    /** La credencial no abre, es de otro destino, o la carpeta no corresponde: acceso perdido. */
    data object PermissionLost : DestinationOpen

    /** Un fallo pasajero de Keystore o del disco: el trabajo se reintenta sin cambiar el estado. */
    data object RetryLater : DestinationOpen
}
