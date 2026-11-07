package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El destino WebDAV contra un servicio falso en memoria: lo que hace con cada respuesta, según la
 * tabla de respuestas por método, sin red real. Las esperas entre reintentos se anotan en vez de
 * esperarse.
 */
class WebDavDestinationTest {
    private val servers = ArrayList<FakeHttpServer>()
    private val files = ArrayList<File>()
    private val waits = ArrayList<Long>()

    @After
    fun cleanUp() {
        servers.forEach { it.close() }
        files.forEach { it.delete() }
    }

    private val name =
        BackupFileName(
            42,
            1,
            requireNotNull(CopyInstant.parse("20261107T120000Z")),
            "0a1b2c3d",
            BackupFileFormat.GZIP,
        )
    private val copyPath = "/dav/app/${name.fileName}"
    private val metaPath = "/dav/app/${name.metaFileName}"
    private val content = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val meta = """{"sl_kind":"backup","sl_generation":"42"}""".toByteArray()

    private fun server(handler: (RecordedRequest) -> FakeResponse) =
        FakeHttpServer(keepAlive = true, handler = handler).also { servers += it }

    private fun dav(
        honorRange: Boolean = true,
        override: (RecordedRequest) -> FakeResponse? = { null },
    ): Pair<InMemoryWebDav, FakeHttpServer> {
        val dav = InMemoryWebDav(honorRange = honorRange, override = override)
        dav.collections += "/dav/app/"
        return dav to server(dav::handle)
    }

    private fun destination(server: FakeHttpServer) = WebDavDestination(
        client = testClient(server),
        folder = APP_FOLDER,
        sleep = { waits += it },
        jitterMillis = { 7 },
    )

    private fun localFile(bytes: ByteArray = content): File {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File.createTempFile("webdav-destination", ".db.gz", dir).also {
            it.writeBytes(bytes)
            files += it
        }
    }

    private fun outgoing(file: File = localFile()) =
        OutgoingCopy(file, name, file.length(), sha256(file.readBytes()), meta, CANDIDATE)

    private fun plan(file: File = localFile()) = UploadPlan(outgoing(file), CopyRef(name.fileName))

    private fun stored(bytes: ByteArray = content, metaBytes: ByteArray = meta) =
        StoredCopy(CopyRef(name.fileName), bytes.size.toLong(), sha256(bytes), metaBytes)

    private fun sha256(bytes: ByteArray) = newSha256().digest(bytes).toLowerHex()

    private fun <T> DestinationResult<T>.value(): T = when (this) {
        is DestinationResult.Success -> value
        is DestinationResult.Failure -> throw AssertionError("Falló: $failure")
    }

    private fun DestinationResult<*>.failure(): DestinationFailure = when (this) {
        is DestinationResult.Success -> throw AssertionError("Debía fallar: $value")
        is DestinationResult.Failure -> failure
    }

    private val control = TransferControl.Unbounded

    private companion object {
        val CANDIDATE =
            SlotCandidate(NewCopyMarks(valid = true, fatal = false), records = 10, catalogCount = 3)
    }

    private class TestControl(
        var stopped: Boolean = false,
        var remaining: Long? = null,
        var metered: Boolean = false,
    ) : TransferControl {
        override fun isStopped(): Boolean = stopped

        override fun remainingMillis(): Long? = remaining

        override fun meteredStop(): Boolean = false

        override fun isMetered(): Boolean = metered
    }

    // Subir, listar, verificar y borrar

    @Test
    fun uploadGoesThroughTemporaryNamesAndNeverOverwrites() = runBlocking<Unit> {
        val (dav, server) = dav()
        val destination = destination(server)
        val result = destination.upload(plan(), control).value()
        assertEquals(CopyRef(name.fileName), result.ref)
        assertArrayEquals(content, dav.files[copyPath])
        assertArrayEquals(meta, dav.files[metaPath])
        assertEquals(setOf(copyPath, metaPath), dav.files.keys)
        val calls = server.requests.map { "${it.method} ${it.path}" }
        assertEquals(
            listOf(
                "PUT $copyPath.sltmp",
                "MOVE $copyPath.sltmp",
                "PUT $metaPath.sltmp",
                "MOVE $metaPath.sltmp",
            ),
            calls,
        )
        server.requests.filter { it.method == "MOVE" }.forEach {
            assertEquals("F", it.header("Overwrite"))
        }
        val put = server.requests.first()
        assertEquals(content.size.toString(), put.header("Content-Length"))
        assertEquals("application/gzip", put.header("Content-Type"))
    }

    @Test
    fun aListedCopyComesWithItsMetaAndTheVerificationReadsIt() = runBlocking<Unit> {
        val (_, server) = dav()
        val destination = destination(server)
        val copy = destination.upload(plan(), control).value()
        val listing = destination.listCopies(control).value()
        val listed = listing.copies.single()
        assertEquals(name, listed.name)
        assertEquals(content.size.toLong(), listed.sizeBytes)
        assertArrayEquals(meta, (listed.meta as ListedMeta.Present).bytes)
        assertTrue(destination.verify(copy, VerifyMode.FULL, null, control).value().fullyRead)
        assertFalse(
            destination.verify(copy, VerifyMode.SAMPLE, localFile(), control).value().fullyRead,
        )
        destination.verify(copy, VerifyMode.SIZE_AND_META, null, control).value()
    }

    @Test
    fun theListSortsCopiesMetasTemporariesAndIgnoresTheRest() = runBlocking<Unit> {
        val (dav, server) = dav()
        val other = name.copy(generation = 41)
        val foreign = name.copy(generation = 40, devicePrefix = "ffffffff")
        dav.files[copyPath] = content
        dav.files[metaPath] = meta
        dav.files["/dav/app/${other.fileName}"] = content
        dav.files["/dav/app/${foreign.metaFileName}"] = meta
        dav.files["/dav/app/${name.copy(generation = 43).fileName}.sltmp"] = content
        dav.files["/dav/app/${foreign.fileName}.sltmp"] = content
        dav.files["/dav/app/notas.txt.sltmp"] = content
        dav.files["/dav/app/notas.txt"] = content
        val listing = destination(server).listCopies(control).value()
        assertEquals(
            setOf(name.fileName, other.fileName),
            listing.copies.map { it.ref.value }.toSet(),
        )
        assertEquals(
            ListedMeta.Missing,
            listing.copies.single { it.name == other }.meta,
        )
        assertEquals(listOf(CopyRef(foreign.metaFileName)), listing.orphanMetas)
        assertEquals(
            setOf("0a1b2c3d", "ffffffff", null),
            listing.temporaries.map { it.devicePrefix }.toSet(),
        )
        // Solo se pidió el único .slmeta que tiene su copia.
        assertEquals(
            listOf(metaPath),
            server.requests.filter {
                it.method == "GET"
            }.map { it.path },
        )
    }

    @Test
    fun aMissingFolderIsCreatedAndListsEmpty() = runBlocking<Unit> {
        val dav = InMemoryWebDav()
        val server = server(dav::handle)
        val listing = destination(server).listCopies(control).value()
        assertTrue(listing.copies.isEmpty())
        assertTrue("/dav/app/" in dav.collections)
    }

    @Test
    fun aFolderWithoutAncestorIsAnInvalidBaseWithoutLoop() = runBlocking<Unit> {
        val server =
            server {
                if (it.method ==
                    "MKCOL"
                ) {
                    FakeResponse.status(409)
                } else {
                    FakeResponse.status(404)
                }
            }
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.BASE_URL_INVALID),
            destination(server).listCopies(control).failure(),
        )
        assertEquals(1, server.requests.count { it.method == "MKCOL" })
    }

    @Test
    fun aListWithAnErrorInsideIsIncomplete() = runBlocking<Unit> {
        val server =
            server {
                FakeResponse.xml(
                    207,
                    Dav.multistatus(
                        Dav.folder("/dav/app/"),
                        Dav.file("/dav/app/${name.fileName}", "1", "HTTP/1.1 500 Error"),
                    ),
                )
            }
        assertEquals(
            DestinationFailure.Incomplete,
            destination(server).listCopies(control).failure(),
        )
    }

    @Test
    fun theQuotaComesFromTheFolder() = runBlocking<Unit> {
        val server =
            server { FakeResponse.xml(207, Dav.multistatus(Dav.folder("/dav/app/", "5000"))) }
        assertEquals(5000L, destination(server).listCopies(control).value().quotaAvailableBytes)
    }

    @Test
    fun metaFilesAreCappedAndTheirErrorsLeaveTheListIncomplete() = runBlocking<Unit> {
        fun listing(metaAnswer: (RecordedRequest) -> FakeResponse, metaSize: Int = meta.size) =
            server {
                when (it.method) {
                    "PROPFIND" ->
                        FakeResponse.xml(
                            207,
                            Dav.multistatus(
                                Dav.folder("/dav/app/"),
                                Dav.file(copyPath, "10"),
                                Dav.file(metaPath, metaSize.toString()),
                            ),
                        )

                    else -> metaAnswer(it)
                }
            }
        // Más de 16 KiB según la lista: no se pide, y no vale.
        val big = listing({ FakeResponse.status(500) }, metaSize = 16 * 1024 + 1)
        assertEquals(
            ListedMeta.Invalid,
            destination(big).listCopies(control).value().copies.single().meta,
        )
        assertTrue(big.requests.none { it.method == "GET" })
        // Más de 16 KiB al leerlo, aunque la lista dijera menos.
        val lying = listing({ FakeResponse.Reply(200, body = ByteArray(16 * 1024 + 1)) })
        assertEquals(
            ListedMeta.Invalid,
            destination(lying).listCopies(control).value().copies.single().meta,
        )
        // Un error que no es 404 deja la lista incompleta.
        val teapot = listing({ FakeResponse.status(418) })
        assertEquals(
            DestinationFailure.Incomplete,
            destination(teapot).listCopies(control).failure(),
        )
        // Con otra codificación la respuesta es inválida.
        val gzip = listing({ FakeResponse.Reply(200, mapOf("Content-Encoding" to "gzip"), meta) })
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.INVALID_RESPONSE),
            destination(gzip).listCopies(control).failure(),
        )
        // Un 404 deja la copia sin metadatos.
        val gone = listing({ FakeResponse.status(404) })
        assertEquals(
            ListedMeta.Missing,
            destination(gone).listCopies(control).value().copies.single().meta,
        )
    }

    // Reintentos

    @Test
    fun transientFailuresAreRetriedThreeTimesWithGrowingWaits() = runBlocking<Unit> {
        val server = server { FakeResponse.status(503) }
        assertEquals(
            DestinationFailure.Transient(TransientKind.SERVER_ERROR),
            destination(server).listCopies(control).failure(),
        )
        assertEquals(4, server.requests.size)
        assertEquals(listOf(1_007L, 2_007L, 4_007L), waits)
    }

    @Test
    fun retryAfterIsRespectedOnlyIfItFitsInTheTimeLeft() = runBlocking<Unit> {
        var calls = 0
        val server =
            server {
                calls++
                if (calls == 1) {
                    FakeResponse.status(429, mapOf("Retry-After" to "3"))
                } else {
                    FakeResponse.xml(207, Dav.multistatus(Dav.folder("/dav/app/")))
                }
            }
        destination(server).listCopies(control).value()
        assertEquals(listOf(3_000L), waits)

        waits.clear()
        val slow = server { FakeResponse.status(429, mapOf("Retry-After" to "600")) }
        assertEquals(
            DestinationFailure.Transient(TransientKind.RATE_LIMIT),
            destination(slow).listCopies(TestControl(remaining = 60_000)).failure(),
        )
        assertEquals(1, slow.requests.size)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun aMeteredPutThatAlreadySentTheFileIsNotRepeated() = runBlocking<Unit> {
        val server = server { FakeResponse.status(503) }
        val metered = TestControl(metered = true)
        assertEquals(
            DestinationFailure.Transient(TransientKind.SERVER_ERROR),
            destination(server).upload(plan(), metered).failure(),
        )
        assertEquals(1, server.requests.count { it.method == "PUT" })
    }

    @Test
    fun permissionLossIsNeverRetried() = runBlocking<Unit> {
        val server = server { FakeResponse.status(401) }
        assertEquals(
            DestinationFailure.PermissionLost(),
            destination(server).upload(plan(), control).failure(),
        )
        assertEquals(1, server.requests.size)
        val forbidden = server { FakeResponse.status(403) }
        assertEquals(
            DestinationFailure.PermissionLost(),
            destination(forbidden).listCopies(control).failure(),
        )
    }

    @Test
    fun fullQuotaAndRejectedNamesAreNotRetried() = runBlocking<Unit> {
        val full = server { FakeResponse.status(507) }
        assertEquals(
            DestinationFailure.QuotaExceeded,
            destination(full).upload(plan(), control).failure(),
        )
        assertEquals(1, full.requests.size)
        val badName = server { FakeResponse.status(400) }
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.NAME_REJECTED),
            destination(badName).upload(plan(), control).failure(),
        )
        val (_, moveRejected) = dav { if (it.method == "MOVE") FakeResponse.status(400) else null }
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.NAME_REJECTED),
            destination(moveRejected).upload(plan(), control).failure(),
        )
    }

    // Carpeta que falta

    @Test
    fun aPutWithoutFolderCreatesItAndRepeatsOnce() = runBlocking<Unit> {
        val dav = InMemoryWebDav()
        val server = server(dav::handle)
        destination(server).upload(plan(), control).value()
        assertEquals(
            listOf("PUT", "MKCOL", "PUT", "MOVE", "PUT", "MOVE"),
            server.requests.map { it.method },
        )
        assertArrayEquals(content, dav.files[copyPath])
    }

    @Test
    fun onMeteredNetworkThePutIsNotRepeatedAfterCreatingTheFolder() = runBlocking<Unit> {
        val dav = InMemoryWebDav()
        val server = server(dav::handle)
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.METERED),
            destination(server).upload(plan(), TestControl(metered = true)).failure(),
        )
        assertEquals(listOf("PUT", "MKCOL"), server.requests.map { it.method })
    }

    @Test
    fun aMoveConflictCreatesTheFolderOnceAndThenGivesUp() = runBlocking<Unit> {
        val (_, server) = dav { if (it.method == "MOVE") FakeResponse.status(409) else null }
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.UNEXPECTED_STATUS),
            destination(server).upload(plan(), control).failure(),
        )
        assertEquals(2, server.requests.count { it.method == "MOVE" })
        assertEquals(1, server.requests.count { it.method == "MKCOL" })
    }

    // Respuestas perdidas y cortes

    @Test
    fun aLostMoveResponseIsFoundByNameWhenItsRepeatGives404() = runBlocking<Unit> {
        val dav = InMemoryWebDav()
        dav.collections += "/dav/app/"
        var dropped = false
        val server =
            server {
                val answer = dav.handle(it)
                if (it.method == "MOVE" && !dropped) {
                    // El servidor aplicó el MOVE, pero la respuesta nunca llegó.
                    dropped = true
                    FakeResponse.Drop
                } else {
                    answer
                }
            }
        destination(server).upload(plan(), control).value()
        assertArrayEquals(content, dav.files[copyPath])
        assertEquals(3, server.requests.count { it.method == "MOVE" })
        assertTrue(server.requests.any { it.method == "PROPFIND" && it.path == copyPath })
    }

    @Test
    fun aCopyWithTheSameNameAndAnotherSizeIsNeverTakenAsUploaded() = runBlocking<Unit> {
        val (dav, server) = dav()
        dav.files[copyPath] = ByteArray(10)
        assertEquals(
            DestinationFailure.Incomplete,
            destination(server).upload(plan(), control).failure(),
        )
        assertArrayEquals(ByteArray(10), dav.files[copyPath])
    }

    @Test
    fun stoppingBeforeOrDuringThePutLeavesNoFinalName() = runBlocking<Unit> {
        val (dav, server) = dav()
        val stopped = TestControl(stopped = true)
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
            destination(server).upload(plan(), stopped).failure(),
        )
        assertTrue(server.requests.isEmpty())

        // Se detiene después de la primera escritura del cuerpo.
        var checks = 0
        val midway =
            object : TransferControl by TransferControl.Unbounded {
                override fun isStopped(): Boolean = ++checks > 2
            }
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
            destination(server).upload(plan(), midway).failure(),
        )
        assertTrue(server.requests.none { it.method == "MOVE" })
        assertFalse(copyPath in dav.files)
    }

    @Test
    fun outOfTimeBeforeSendingIsReportedAsSuch() = runBlocking<Unit> {
        val (_, server) = dav()
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.OUT_OF_TIME),
            destination(server).upload(plan(), TestControl(remaining = 0)).failure(),
        )
        assertTrue(server.requests.isEmpty())
    }

    // Verificar

    @Test
    fun verificationFailsWhenSizeContentOrMetaDiffer() = runBlocking<Unit> {
        val (dav, server) = dav()
        val destination = destination(server)
        val copy = destination.upload(plan(), control).value()

        dav.files[copyPath] = content.copyOf(content.size - 1)
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(copy, VerifyMode.SIZE_AND_META, null, control).failure(),
        )
        dav.files[copyPath] = content.copyOf().also { it[100_000] = (it[100_000] + 1).toByte() }
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(copy, VerifyMode.FULL, null, control).failure(),
        )
        // La muestra no ve el centro del archivo: por eso no basta para siempre.
        destination.verify(copy, VerifyMode.SAMPLE, localFile(), control).value()
        dav.files[copyPath] = content.copyOf().also { it[it.size - 1] = 0x55 }
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(copy, VerifyMode.SAMPLE, localFile(), control).failure(),
        )
        dav.files[copyPath] = content
        dav.files[metaPath] = meta.copyOf().also { it[0] = '['.code.toByte() }
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(copy, VerifyMode.FULL, null, control).failure(),
        )
        dav.files.remove(metaPath)
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(copy, VerifyMode.FULL, null, control).failure(),
        )
        dav.files.remove(copyPath)
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(copy, VerifyMode.FULL, null, control).failure(),
        )
        assertTrue(server.requests.none { it.method == "MKCOL" })
    }

    @Test
    fun aCompressedAnswerIsInvalidNotAMismatch() = runBlocking<Unit> {
        val (dav, server) =
            dav {
                if (it.method ==
                    "GET"
                ) {
                    FakeResponse.Reply(200, mapOf("Content-Encoding" to "gzip"), content)
                } else {
                    null
                }
            }
        dav.files[copyPath] = content
        dav.files[metaPath] = meta
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.INVALID_RESPONSE),
            destination(server).verify(stored(), VerifyMode.FULL, null, control).failure(),
        )
    }

    @Test
    fun aServerIgnoringRangeIsComparedOnlyAtTheStart() = runBlocking<Unit> {
        val (dav, server) = dav(honorRange = false)
        // Grande, para que no quepa entero en los búferes del socket: cortar la llamada se nota.
        val big = ByteArray(8 * 1024 * 1024) { (it % 253).toByte() }
        dav.files[copyPath] = big
        dav.files[metaPath] = meta
        destination(server)
            .verify(stored(big), VerifyMode.SAMPLE, localFile(big), control)
            .value()
        val gets = server.requests.filter { it.method == "GET" }
        // Una sola lectura del archivo, la del comienzo, y la de los metadatos.
        assertEquals(listOf(copyPath, metaPath), gets.map { it.path })
        assertTrue("${server.bodyBytesWritten} bytes", server.bodyBytesWritten < big.size / 2)
    }

    @Test
    fun aPartialAnswerWithAnotherRangeIsInvalid() = runBlocking<Unit> {
        val (dav, server) =
            dav {
                if (it.method == "GET" && it.header("Range") != null) {
                    FakeResponse.Reply(
                        206,
                        mapOf("Content-Range" to "bytes 1-65536/${content.size}"),
                        content.copyOfRange(1, 65_537),
                    )
                } else {
                    null
                }
            }
        dav.files[copyPath] = content
        dav.files[metaPath] = meta
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.INVALID_RESPONSE),
            destination(server).verify(stored(), VerifyMode.SAMPLE, localFile(), control).failure(),
        )
    }

    @Test
    fun withoutTimeForTheFullReadTheSampleIsDoneInstead() = runBlocking<Unit> {
        val (dav, server) = dav()
        dav.files[copyPath] = content
        dav.files[metaPath] = meta
        val tight = TestControl(remaining = WebDavDestination.FULL_READ_RESERVE_MILLIS - 1)
        val verified =
            destination(server).verify(stored(), VerifyMode.FULL, localFile(), tight).value()
        assertFalse(verified.fullyRead)
        assertTrue(
            server.requests.filter { it.method == "GET" }.all {
                it.path == metaPath ||
                    it.header("Range") != null
            },
        )
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.OUT_OF_TIME),
            destination(server).verify(stored(), VerifyMode.FULL, null, tight).failure(),
        )
    }

    // Recuperar, bajar, releer y borrar

    @Test
    fun aPendingUploadIsLocatedByName() = runBlocking<Unit> {
        val (dav, server) = dav()
        val destination = destination(server)
        val pending = PendingCopy(name, content.size.toLong(), sha256(content))
        assertEquals(PendingLocation.Absent, destination.locatePending(pending, control).value())
        dav.files[copyPath] = ByteArray(5)
        assertEquals(
            PendingLocation.WrongSize(CopyRef(name.fileName)),
            destination.locatePending(pending, control).value(),
        )
        dav.files[copyPath] = content
        val withoutMeta = destination.locatePending(
            pending,
            control,
        ).value() as PendingLocation.Uploaded
        assertEquals(ListedMeta.Missing, withoutMeta.meta)
        destination.uploadMeta(plan(), control).value()
        val withMeta = destination.locatePending(
            pending,
            control,
        ).value() as PendingLocation.Uploaded
        assertArrayEquals(meta, (withMeta.meta as ListedMeta.Present).bytes)
        assertArrayEquals(meta, withMeta.copy.metaBytes)
    }

    @Test
    fun downloadStreamsWithItsSumAndRespectsTheLimit() = runBlocking<Unit> {
        val (dav, server) = dav()
        dav.files[copyPath] = content
        val destination = destination(server)
        val sink = ByteArrayOutputStream()
        val downloaded = destination.download(
            CopyRef(name.fileName),
            sink,
            content.size.toLong(),
            control,
        ).value()
        assertEquals(DownloadedCopy(content.size.toLong(), sha256(content)), downloaded)
        assertArrayEquals(content, sink.toByteArray())
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.TOO_LARGE),
            destination.download(
                CopyRef(name.fileName),
                ByteArrayOutputStream(),
                10,
                control,
            ).failure(),
        )
        dav.files.remove(copyPath)
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.download(
                CopyRef(name.fileName),
                ByteArrayOutputStream(),
                10,
                control,
            ).failure(),
        )
    }

    @Test
    fun readMetaRereadsWhatIsThereNow() = runBlocking<Unit> {
        val (dav, server) = dav()
        val destination = destination(server)
        assertEquals(
            ListedMeta.Missing,
            destination.readMeta(CopyRef(name.fileName), control).value(),
        )
        dav.files[metaPath] = meta
        assertArrayEquals(
            meta,
            (
                destination.readMeta(
                    CopyRef(name.fileName),
                    control,
                ).value() as ListedMeta.Present
                ).bytes,
        )
    }

    @Test
    fun replacingTheMetaGoesThroughATemporaryNameAndMovesOverTheOldOne() = runBlocking<Unit> {
        val (dav, server) = dav()
        dav.files[copyPath] = content
        dav.files[metaPath] = meta
        val destination = destination(server)
        val pinned = """{"sl_kind":"backup","sl_generation":"42","sl_pinned":1}""".toByteArray()

        destination.replaceMeta(CopyRef(name.fileName), pinned, control).value()

        assertArrayEquals(pinned, dav.files[metaPath])
        assertArrayEquals(content, dav.files[copyPath])
        assertFalse(dav.files.containsKey("$metaPath.sltmp"))
        val requests = server.requests
        assertEquals(listOf("PUT", "MOVE"), requests.map { it.method })
        assertEquals("$metaPath.sltmp", requests[0].path)
        // El único reemplazo que hace la app: pisa los metadatos anteriores.
        assertEquals("T", requests[1].header("Overwrite"))
        assertTrue(requests[1].header("Destination").orEmpty().endsWith(metaPath))
    }

    @Test
    fun deleteRemovesTheCopyThenItsMetaAndOnlyAppFiles() = runBlocking<Unit> {
        val (dav, server) = dav()
        dav.files[copyPath] = content
        dav.files[metaPath] = meta
        val destination = destination(server)
        destination.delete(CopyRef(name.fileName), control).value()
        assertTrue(dav.files.isEmpty())
        assertEquals(listOf(copyPath, metaPath), server.requests.map { it.path })
        // Lo que ya no estaba también cuenta como borrado.
        destination.delete(CopyRef(name.fileName), control).value()
        destination.delete(CopyRef("${name.fileName}.sltmp"), control).value()
        for (foreign in listOf("notas.txt", "media", "notas.txt.sltmp")) {
            val refused =
                try {
                    destination.delete(CopyRef(foreign), control)
                    false
                } catch (_: IllegalArgumentException) {
                    true
                }
            assertTrue(foreign, refused)
        }
    }
}
