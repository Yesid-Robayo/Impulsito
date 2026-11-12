package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.CurrentDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.TestedWebDav
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddress
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddressProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavLogin
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSaveResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSuggestion
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import io.github.yesidrobayo.shopledger.domain.port.WebDavConnectionPort
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Los servicios WebDAV sugeridos, en su orden. Solo entra uno que pasó la prueba de servicios en un
 * teléfono, y ninguno debe parecer recomendado para un país. Son nombres propios: no se traducen.
 */
internal val WEBDAV_SUGGESTIONS: List<WebDavSuggestion> =
    listOf(
        WebDavSuggestion(
            key = "koofr",
            name = "Koofr",
            url = "https://app.koofr.net/dav/Koofr",
            usesAccountPassword = false,
            declaresLimits = false,
        ),
    )

/**
 * Conectar un servicio WebDAV desde la pantalla, sobre el mismo conector del respaldo.
 *
 * - La prueba no toca nada del teléfono: ni la credencial, ni la base, ni `link.json`.
 * - Guardar vuelve a armar la conexión probada con lo mismo que se probó: si la dirección o el
 *   usuario cambiaron entre medio, no guarda.
 * - Guardar corre bajo el cerrojo del respaldo, sin cancelación: una pantalla que se cierra a mitad
 *   no deja la credencial sellada sin destino. Después programa la primera copia.
 * - Los nombres anteriores de la app no existen todavía: la carpeta es solo la del nombre actual.
 *
 * @param state la fila del respaldo, o nula si la base no abre.
 * @param slotsLabel el nombre de la nube del teléfono conectada, si se sabe.
 * @param record deja el destino en el estado del respaldo; devuelve si quedó escrito.
 * @param onConnected programa la copia tras conectar.
 * @param nowMillis el reloj de pared, en milisegundos UTC.
 * @param change cambia a este servicio si hay otro destino guardado, conectado o no, con toda su
 *   limpieza bajo el cerrojo. Sin otro destino responde que no era un cambio, y se conecta como
 *   siempre. Nulo donde no se ofrece.
 */
internal class WebDavConnectionService(
    private val connector: WebDavConnector,
    private val state: suspend () -> BackupStateEntity?,
    private val slotsLabel: suspend () -> String?,
    private val record: suspend (DestinationLink) -> Boolean,
    private val backupLock: Mutex,
    private val onConnected: suspend () -> Unit,
    private val network: () -> Boolean,
    private val nowMillis: () -> Long,
    override val suggestions: List<WebDavSuggestion> = WEBDAV_SUGGESTIONS,
    private val change:
    (suspend (StoredWebDavCredential, DestinationLink) -> ChangeOutcome)? = null,
) : WebDavConnectionPort {
    override fun inspect(url: String): WebDavAddress =
        when (val parsed = WebDavEndpoint.parse(url)) {
            is EndpointParse.Accepted -> WebDavAddress.Accepted(parsed.endpoint.asciiHost)
            is EndpointParse.Rejected -> WebDavAddress.Rejected(problemOf(parsed.reason))
        }

    override fun hasNetwork(): Boolean = network()

    override suspend fun current(): CurrentDestination? {
        val row = state()?.takeIf { it.linkState != BackupLinkState.NOT_CONNECTED.name }
            ?: return null
        val kind = BackupDestinationKind.entries.firstOrNull { it.name == row.destinationKind }
            ?: return null
        val destinationId = row.destinationId ?: return null
        return when (kind) {
            BackupDestinationKind.WEBDAV -> {
                val folder = row.destinationFolder?.toHttpUrlOrNull()
                // La dirección escrita es la carpeta de copias sin su último segmento.
                val base = folder?.newBuilder()?.removePathSegment(folder.pathSize - 2)?.build()
                CurrentDestination(
                    kind = kind,
                    destinationId = destinationId,
                    baseUrl = base?.toString(),
                    account = row.destinationAccount,
                    label = folder?.host,
                )
            }

            BackupDestinationKind.DOCUMENT_SLOTS ->
                CurrentDestination(kind, destinationId, null, null, slotsLabel())
        }
    }

    override suspend fun test(
        login: WebDavLogin,
        appName: String,
        onStep: (WebDavTestStep) -> Unit,
    ): WebDavTestResult {
        if (login.url.isBlank()) return WebDavTestResult.MissingField
        val outcome =
            connector.check(
                url = login.url,
                username = login.username,
                password = login.password,
                appName = appName,
                previousAppNames = emptyList(),
                onStep = { onStep(visibleStep(it)) },
            )
        return when (outcome) {
            is CheckOutcome.Passed -> {
                val checked = outcome.checked
                WebDavTestResult.Passed(
                    TestedWebDav(
                        destinationId = checked.destinationId,
                        folderName = checked.folder.folder.single(),
                        hasCopies = checked.hasCopies,
                    ),
                )
            }

            is CheckOutcome.Stopped -> stopped(outcome.result)
        }
    }

    override suspend fun save(login: WebDavLogin, tested: TestedWebDav): WebDavSaveResult {
        val endpoint =
            (WebDavEndpoint.parse(login.url) as? EndpointParse.Accepted)?.endpoint
                ?: return WebDavSaveResult.SaveFailed
        val checked =
            CheckedWebDav(
                endpoint = endpoint,
                username = login.username,
                folder = WebDavPath(listOf(tested.folderName)),
                hasCopies = tested.hasCopies,
            )
        // Lo que se guarda es exactamente lo que se probó.
        if (checked.destinationId != tested.destinationId) return WebDavSaveResult.SaveFailed
        val result =
            withContext(NonCancellable) {
                val now = nowMillis()
                val link = checked.linkAt(now)
                when (change?.invoke(checked.credentialWith(login.password), link)) {
                    ChangeOutcome.CHANGED -> ConnectResult.Connected(link, checked.hasCopies, true)

                    ChangeOutcome.FAILED -> ConnectResult.SaveFailed

                    // Sin otro destino guardado es conectar, como siempre.
                    ChangeOutcome.NOT_A_CHANGE, null ->
                        backupLock.withLock { connector.save(checked, login.password, now, record) }
                }
            }
        return when (result) {
            is ConnectResult.Connected -> {
                onConnected()
                WebDavSaveResult.Connected(result.hasCopies)
            }

            is ConnectResult.AddressRejected,
            ConnectResult.MissingField,
            ConnectResult.InvalidAppName,
            is ConnectResult.CheckFailed,
            ConnectResult.SaveFailed,
            -> WebDavSaveResult.SaveFailed
        }
    }

    private fun stopped(result: ConnectResult): WebDavTestResult = when (result) {
        is ConnectResult.AddressRejected -> WebDavTestResult.AddressRejected(
            problemOf(result.reason),
        )

        ConnectResult.MissingField -> WebDavTestResult.MissingField

        // El nombre de la app no da carpeta: es un error de la app, y ese servicio no sirve así.
        ConnectResult.InvalidAppName ->
            WebDavTestResult.Failed(WebDavTestStep.LIST, WebDavTestFailure.UNSUPPORTED)

        is ConnectResult.CheckFailed ->
            WebDavTestResult.Failed(visibleStep(result.step), failureOf(result.failure))

        // La prueba no guarda: estos no llegan aquí.
        is ConnectResult.Connected, ConnectResult.SaveFailed ->
            WebDavTestResult.Failed(WebDavTestStep.LIST, WebDavTestFailure.UNSUPPORTED)
    }

    companion object {
        /** El paso que ve la persona: buscar la carpeta cuenta como leerla. */
        internal fun visibleStep(step: ConnectionStep): WebDavTestStep = when (step) {
            ConnectionStep.ADDRESS, ConnectionStep.FIND_FOLDER -> WebDavTestStep.LIST
            ConnectionStep.CREATE_FOLDER -> WebDavTestStep.CREATE_FOLDER
            ConnectionStep.WRITE -> WebDavTestStep.UPLOAD
            ConnectionStep.MOVE -> WebDavTestStep.MOVE
            ConnectionStep.NO_OVERWRITE -> WebDavTestStep.NO_OVERWRITE
            ConnectionStep.READ_BACK -> WebDavTestStep.DOWNLOAD
            ConnectionStep.DELETE -> WebDavTestStep.DELETE
        }

        internal fun failureOf(failure: ConnectionFailure): WebDavTestFailure = when (failure) {
            ConnectionFailure.CredentialRejected -> WebDavTestFailure.CREDENTIAL_REJECTED

            ConnectionFailure.Forbidden -> WebDavTestFailure.FORBIDDEN

            ConnectionFailure.RateLimited -> WebDavTestFailure.RATE_LIMITED

            ConnectionFailure.ServerError -> WebDavTestFailure.UNREACHABLE

            is ConnectionFailure.Unreachable ->
                when (failure.kind) {
                    TransientKind.TLS -> WebDavTestFailure.INSECURE

                    TransientKind.NETWORK,
                    TransientKind.SERVER_ERROR,
                    TransientKind.RATE_LIMIT,
                    -> WebDavTestFailure.UNREACHABLE
                }

            ConnectionFailure.QuotaExceeded -> WebDavTestFailure.NO_SPACE

            is ConnectionFailure.Unsupported ->
                when (failure.reason) {
                    UnsupportedReason.BASE_URL_INVALID -> WebDavTestFailure.BASE_URL

                    UnsupportedReason.REDIRECT -> WebDavTestFailure.REDIRECT

                    UnsupportedReason.NAME_REJECTED,
                    UnsupportedReason.FORBIDDEN,
                    UnsupportedReason.METHOD_NOT_ALLOWED,
                    UnsupportedReason.TOO_LARGE,
                    UnsupportedReason.NOT_IMPLEMENTED,
                    UnsupportedReason.INVALID_RESPONSE,
                    UnsupportedReason.UNEXPECTED_STATUS,
                    UnsupportedReason.PROVIDER_REJECTED,
                    -> WebDavTestFailure.UNSUPPORTED
                }

            ConnectionFailure.OverwriteNotRespected,
            ConnectionFailure.ReadBackMismatch,
            ConnectionFailure.Incomplete,
            -> WebDavTestFailure.UNSUPPORTED
        }

        internal fun problemOf(reason: EndpointRejection): WebDavAddressProblem = when (reason) {
            EndpointRejection.NOT_HTTPS -> WebDavAddressProblem.NOT_HTTPS

            EndpointRejection.CREDENTIALS_IN_URL,
            EndpointRejection.QUERY_OR_FRAGMENT,
            EndpointRejection.MALFORMED,
            -> WebDavAddressProblem.INVALID
        }
    }
}
