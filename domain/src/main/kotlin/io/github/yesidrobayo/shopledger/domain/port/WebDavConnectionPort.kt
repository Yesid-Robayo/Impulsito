package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.CurrentDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.TestedWebDav
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddress
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavLogin
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSaveResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSuggestion
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestResult
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep

/**
 * Conectar un servicio WebDAV: probarlo antes de guardar nada, y guardarlo solo si pasó. La prueba
 * y el guardado van separados para poder preguntar, entre los dos, si se cambia el destino actual.
 */
interface WebDavConnectionPort {
    /** Los servicios sugeridos, en su orden. Puede estar vacía. */
    val suggestions: List<WebDavSuggestion>

    /** Revisa [url] sin abrir ninguna conexión. */
    fun inspect(url: String): WebDavAddress

    /** Si el teléfono tiene ahora una red que el sistema comprobó. */
    fun hasNetwork(): Boolean

    /** El destino conectado ahora, sin red, o nulo si no hay ninguno o no se pudo leer. */
    suspend fun current(): CurrentDestination?

    /**
     * Corre la prueba de conexión con [login], sobre la carpeta del nombre de la app. No guarda
     * nada en el teléfono, y no se reintenta sola.
     *
     * @param appName el nombre de la app, que nunca se traduce: da la carpeta de copias.
     * @param onStep se llama al empezar cada paso.
     */
    suspend fun test(
        login: WebDavLogin,
        appName: String,
        onStep: (WebDavTestStep) -> Unit,
    ): WebDavTestResult

    /**
     * Guarda el servicio [tested], que pasó la prueba con [login]: sella la credencial, deja el
     * destino conectado y programa la primera copia. Si algo falla, no queda nada guardado.
     */
    suspend fun save(login: WebDavLogin, tested: TestedWebDav): WebDavSaveResult
}
