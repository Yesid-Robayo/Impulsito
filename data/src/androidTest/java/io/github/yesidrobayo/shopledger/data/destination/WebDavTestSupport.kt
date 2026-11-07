package io.github.yesidrobayo.shopledger.data.destination

import java.io.ByteArrayOutputStream
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Usuario y contraseña inventados. La contraseña lleva letras no latinas a propósito. */
internal const val TEST_USER = "usuario-de-prueba"
internal const val TEST_PASSWORD = "clave-ñandú-日本"

/**
 * El cliente de la app, con una sola diferencia: habla en claro con el servidor falso de la propia
 * máquina. Redirecciones, reintentos, protocolo, caché, cookies y tiempos son los de
 * [HttpClientFactory.newClient].
 */
internal fun testHttp(): OkHttpClient = HttpClientFactory
    .newClient()
    .newBuilder()
    .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
    .build()

internal fun testEndpoint(server: FakeHttpServer, host: String = "127.0.0.1") =
    WebDavEndpoint(server.url(host).toHttpUrl())

internal fun testClient(
    server: FakeHttpServer,
    http: OkHttpClient = testHttp(),
    endpoint: WebDavEndpoint = testEndpoint(server),
): WebDavClient = WebDavClient(
    endpoint = endpoint,
    credential = WebDavCredential(TEST_USER, TEST_PASSWORD),
    http = http,
)

/** La carpeta de las copias en las pruebas: `/dav/app/`. */
internal val APP_FOLDER = WebDavPath(listOf("app"))

/** Un `GET` que guarda en memoria lo que baja. */
internal suspend fun WebDavClient.getBytes(
    path: WebDavPath,
    maxBytes: Long,
): Pair<GetResult, ByteArray> {
    val sink = ByteArrayOutputStream()
    val result = get(path, sink, maxBytes)
    return result to sink.toByteArray()
}

internal object Dav {
    fun multistatus(vararg responses: String): String =
        """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""" +
            responses.joinToString("") + "</d:multistatus>"

    fun folder(href: String, quota: String? = null, status: String = "HTTP/1.1 200 OK"): String {
        val quotaProp =
            quota?.let { "<d:quota-available-bytes>$it</d:quota-available-bytes>" } ?: ""
        return "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
            "<d:resourcetype><d:collection/></d:resourcetype>$quotaProp</d:prop>" +
            "<d:status>$status</d:status></d:propstat></d:response>"
    }

    fun file(href: String, length: String, status: String = "HTTP/1.1 200 OK"): String =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/>" +
            "<d:getcontentlength>$length</d:getcontentlength>" +
            "<d:getlastmodified>Fri, 06 Nov 2026 12:00:00 GMT</d:getlastmodified>" +
            "</d:prop><d:status>$status</d:status></d:propstat></d:response>"
}
