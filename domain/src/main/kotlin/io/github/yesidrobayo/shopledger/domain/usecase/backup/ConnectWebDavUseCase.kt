package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.CurrentDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.TestedWebDav
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddress
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavLogin
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSaveResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSuggestion
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import io.github.yesidrobayo.shopledger.domain.port.WebDavConnectionPort

/**
 * Conectar un servicio WebDAV: probar antes de guardar nada, preguntar si se cambia el destino
 * actual, y guardar solo lo que pasó la prueba.
 *
 * Cambiar de destino es conectar uno distinto del actual: el mismo servicio con el mismo usuario
 * es reconectar, y no pregunta. El actual sigue funcionando hasta que la persona confirma.
 */
class ConnectWebDavUseCase(private val port: WebDavConnectionPort) {
    /** Los servicios sugeridos, en su orden. */
    val suggestions: List<WebDavSuggestion> get() = port.suggestions

    /** Revisa una dirección sin abrir ninguna conexión. */
    fun inspect(url: String): WebDavAddress = port.inspect(url)

    /** Si hay ahora una red comprobada: sin ella, no se intenta. */
    fun hasNetwork(): Boolean = port.hasNetwork()

    /** El destino conectado ahora, o nulo. */
    suspend fun current(): CurrentDestination? = port.current()

    /** La prueba de conexión, que no guarda nada. */
    suspend fun test(
        login: WebDavLogin,
        appName: String,
        onStep: (WebDavTestStep) -> Unit,
    ): WebDavTestResult = port.test(login, appName, onStep)

    /** Guarda lo probado. */
    suspend fun save(login: WebDavLogin, tested: TestedWebDav): WebDavSaveResult =
        port.save(login, tested)

    companion object {
        /** Si guardar [tested] cambia el destino [current]: hay uno y es otro. */
        fun changesDestination(current: CurrentDestination?, tested: TestedWebDav): Boolean =
            current != null && current.destinationId != tested.destinationId
    }
}
