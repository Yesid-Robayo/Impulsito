package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.media.MediaOpen
import io.github.yesidrobayo.shopledger.data.media.MediaSync
import io.github.yesidrobayo.shopledger.data.media.MediaSyncAccess
import io.github.yesidrobayo.shopledger.data.media.MediaSyncRun
import io.github.yesidrobayo.shopledger.data.media.MediaSyncStop
import io.github.yesidrobayo.shopledger.data.media.MediaSyncStore
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `media-sync` contra el servicio WebDAV falso en memoria: sube cada foto con su `.slmeta` a
 * `media/` y la verifica, una foto nunca aparece como copia, se detiene si en el servicio caben
 * menos de 3 copias, cede al respaldo, respeta la red medida y no hace nada con ranuras.
 *
 * Las fotos son bytes inventados con su suma: aquí no hace falta decodificarlas.
 */
class MediaSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var media: File
    private lateinit var dav: InMemoryWebDav
    private lateinit var server: FakeHttpServer
    private val mutex = Mutex()

    @Before
    fun setUp() {
        root = File(context.cacheDir, "media-sync-" + Random.nextLong().toULong())
        media = File(root, "media").apply { mkdirs() }
        dav = InMemoryWebDav().apply { collections += "/dav/app/" }
        server = FakeHttpServer(keepAlive = true, handler = dav::handle)
    }

    @After
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    @Test
    fun eachPhotoUploadsWithItsMetaAndIsVerified() = runBlocking {
        val photos = (1..3).map(::photo)

        assertEquals(MediaSyncRun.DONE, sync(photos).run(TransferControl.Unbounded))

        for (photo in photos) {
            assertTrue(dav.files.containsKey("/dav/app/media/${photo.sha256}.webp"))
            val meta = dav.files.getValue("/dav/app/media/${photo.sha256}.webp.slmeta")
            val text = meta.decodeToString()
            assertTrue(text, text.contains("\"sl_kind\":\"media\""))
            assertTrue(text, text.contains("\"sl_media_sha256\":\"${photo.sha256}\""))
        }
        assertFalse(dav.files.keys.any { it.endsWith(".sltmp") })
        assertEquals(photos.map { it.sha256 }.toSet(), store().verified())
        assertNull(store().stop())
    }

    @Test
    fun aPhotoNeverAppearsAsACopy() = runBlocking {
        dav.listsFolders = true
        sync((1..2).map(::photo)).run(TransferControl.Unbounded)

        val listing = (
            destination().listCopies(TransferControl.Unbounded)
                as DestinationResult.Success
            ).value

        assertTrue(listing.copies.isEmpty())
        assertTrue(listing.orphanMetas.isEmpty())
        assertTrue(listing.temporaries.isEmpty())
    }

    @Test
    fun verifiedPhotosAreNotUploadedAgainAndALostStateIsRebuiltByListing() = runBlocking {
        val photos = (1..3).map(::photo)
        sync(photos).run(TransferControl.Unbounded)
        val puts = puts()

        sync(photos).run(TransferControl.Unbounded)
        assertEquals(puts, puts())

        store().forget()
        sync(photos).run(TransferControl.Unbounded)
        assertEquals(puts, puts())
        assertEquals(photos.map { it.sha256 }.toSet(), store().verified())
    }

    @Test
    fun itStopsWhenFewerThanThreeCopiesFitAndSaysWhy() = runBlocking {
        // Una copia de 1000 bytes en la carpeta, y espacio para dos y media.
        val copy = BackupFileName(
            42,
            1,
            requireNotNull(CopyInstant.parse("20261113T120000Z")),
            "0a1b2c3d",
            BackupFileFormat.GZIP,
        )
        dav.files["/dav/app/${copy.fileName}"] = ByteArray(1_000)
        dav.quota = "2500"
        val photos = (1..2).map(::photo)

        assertEquals(MediaSyncRun.DONE, sync(photos).run(TransferControl.Unbounded))
        assertEquals(0, puts())
        assertEquals(MediaSyncStop.SERVICE_SPACE, store().stop())

        // Con espacio para tres, sigue y deja de decir que se detuvo.
        dav.quota = "3000"
        sync(photos).run(TransferControl.Unbounded)
        assertEquals(photos.map { it.sha256 }.toSet(), store().verified())
        assertNull(store().stop())
    }

    @Test
    fun onAMeteredNetworkOnlyWhenThePersonAllowsIt() = runBlocking {
        val photos = listOf(photo(1))
        val metered = control(metered = true)

        assertEquals(MediaSyncRun.DONE, sync(photos).run(metered))
        assertEquals(0, server.requests.size)

        sync(photos, onMetered = true).run(metered)
        assertEquals(setOf(photos.single().sha256), store().verified())
    }

    @Test
    fun itYieldsToTheBackupAndNeverWaitsForItsLock() = runBlocking {
        mutex.lock()
        try {
            assertEquals(MediaSyncRun.DONE, sync(listOf(photo(1))).run(TransferControl.Unbounded))
        } finally {
            mutex.unlock()
        }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun withSlotsNothingLeavesThePhone() = runBlocking {
        val run =
            sync(listOf(photo(1)), kind = BackupDestinationKind.DOCUMENT_SLOTS)
                .run(TransferControl.Unbounded)

        assertEquals(MediaSyncRun.DONE, run)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun aPhotoTheServiceReturnsChangedIsNotMarkedAsSaved() = runBlocking {
        val changing = InMemoryWebDav(
            override = { request ->
                if (request.method == "GET" && request.path.endsWith(".webp")) {
                    FakeResponse.Reply(200, body = ByteArray(10))
                } else {
                    null
                }
            },
        ).apply { collections += "/dav/app/" }
        server.close()
        dav = changing
        server = FakeHttpServer(keepAlive = true, handler = changing::handle)

        sync(listOf(photo(1))).run(TransferControl.Unbounded)

        assertEquals(emptySet<String>(), store().verified())
    }

    @Test
    fun spaceIsMeasuredInWholeCopies() {
        assertFalse(MediaSync.lacksSpace(availableBytes = null, copyBytes = 1_000))
        assertFalse(MediaSync.lacksSpace(availableBytes = 2_000, copyBytes = null))
        assertFalse(MediaSync.lacksSpace(availableBytes = 3_000, copyBytes = 1_000))
        assertTrue(MediaSync.lacksSpace(availableBytes = 2_999, copyBytes = 1_000))
        assertTrue(MediaSync.lacksSpace(availableBytes = 0, copyBytes = 1))
    }

    private fun sync(
        photos: List<StoredImage>,
        onMetered: Boolean = false,
        kind: BackupDestinationKind = BackupDestinationKind.WEBDAV,
    ) = MediaSync(
        prepare = {
            MediaSyncAccess.Ready(
                ConnectedDestination(kind, "destino-de-prueba", server.url() + "app/"),
                photos,
                onMetered,
            )
        },
        open = {
            MediaOpen.Opened(destination(), WebDavMediaChannel(testClient(server), APP_FOLDER))
        },
        store = ::store,
        mediaDirectory = { media },
        mutex = mutex,
        log = SilentLog,
    )

    private fun destination() = WebDavDestination(testClient(server), APP_FOLDER, sleep = {})

    private fun store() = MediaSyncStore(root, Dispatchers.IO)

    private fun puts() = server.requests.count { it.method == "PUT" }

    /** Una foto inventada en la carpeta local, nombrada por su suma. */
    private fun photo(seed: Int): StoredImage {
        val bytes = Random(seed).nextBytes(4_000 + seed)
        val sha =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        File(media, StoredImage.fileNameOf(sha)).writeBytes(bytes)
        return StoredImage(sha, "image/webp", 640, 480, bytes.size.toLong())
    }

    private fun control(metered: Boolean) = object : TransferControl {
        override fun isStopped(): Boolean = false

        override fun remainingMillis(): Long? = null

        override fun meteredStop(): Boolean = false

        override fun isMetered(): Boolean = metered
    }

    private object SilentLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }
}
