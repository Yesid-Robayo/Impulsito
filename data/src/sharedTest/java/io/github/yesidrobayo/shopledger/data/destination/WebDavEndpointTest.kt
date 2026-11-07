package io.github.yesidrobayo.shopledger.data.destination

import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavEndpointTest {
    private fun rejected(input: String): EndpointRejection? =
        (WebDavEndpoint.parse(input) as? EndpointParse.Rejected)?.reason

    private fun accepted(input: String): WebDavEndpoint =
        (WebDavEndpoint.parse(input) as EndpointParse.Accepted).endpoint

    @Test
    fun onlyHttpsIsAccepted() {
        assertEquals(EndpointRejection.NOT_HTTPS, rejected("http://dav.example.com/dav/"))
        assertEquals(EndpointRejection.NOT_HTTPS, rejected("HTTP://dav.example.com/"))
        assertEquals(EndpointRejection.NOT_HTTPS, rejected("ftp://dav.example.com/"))
        assertEquals(EndpointRejection.MALFORMED, rejected("dav.example.com/dav/"))
        assertEquals(EndpointRejection.MALFORMED, rejected(""))
    }

    @Test
    fun credentialsQueryAndFragmentAreRejected() {
        assertEquals(EndpointRejection.CREDENTIALS_IN_URL, rejected("https://a:b@example.com/"))
        assertEquals(EndpointRejection.CREDENTIALS_IN_URL, rejected("https://a@example.com/"))
        assertEquals(EndpointRejection.CREDENTIALS_IN_URL, rejected("https://@example.com/"))
        assertEquals(EndpointRejection.QUERY_OR_FRAGMENT, rejected("https://dav.example.com/?a=1"))
        assertEquals(EndpointRejection.QUERY_OR_FRAGMENT, rejected("https://dav.example.com/#x"))
        assertEquals(EndpointRejection.MALFORMED, rejected("https://dav.example.com/a b/"))
    }

    @Test
    fun hostIsLowercaseAsciiAndPathEndsWithSlash() {
        val endpoint = accepted("https://DAV.Example.com:443/Dav/Copias")
        assertEquals("dav.example.com", endpoint.asciiHost)
        assertEquals("https://dav.example.com/Dav/Copias/", endpoint.baseUrl.toString())
        val idn = accepted("https://ñandú.example/dav/")
        assertTrue(idn.asciiHost.startsWith("xn--"))
        assertEquals(8443, accepted("https://dav.example.com:8443/").baseUrl.port)
    }

    @Test
    fun pathsAreBuiltFromTheStoredUrlWithEncodedSegments() {
        val endpoint = accepted("https://dav.example.com/dav/")
        val folder = WebDavPath(listOf("Mis copias"))
        assertEquals(
            "https://dav.example.com/dav/Mis%20copias/",
            endpoint.urlOf(folder).toString(),
        )
        assertEquals(
            "https://dav.example.com/dav/Mis%20copias/a%23b.sltmp",
            endpoint.urlOf(folder.file("a#b.sltmp")).toString(),
        )
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb")) {
            assertFalse(bad, WebDavPath.isSafeSegment(bad))
        }
    }

    @Test
    fun clientFollowsNoRedirectRetriesNothingAndKeepsNothing() {
        val client = HttpClientFactory.newClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertNull(client.cache)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(Authenticator.NONE, client.authenticator)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(listOf(ConnectionSpec.MODERN_TLS), client.connectionSpecs)
        assertEquals(TimeUnit.SECONDS.toMillis(15), client.connectTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(60), client.readTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(60), client.writeTimeoutMillis.toLong())
        // Sin HTTP/2 ni conexión en paralelo: los dos ponen el host en el nombre de un hilo.
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertFalse(client.fastFallback)
    }

    @Test
    fun credentialNeverShowsItself() {
        val credential = WebDavCredential("usuario-inventado", "clave-ñandú")
        assertFalse(credential.toString().contains("clave"))
        assertFalse(credential.toString().contains("usuario"))
        val endpoint = accepted("https://dav.example.com/secreto/")
        assertFalse(endpoint.toString().contains("example"))
    }
}
