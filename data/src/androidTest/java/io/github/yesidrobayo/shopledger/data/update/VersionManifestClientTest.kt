package io.github.yesidrobayo.shopledger.data.update

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.FakeResponse
import io.github.yesidrobayo.shopledger.data.destination.HttpClientFactory
import io.github.yesidrobayo.shopledger.data.destination.testHttp
import io.github.yesidrobayo.shopledger.domain.model.update.ManifestRead
import io.github.yesidrobayo.shopledger.domain.model.update.VersionManifest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El aviso de versión contra un servidor falso de la propia máquina: la consulta no lleva
 * `Authorization`, cookies ni parámetros, y su `User-Agent` no tiene el modelo del teléfono. Un
 * aviso alterado, uno firmado con otra clave, uno de 1 MiB, una redirección, una respuesta que no
 * es `200`, uno que no se entiende y la falta de red dan su resultado, sin bloquear nada. El
 * cliente de la app no habla en claro: solo `https`. Las claves se crean en memoria para la
 * prueba.
 */
@RunWith(AndroidJUnit4::class)
class VersionManifestClientTest {
    private val keys: KeyPair = newKeys()
    private val servers = mutableListOf<FakeHttpServer>()
    private val hash = "ab".repeat(32)

    private val manifest =
        (
            """{"version_code":2,"version_name":"1.0.1","apk_sha256":"$hash",""" +
                """"min_safe_version_code":1,"published_at":1793000000000}"""
        ).toByteArray(Charsets.UTF_8)

    @After
    fun closeServers() {
        servers.forEach { it.close() }
    }

    private fun newKeys(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun sign(bytes: ByteArray, with: KeyPair = keys): ByteArray =
        Signature.getInstance(VersionManifestClient.SIGNATURE_ALGORITHM).run {
            initSign(with.private)
            update(bytes)
            sign()
        }

    private fun server(handler: (String) -> FakeResponse): FakeHttpServer =
        FakeHttpServer { request -> handler(request.path) }.also { servers += it }

    /** Un servidor que entrega [body] como aviso y [signature] como su firma. */
    private fun serving(body: ByteArray, signature: ByteArray = sign(body)): FakeHttpServer =
        server { path ->
            when (path) {
                MANIFEST_PATH -> FakeResponse.Reply(200, body = body)
                MANIFEST_PATH + VersionManifestSource.SIGNATURE_SUFFIX ->
                    FakeResponse.Reply(200, body = signature)
                else -> FakeResponse.status(404)
            }
        }

    private fun sourceOf(server: FakeHttpServer, key: KeyPair = keys): VersionManifestSource {
        val base = "http://127.0.0.1:${server.port}$MANIFEST_PATH"
        return VersionManifestSource(
            base.toHttpUrl(),
            (base + VersionManifestSource.SIGNATURE_SUFFIX).toHttpUrl(),
            key.public,
        )
    }

    private fun read(
        source: VersionManifestSource?,
        http: OkHttpClient = testHttp(),
    ): ManifestRead = runBlocking { VersionManifestClient(http).read(source) }

    @Test
    fun aSignedNoticeIsReadAndTheRequestCarriesNothingOfThePerson() {
        val server = serving(manifest)

        val read = read(sourceOf(server))

        assertEquals(
            ManifestRead.Verified(
                VersionManifest(2, "1.0.1", hash, 1, Instant.fromEpochMilliseconds(1793000000000)),
            ),
            read,
        )
        val requests = server.requests
        assertEquals(
            listOf(MANIFEST_PATH, MANIFEST_PATH + VersionManifestSource.SIGNATURE_SUFFIX),
            requests.map { it.path },
        )
        for (request in requests) {
            assertEquals("GET", request.method)
            assertFalse(request.path, '?' in request.path)
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            assertEquals(VersionManifestClient.USER_AGENT, request.header("User-Agent"))
            assertFalse(request.header("User-Agent").orEmpty().contains(Build.MODEL))
            assertEquals("identity", request.header("Accept-Encoding"))
            assertEquals(0, request.body.size)
        }
    }

    @Test
    fun anAlteredNoticeIsIgnored() {
        val altered = manifest.copyOf().also { it[it.size - 3] = '9'.code.toByte() }
        val server = serving(altered, signature = sign(manifest))

        assertEquals(ManifestRead.BadSignature, read(sourceOf(server)))
    }

    @Test
    fun aNoticeSignedWithAnotherKeyIsIgnored() {
        val server = serving(manifest, signature = sign(manifest, with = newKeys()))

        assertEquals(ManifestRead.BadSignature, read(sourceOf(server)))
    }

    @Test
    fun aSignatureThatIsNotEvenASignatureIsIgnored() {
        val server = serving(manifest, signature = ByteArray(16) { 7 })

        assertEquals(ManifestRead.BadSignature, read(sourceOf(server)))
    }

    @Test
    fun aNoticeOfOneMebibyteIsNotReadAndItsSignatureIsNotAsked() {
        val big = ByteArray(1024 * 1024) { ' '.code.toByte() }
        val server = serving(big)

        assertEquals(ManifestRead.Unreadable, read(sourceOf(server)))
        assertEquals(listOf(MANIFEST_PATH), server.requests.map { it.path })
    }

    @Test
    fun aRedirectToAnotherHostIsNeverFollowed() {
        val other = serving(manifest)
        val server =
            server {
                FakeResponse.status(
                    302,
                    mapOf("Location" to "http://localhost:${other.port}$MANIFEST_PATH"),
                )
            }

        assertEquals(ManifestRead.Unreadable, read(sourceOf(server)))
        assertTrue(other.requests.isEmpty())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aMissingSignatureLeavesTheNoticeUnread() {
        val server =
            server { path ->
                if (path == MANIFEST_PATH) {
                    FakeResponse.Reply(200, body = manifest)
                } else {
                    FakeResponse.status(404)
                }
            }

        assertEquals(ManifestRead.Unreadable, read(sourceOf(server)))
    }

    @Test
    fun aCompressedAnswerIsNotRead() {
        val server =
            server { FakeResponse.Reply(200, mapOf("Content-Encoding" to "gzip"), manifest) }

        assertEquals(ManifestRead.Unreadable, read(sourceOf(server)))
    }

    @Test
    fun aSignedNoticeThatMakesNoSenseIsNotRead() {
        val notJson = "no es un aviso".toByteArray(Charsets.UTF_8)
        assertEquals(ManifestRead.Unreadable, read(sourceOf(serving(notJson))))

        val missing = """{"version_code":2,"version_name":"1.0.1"}""".toByteArray(Charsets.UTF_8)
        assertEquals(ManifestRead.Unreadable, read(sourceOf(serving(missing))))

        val decimal =
            (
                """{"version_code":2.5,"version_name":"1.0.1","apk_sha256":"$hash",""" +
                    """"min_safe_version_code":1,"published_at":1793000000000}"""
            ).toByteArray(Charsets.UTF_8)
        assertEquals(ManifestRead.Unreadable, read(sourceOf(serving(decimal))))
    }

    @Test
    fun withoutNetworkItSaysSoAndBlocksNothing() {
        val gone = server { FakeResponse.status(200) }
        val source = sourceOf(gone)
        gone.close()

        assertEquals(ManifestRead.Offline, read(source))
    }

    @Test
    fun theAppClientNeverTalksInTheClear() {
        val server = serving(manifest)

        val read = read(sourceOf(server), http = HttpClientFactory.newClient())

        assertFalse(read is ManifestRead.Verified)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun withoutTheAddressOrTheKeyThereIsNothingToCheck() {
        assertEquals(ManifestRead.NotConfigured, read(null))
    }

    private companion object {
        const val MANIFEST_PATH = "/app/version.json"
    }
}
