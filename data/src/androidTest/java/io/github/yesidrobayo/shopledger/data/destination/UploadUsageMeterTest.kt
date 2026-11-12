package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que una subida a WebDAV anota para `Subido este mes`, contra el servicio falso en memoria: el
 * archivo entero una vez por envío, también un envío repetido y uno cortado a mitad, porque esos
 * datos ya se gastaron. Los metadatos, de pocos bytes, no cuentan.
 */
class UploadUsageMeterTest {
    private val servers = ArrayList<FakeHttpServer>()
    private val files = ArrayList<File>()
    private val sent = ArrayList<Long>()
    private val meter = UploadUsageMeter { sent += it }

    @After
    fun cleanUp() {
        servers.forEach { it.close() }
        files.forEach { it.delete() }
    }

    private val name =
        BackupFileName(
            42,
            1,
            requireNotNull(CopyInstant.parse("20261112T120000Z")),
            "0a1b2c3d",
            BackupFileFormat.GZIP,
        )
    private val content = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val meta = """{"sl_kind":"backup","sl_generation":"42"}""".toByteArray()

    private fun server(dav: InMemoryWebDav) =
        FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }

    private fun destination(server: FakeHttpServer) = WebDavDestination(
        client = testClient(server),
        folder = APP_FOLDER,
        usage = meter,
        sleep = {},
        jitterMillis = { 0 },
    )

    private fun plan(): UploadPlan {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file =
            File.createTempFile("upload-usage", ".db.gz", dir).also {
                it.writeBytes(content)
                files += it
            }
        val sha = newSha256().digest(content).toLowerHex()
        val copy = OutgoingCopy(file, name, file.length(), sha, meta, CANDIDATE)
        return UploadPlan(copy, CopyRef(name.fileName))
    }

    @Test
    fun aWholeUploadCountsTheFileOnce() = runBlocking<Unit> {
        val dav = InMemoryWebDav().also { it.collections += "/dav/app/" }
        val result = destination(server(dav)).upload(plan(), TransferControl.Unbounded)

        assertTrue(result is DestinationResult.Success)
        assertEquals(listOf(content.size.toLong()), sent)
    }

    @Test
    fun aRepeatedPutCountsBothSends() = runBlocking<Unit> {
        // Sin la carpeta, el primer PUT se envía entero, falla, se crea la carpeta y se repite.
        val dav = InMemoryWebDav()
        val result = destination(server(dav)).upload(plan(), TransferControl.Unbounded)

        assertTrue(result is DestinationResult.Success)
        assertEquals(listOf(2L * content.size), sent)
    }

    @Test
    fun aCutUploadCountsWhatWasSent() = runBlocking<Unit> {
        val dav = InMemoryWebDav().also { it.collections += "/dav/app/" }
        var checks = 0
        val midway =
            object : TransferControl by TransferControl.Unbounded {
                override fun isStopped(): Boolean = ++checks > 2
            }

        val result = destination(server(dav)).upload(plan(), midway)

        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
            (result as DestinationResult.Failure).failure,
        )
        assertEquals(1, sent.size)
        assertTrue("Algo se envió", sent.single() > 0)
        assertTrue("No se envió entero", sent.single() < content.size)
    }

    @Test
    fun nothingSentRecordsNothing() = runBlocking<Unit> {
        val dav = InMemoryWebDav().also { it.collections += "/dav/app/" }
        val stopped =
            object : TransferControl by TransferControl.Unbounded {
                override fun isStopped(): Boolean = true
            }

        destination(server(dav)).upload(plan(), stopped)

        assertEquals(emptyList<Long>(), sent)
    }

    private companion object {
        val CANDIDATE =
            SlotCandidate(NewCopyMarks(valid = true, fatal = false), records = 10, catalogCount = 3)
    }
}
