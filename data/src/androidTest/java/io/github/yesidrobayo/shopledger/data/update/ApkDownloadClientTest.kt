package io.github.yesidrobayo.shopledger.data.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.FakeResponse
import io.github.yesidrobayo.shopledger.data.destination.HttpClientFactory
import io.github.yesidrobayo.shopledger.data.destination.testHttp
import io.github.yesidrobayo.shopledger.domain.model.update.ApkDownloadResult
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bajar el APK de la versión anunciada contra un servidor falso de la propia máquina: llega entero
 * con su SHA-256 y su progreso, sigue a lo sumo tres redirecciones y solo a hosts de la lista,
 * nunca en claro con la política real, corta lo que pasa el tope, y la petición no lleva
 * `Authorization` ni cookies. Lo que falla no deja archivo. El contenido es inventado; las huellas
 * esperadas son los vectores publicados de SHA-256.
 */
@RunWith(AndroidJUnit4::class)
class ApkDownloadClientTest {
    private val servers = mutableListOf<FakeHttpServer>()
    private val folder =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "apk-test")
    private val target = File(folder, "update.apk")

    /** Para el servidor falso: solo la propia máquina, y en claro, porque no tiene TLS. */
    private val local = ApkDownloadPolicy(setOf("127.0.0.1"), httpsOnly = false)

    @Before
    fun cleanFolder() {
        folder.deleteRecursively()
    }

    @After
    fun closeServers() {
        servers.forEach { it.close() }
        folder.deleteRecursively()
    }

    private fun server(
        bindAll: Boolean = false,
        handler: (String) -> FakeResponse,
    ): FakeHttpServer = FakeHttpServer(bindAll) { request -> handler(request.path) }
        .also { servers += it }

    private fun urlOf(server: FakeHttpServer, path: String = APK_PATH): HttpUrl =
        "http://127.0.0.1:${server.port}$path".toHttpUrl()

    private fun download(
        url: HttpUrl,
        policy: ApkDownloadPolicy = local,
        http: OkHttpClient = testHttp(),
        maxBytes: Long = MAX_BYTES,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): ApkDownloadResult = runBlocking {
        ApkDownloadClient(http, policy).download(url, target, maxBytes, onProgress)
    }

    @Test
    fun aFileArrivesWholeWithItsHashAndProgressAndTheRequestCarriesNothingOfThePerson() {
        val body = ByteArray(MILLION) { 'a'.code.toByte() }
        val server = server { FakeResponse.Reply(200, body = body) }
        val progress = mutableListOf<Pair<Long, Long?>>()

        val result = download(urlOf(server)) { done, total -> progress += done to total }

        assertEquals(ApkDownloadResult.Downloaded(MILLION_A_SHA256, MILLION.toLong()), result)
        assertArrayEquals(body, target.readBytes())
        assertEquals(MILLION.toLong() to MILLION.toLong(), progress.last())
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first })
        assertEquals(listOf(target.name), folder.list().orEmpty().toList())
        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertEquals(ApkDownloadClient.USER_AGENT, request.header("User-Agent"))
        assertEquals("identity", request.header("Accept-Encoding"))
        assertEquals(0, request.body.size)
    }

    @Test
    fun aRedirectToAnAllowedHostIsFollowedWithoutCarryingAnything() {
        val files = server { FakeResponse.Reply(200, body = ABC) }
        val asset = urlOf(files, "/asset?sig=1").toString()
        val origin = server { FakeResponse.status(302, mapOf("Location" to asset)) }

        val result = download(urlOf(origin))

        assertEquals(ApkDownloadResult.Downloaded(ABC_SHA256, 3), result)
        assertEquals(1, origin.requests.size)
        val followed = files.requests.single()
        assertEquals("/asset?sig=1", followed.path)
        assertNull(followed.header("Authorization"))
        assertNull(followed.header("Cookie"))
        assertEquals(ApkDownloadClient.USER_AGENT, followed.header("User-Agent"))
    }

    @Test
    fun aRedirectToAHostOutsideTheListIsNotFollowed() {
        val other = server(bindAll = true) { FakeResponse.Reply(200, body = ABC) }
        val origin =
            server {
                FakeResponse.status(
                    302,
                    mapOf("Location" to "http://localhost:${other.port}$APK_PATH"),
                )
            }

        assertEquals(ApkDownloadResult.Refused, download(urlOf(origin)))
        assertTrue(other.requests.isEmpty())
        assertFalse(target.exists())
    }

    @Test
    fun withTheReleasePolicyPlainHttpIsNeverAsked() {
        val server = server { FakeResponse.Reply(200, body = ABC) }
        val releaseLike = ApkDownloadPolicy(setOf("127.0.0.1"))

        assertEquals(ApkDownloadResult.Refused, download(urlOf(server), policy = releaseLike))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun theAppClientNeverTalksInTheClear() {
        val server = server { FakeResponse.Reply(200, body = ABC) }

        val result = download(urlOf(server), http = HttpClientFactory.newClient())

        assertFalse(result is ApkDownloadResult.Downloaded)
        assertTrue(server.requests.isEmpty())
        assertFalse(target.exists())
    }

    @Test
    fun threeRedirectsAreFollowedAndAFourthIsNot() {
        // `/hop/n` lleva a `/hop/n+1`, y `/hop/4` entrega el archivo.
        val server =
            server { path ->
                val hop = path.removePrefix("/hop/").toInt()
                if (hop == 4) {
                    FakeResponse.Reply(200, body = ABC)
                } else {
                    FakeResponse.status(302, mapOf("Location" to "/hop/${hop + 1}"))
                }
            }

        assertEquals(
            ApkDownloadResult.Downloaded(ABC_SHA256, 3),
            download(urlOf(server, "/hop/1")),
        )
        assertEquals(4, server.requests.size)

        assertEquals(ApkDownloadResult.Refused, download(urlOf(server, "/hop/0")))
        assertEquals(
            listOf("/hop/0", "/hop/1", "/hop/2", "/hop/3"),
            server.requests.drop(4).map { it.path },
        )
        assertFalse(target.exists())
    }

    @Test
    fun aFileOverTheCapIsNotKept() {
        val server = server { FakeResponse.Reply(200, body = ByteArray(5_000)) }

        assertEquals(ApkDownloadResult.TooLarge, download(urlOf(server), maxBytes = 1_000))
        assertFalse(target.exists())
        assertTrue(folder.list().orEmpty().isEmpty())
    }

    @Test
    fun aFileOfExactlyTheCapArrives() {
        val server = server { FakeResponse.Reply(200, body = ABC) }

        assertEquals(
            ApkDownloadResult.Downloaded(ABC_SHA256, 3),
            download(urlOf(server), maxBytes = 3),
        )
    }

    @Test
    fun anAnswerThatIsNotTheFileLeavesNothing() {
        val missing = server { FakeResponse.status(404) }
        assertEquals(ApkDownloadResult.Failed, download(urlOf(missing)))
        assertFalse(target.exists())

        val compressed =
            server { FakeResponse.Reply(200, mapOf("Content-Encoding" to "gzip"), ABC) }
        assertEquals(ApkDownloadResult.Failed, download(urlOf(compressed)))
        assertFalse(target.exists())

        val nowhere = server { FakeResponse.status(302) }
        assertEquals(ApkDownloadResult.Failed, download(urlOf(nowhere)))
    }

    @Test
    fun withoutNetworkItSaysSo() {
        val gone = server { FakeResponse.status(200) }
        val url = urlOf(gone)
        gone.close()

        assertEquals(ApkDownloadResult.Offline, download(url))
        assertFalse(target.exists())
    }

    @Test
    fun aPreviousFileIsReplaced() {
        folder.mkdirs()
        target.writeBytes(ByteArray(10) { 7 })
        File(folder, "left-behind.part").writeBytes(ByteArray(10))
        val server = server { FakeResponse.Reply(200, body = ABC) }

        assertEquals(ApkDownloadResult.Downloaded(ABC_SHA256, 3), download(urlOf(server)))
        assertArrayEquals(ABC, target.readBytes())
        assertEquals(listOf(target.name), folder.list().orEmpty().toList())
    }

    @Test
    fun cancellingStopsTheDownloadAndLeavesNothing() {
        val server = server { FakeResponse.Reply(200, body = ByteArray(MILLION)) }
        var result: ApkDownloadResult? = null

        runBlocking {
            lateinit var job: Job
            job =
                launch(start = CoroutineStart.LAZY) {
                    result =
                        ApkDownloadClient(testHttp(), local).download(
                            urlOf(server),
                            target,
                            MAX_BYTES,
                        ) { done, _ -> if (done > 0) job.cancel() }
                }
            job.start()
            job.join()
        }

        assertNull(result)
        assertFalse(target.exists())
        assertTrue(folder.list().orEmpty().isEmpty())
    }

    private companion object {
        const val APK_PATH = "/download/v1.0.2/app-1.0.2.apk"
        const val MAX_BYTES = 8L * 1024 * 1024
        const val MILLION = 1_000_000
        val ABC = "abc".toByteArray(Charsets.US_ASCII)

        // Los vectores de prueba publicados de SHA-256: `abc` y un millón de letras `a`.
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        const val MILLION_A_SHA256 =
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
    }
}
