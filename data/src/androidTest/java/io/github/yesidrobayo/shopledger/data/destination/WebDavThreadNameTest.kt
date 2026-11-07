package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ningún hilo de la app lleva en su nombre el host del servicio WebDAV, porque el sistema puede
 * escribir ese nombre en su registro. El servicio falso responde con un nombre propio que solo
 * resuelve un `Dns` de la prueba, y mientras atiende cada petición se anotan los nombres de todos
 * los hilos del proceso.
 */
class WebDavThreadNameTest {
    private val servers = ArrayList<FakeHttpServer>()
    private val files = ArrayList<File>()

    @After
    fun cleanUp() {
        servers.forEach { it.close() }
        files.forEach { it.delete() }
    }

    /** Todos los nombres de hilo vistos mientras el servidor atendía. */
    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())

    private fun snapshot() {
        Thread.getAllStackTraces().keys.forEach { seen += it.name }
        seen += Thread.currentThread().name
    }

    /** Resuelve el host falso a la propia máquina, y anota el hilo que conecta. */
    private val dns =
        Dns { hostname ->
            snapshot()
            if (hostname ==
                HOST
            ) {
                listOf(InetAddress.getByName("127.0.0.1"))
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        }

    private fun server(): Pair<InMemoryWebDav, FakeHttpServer> {
        val dav = InMemoryWebDav()
        val server =
            FakeHttpServer(keepAlive = true) {
                snapshot()
                // Una pausa en cada petición deja tiempo para ver los hilos de la llamada en curso.
                Thread.sleep(50)
                dav.handle(it)
            }
        servers += server
        return dav to server
    }

    @Test
    fun noThreadCarriesTheHostDuringAWholeBackupCycle() = runBlocking<Unit> {
        val (_, server) = server()
        val http = testHttp().newBuilder().dns(dns).build()
        val client = testClient(server, http, WebDavEndpoint(server.url(HOST).toHttpUrl()))
        val destination = WebDavDestination(client, APP_FOLDER, sleep = {})
        val name =
            BackupFileName(
                1,
                1,
                requireNotNull(CopyInstant.parse("20261107T120000Z")),
                "0a1b2c3d",
                BackupFileFormat.GZIP,
            )
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("thread-name", ".db.gz", dir).also { files += it }
        val bytes = ByteArray(1024 * 1024) { (it % 249).toByte() }
        file.writeBytes(bytes)
        val copy = OutgoingCopy(
            file,
            name,
            file.length(),
            newSha256().digest(bytes).toLowerHex(),
            META,
            SlotCandidate(
                NewCopyMarks(valid = true, fatal = false),
                records = 10,
                catalogCount = 3,
            ),
        )
        val control = TransferControl.Unbounded

        destination.listCopies(control)
        val stored = destination.upload(UploadPlan(copy, CopyRef(name.fileName)), control)
        assertTrue(stored is DestinationResult.Success)
        val verified =
            destination.verify(
                (stored as DestinationResult.Success).value,
                VerifyMode.SAMPLE,
                file,
                control,
            )
        assertTrue(verified is DestinationResult.Success)
        destination.verify(stored.value, VerifyMode.FULL, null, control)
        destination.delete(CopyRef(name.fileName), control)

        assertTrue("no se vio ningún hilo", seen.isNotEmpty())
        assertEquals(emptyList<String>(), seen.filter { it.contains(HOST, ignoreCase = true) })
    }

    /**
     * Control: la misma petición con `enqueue()`, como la haría OkHttp por defecto, sí deja el host
     * en el nombre de un hilo. Si esto dejara de pasar, la prueba de arriba no probaría nada.
     */
    @Test
    fun anAsynchronousCallWouldCarryTheHost() {
        val (_, server) = server()
        val http = OkHttpClient.Builder().dns(dns).build()
        val done = CountDownLatch(1)
        http.newCall(Request.Builder().url(server.url(HOST, "/dav/")).build()).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = done.countDown()

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    done.countDown()
                }
            },
        )
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(seen.any { it.contains(HOST, ignoreCase = true) })
    }

    private companion object {
        /** Un nombre que no existe fuera de la prueba, y que no se confunde con nada del sistema. */
        const val HOST = "webdav-host-under-test.example"

        val META = """{"sl_kind":"backup"}""".toByteArray()
    }
}
