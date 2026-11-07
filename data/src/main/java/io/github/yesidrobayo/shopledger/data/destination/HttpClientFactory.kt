package io.github.yesidrobayo.shopledger.data.destination

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * Crea el único cliente HTTP de la app. Lo usan el destino WebDAV y, más adelante, la lectura del
 * manifiesto de versión: un solo camino de red, con una sola configuración.
 *
 * - **Sin redirecciones automáticas.** Un `3xx` se compara con la dirección pedida y casi nunca se
 *   sigue: así la contraseña no viaja a otro host.
 * - **Sin reintentos automáticos.** OkHttp repite solo una petición tras un corte, y repetir un
 *   `MOVE` o un `PUT` no es inocuo. Los únicos reintentos los decide quien sabe qué método repite.
 * - **Tiempos de espera fijados.** Los de OkHttp son de 10 s y sin tope por llamada: un servidor
 *   lento al cerrar un `PUT` grande obligaría a repetir la subida entera.
 * - **Sin caché, cookies, interceptores de registro ni `Authenticator`.** Un `401` no repite la
 *   credencial, y ningún encabezado ni cuerpo llega a un log.
 * - **Solo TLS moderno, sin texto en claro.** Confía en las CA del sistema y nunca en las que
 *   instaló la persona: lo fija la configuración de seguridad de red de la app, no este código.
 * - **Ningún hilo lleva el host en su nombre.** OkHttp nombra con la dirección los hilos de una
 *   llamada asíncrona, los de una conexión HTTP/2 y los de la conexión en paralelo a varias
 *   direcciones, y el sistema puede escribir ese nombre en su registro. Por eso las llamadas se
 *   hacen en hilos propios, solo con HTTP/1.1 y una dirección a la vez. Para subir copias de a una
 *   no se pierde nada: HTTP/2 sirve para muchas peticiones a la vez.
 */
internal object HttpClientFactory {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 60L

    fun newClient(): OkHttpClient = OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .fastFallback(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .build()
}
