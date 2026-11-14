package io.github.yesidrobayo.shopledger.data.destination

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.backup.PackEncryption
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedReadResult
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.media.MediaForm
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
import java.io.ByteArrayOutputStream
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
 * `media-sync` contra el servicio WebDAV falso en memoria: sube cada foto con su `.slmeta` a la
 * subcarpeta de su prefijo en `media/` y la verifica, crea cada subcarpeta una sola vez, lista
 * solo las que hacen falta y no lee nada fuera de su subcarpeta. Una foto nunca aparece como
 * copia, se detiene si en el servicio caben menos de 3 copias, cede al respaldo, respeta la red
 * medida y no hace nada con ranuras. Baja lo
 * que falta tras restaurar, con tope y comprobado, cifra cada foto con el cifrado activo y nunca la
 * sube en claro, la reemplaza al cambiar de código o apagarlo y borra lo que la limpieza borró.
 *
 * Casi todas las fotos son bytes inventados con su suma; las que deben decodificarse son WebP
 * inventados.
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
            assertTrue(dav.files.containsKey(remote("${photo.sha256}.webp")))
            val meta = dav.files.getValue(remote("${photo.sha256}.webp.slmeta"))
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
    fun aMissingPhotoIsDownloadedCheckedAndSavedUnderItsName() = runBlocking {
        val photo = realPhoto()
        val bytes = localFile(photo).readBytes()
        sync(listOf(photo)).run(TransferControl.Unbounded)
        localFile(photo).delete()

        val run =
            sync(listOf(photo), isImage = MediaSync::decodesAsImage).run(TransferControl.Unbounded)

        assertEquals(MediaSyncRun.DONE, run)
        assertTrue(bytes.contentEquals(localFile(photo).readBytes()))
        assertEquals(emptySet<String>(), store().unavailable())
        assertFalse(media.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun aPhotoTheServiceDoesNotHaveIsUnavailableWithoutAnError() = runBlocking {
        val photo = photo(1)
        localFile(photo).delete()

        assertEquals(MediaSyncRun.DONE, sync(listOf(photo)).run(TransferControl.Unbounded))

        assertFalse(localFile(photo).exists())
        assertEquals(setOf(photo.sha256), store().unavailable())
    }

    @Test
    fun aDownloadThatIsNotThePhotoOrNotAnImageIsDiscarded() = runBlocking {
        // Con su nombre pero otro contenido, y con su contenido pero sin ser imagen.
        val changed = photo(1)
        val notImage = photo(2)
        sync(listOf(changed, notImage)).run(TransferControl.Unbounded)
        dav.files[remote("${changed.sha256}.webp")] = ByteArray(50)
        localFile(changed).delete()
        localFile(notImage).delete()

        sync(listOf(changed, notImage), isImage = MediaSync::decodesAsImage)
            .run(TransferControl.Unbounded)

        assertFalse(localFile(changed).exists())
        assertFalse(localFile(notImage).exists())
        assertEquals(setOf(changed.sha256, notImage.sha256), store().unavailable())
    }

    @Test
    fun aDownloadIsCappedAtTwoMebibytes() = runBlocking {
        val photo = photo(1)
        sync(listOf(photo)).run(TransferControl.Unbounded)
        dav.files[remote("${photo.sha256}.webp")] = ByteArray(2 * 1024 * 1024 + 1)
        localFile(photo).delete()

        sync(listOf(photo)).run(TransferControl.Unbounded)

        assertFalse(localFile(photo).exists())
        assertEquals(setOf(photo.sha256), store().unavailable())
    }

    @Test
    fun withEncryptionEveryPhotoUploadsSealedAndNothingGoesOutInClear() = runBlocking {
        val photos = (1..2).map(::photo)

        sync(photos, encryption = PackEncryption.Encrypt(CODE)).run(TransferControl.Unbounded)

        val key = BackupKey.from(CODE)
        for (photo in photos) {
            val sealed = dav.files.getValue(remote("${photo.sha256}.slmd"))
            assertTrue(sealed.copyOfRange(0, 4).contentEquals(byteArrayOf(0x53, 0x4C, 0x42, 0x4B)))
            assertFalse(sealed.contentEquals(localFile(photo).readBytes()))
            val meta = dav.files.getValue(remote("${photo.sha256}.slmd.slmeta"))
            assertTrue(meta.decodeToString().contains("\"sl_key_id\":\"${key.keyIdHex}\""))
        }
        assertFalse(server.requests.any { it.method == "PUT" && ".webp" in it.path })
        assertEquals(
            photos.associate { it.sha256 to MediaForm.Sealed(key.keyIdHex) },
            store().verifiedForms(),
        )
    }

    @Test
    fun withoutTheKeyNothingUploadsAndNeverInClear() = runBlocking {
        sync(listOf(photo(1)), encryption = PackEncryption.KeyUnavailable)
            .run(TransferControl.Unbounded)

        assertEquals(0, puts())
    }

    @Test
    fun turningEncryptionOnDeletesThePlainPhotoOnlyAfterTheSealedOneIsVerified() = runBlocking {
        val photo = photo(1)
        sync(listOf(photo)).run(TransferControl.Unbounded)
        val before = server.requests.size

        sync(listOf(photo), encryption = PackEncryption.Encrypt(CODE))
            .run(TransferControl.Unbounded)

        val after = server.requests.drop(before).map { it.method to it.path }
        val verified = after.indexOfLast { it == "GET" to remote("${photo.sha256}.slmd") }
        val deleted = after.indexOf("DELETE" to remote("${photo.sha256}.webp"))
        assertTrue(after.toString(), verified in 0 until deleted)
        assertFalse(dav.files.containsKey(remote("${photo.sha256}.webp")))
        assertFalse(dav.files.containsKey(remote("${photo.sha256}.webp.slmeta")))
        assertTrue(dav.files.containsKey(remote("${photo.sha256}.slmd")))
    }

    @Test
    fun aNewCodeReplacesEachSealedPhotoAfterVerifyingTheNewOne() = runBlocking {
        val photo = photo(1)
        sync(listOf(photo), encryption = PackEncryption.Encrypt(CODE))
            .run(TransferControl.Unbounded)

        sync(listOf(photo), encryption = PackEncryption.Encrypt(OTHER_CODE))
            .run(TransferControl.Unbounded)

        val key = BackupKey.from(OTHER_CODE)
        val sealed = dav.files.getValue(remote("${photo.sha256}.slmd"))
        val read = EncryptedCopy.read(sealed.inputStream(), key, sink = null)
        assertTrue(read.toString(), read is EncryptedReadResult.Read)
        val meta = dav.files.getValue(remote("${photo.sha256}.slmd.slmeta"))
        assertTrue(meta.decodeToString().contains("\"sl_key_id\":\"${key.keyIdHex}\""))
        assertFalse(dav.files.keys.any { it.endsWith(".sltmp") })
        assertEquals(mapOf(photo.sha256 to MediaForm.Sealed(key.keyIdHex)), store().verifiedForms())
    }

    @Test
    fun turningEncryptionOffUploadsInClearAndThenDeletesTheSealedPhoto() = runBlocking {
        val photo = photo(1)
        sync(listOf(photo), encryption = PackEncryption.Encrypt(CODE))
            .run(TransferControl.Unbounded)

        sync(listOf(photo)).run(TransferControl.Unbounded)

        assertTrue(dav.files.containsKey(remote("${photo.sha256}.webp")))
        assertFalse(dav.files.containsKey(remote("${photo.sha256}.slmd")))
        assertFalse(dav.files.containsKey(remote("${photo.sha256}.slmd.slmeta")))
        assertEquals(mapOf(photo.sha256 to MediaForm.Plain), store().verifiedForms())
    }

    @Test
    fun aSealedMissingPhotoIsDownloadedAndDecrypted() = runBlocking {
        val photo = realPhoto()
        val bytes = localFile(photo).readBytes()
        sync(listOf(photo), encryption = PackEncryption.Encrypt(CODE))
            .run(TransferControl.Unbounded)
        localFile(photo).delete()
        store().forget()

        sync(
            listOf(photo),
            encryption = PackEncryption.Encrypt(CODE),
            isImage = MediaSync::decodesAsImage,
        ).run(TransferControl.Unbounded)

        assertTrue(bytes.contentEquals(localFile(photo).readBytes()))
    }

    @Test
    fun aSealedPhotoWithAnotherCodeIsUnavailableAndNothingIsDeleted() = runBlocking {
        val photo = photo(1)
        sync(listOf(photo), encryption = PackEncryption.Encrypt(CODE))
            .run(TransferControl.Unbounded)
        localFile(photo).delete()
        val files = dav.files.keys.toSet()

        sync(listOf(photo), encryption = PackEncryption.Encrypt(OTHER_CODE))
            .run(TransferControl.Unbounded)

        assertFalse(localFile(photo).exists())
        assertEquals(setOf(photo.sha256), store().unavailable())
        assertEquals(files, dav.files.keys.toSet())
    }

    @Test
    fun photosTheCleanupDeletedAreRemovedFromTheServiceUnlessInUseAgain() = runBlocking {
        val gone = photo(1)
        val back = photo(2)
        sync(listOf(gone, back)).run(TransferControl.Unbounded)
        localFile(gone).delete()
        store().saveDeletions(setOf(gone.sha256, back.sha256))

        sync(listOf(back)).run(TransferControl.Unbounded)

        assertFalse(dav.files.containsKey(remote("${gone.sha256}.webp")))
        assertFalse(dav.files.containsKey(remote("${gone.sha256}.webp.slmeta")))
        assertTrue(dav.files.containsKey(remote("${back.sha256}.webp")))
        assertEquals(emptySet<String>(), store().deletions())
        assertEquals(setOf(back.sha256), store().verified())
    }

    @Test
    fun eachPhotoGoesToTheSubfolderOfItsPrefixAndEachSubfolderIsCreatedOnce() = runBlocking {
        val (a, b, c) = seedsInOneShard(3)
        val photos = listOf(photo(a), photo(b), photo(SEED_ELSEWHERE))

        sync(photos).run(TransferControl.Unbounded)

        for (photo in photos) {
            assertTrue(dav.files.containsKey(remote("${photo.sha256}.webp")))
            assertTrue(dav.files.containsKey(remote("${photo.sha256}.webp.slmeta")))
        }
        // Nada queda suelto en media/: cada archivo va en su subcarpeta.
        assertFalse(dav.files.keys.any { it.substringBeforeLast('/') == "/dav/app/media" })
        val made = mkcols()
        assertEquals(made.toString(), made.toSet().size, made.size)
        assertEquals(
            photos.map { "/dav/app/media/${it.sha256.take(2)}/" }.toSet(),
            made.filter { it != "/dav/app/media/" }.toSet(),
        )

        // Una foto nueva en una subcarpeta que ya existe no la crea otra vez: la lista la encontró.
        val third = photo(c)
        sync(photos + third).run(TransferControl.Unbounded)

        assertEquals(made, mkcols())
        assertTrue(dav.files.containsKey(remote("${third.sha256}.webp")))
    }

    @Test
    fun onlyTheSubfolderOfAPhotoToFetchIsListedAndItIsRestoredFromThere() = runBlocking {
        val photos = (1..4).map(::photo)
        sync(photos).run(TransferControl.Unbounded)

        // Todo verificado: ninguna subcarpeta se lista.
        val idle = server.requests.size
        sync(photos).run(TransferControl.Unbounded)
        assertEquals(emptyList<String>(), shardListings(idle))

        val gone = photos.first()
        val bytes = localFile(gone).readBytes()
        localFile(gone).delete()
        val mark = server.requests.size
        sync(photos).run(TransferControl.Unbounded)

        val shard = "/dav/app/media/${gone.sha256.take(2)}/"
        assertEquals(listOf(shard), shardListings(mark))
        assertTrue(bytes.contentEquals(localFile(gone).readBytes()))
        assertTrue(
            server.requests.drop(mark).any {
                it.method == "GET" && it.path == remote("${gone.sha256}.webp")
            },
        )
    }

    @Test
    fun aPhotoOutsideItsSubfolderOrWithAnotherNameIsNeverRead() = runBlocking {
        val photo = photo(1)
        val bytes = localFile(photo).readBytes()
        sync(listOf(photo)).run(TransferControl.Unbounded)
        val name = "${photo.sha256}.webp"
        val meta = dav.files.getValue(remote("$name.slmeta"))
        dav.files.remove(remote(name))
        dav.files.remove(remote("$name.slmeta"))
        // Plana en media/, como antes de las subcarpetas; en otra subcarpeta; y en la suya, pero
        // con mayúsculas, que la app nunca escribe.
        val other = if (photo.sha256.startsWith("00")) "ff" else "00"
        dav.collections += "/dav/app/media/$other/"
        val upper = "/dav/app/media/${photo.sha256.take(2)}/${photo.sha256.uppercase()}.webp"
        for (path in listOf("/dav/app/media/$name", "/dav/app/media/$other/$name", upper)) {
            dav.files[path] = bytes
            dav.files["$path.slmeta"] = meta
        }
        localFile(photo).delete()
        val mark = server.requests.size

        sync(listOf(photo)).run(TransferControl.Unbounded)

        assertFalse(localFile(photo).exists())
        assertEquals(setOf(photo.sha256), store().unavailable())
        assertFalse(server.requests.drop(mark).any { it.method == "GET" })
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
        encryption: PackEncryption = PackEncryption.Plain,
        isImage: (ByteArray) -> Boolean = { true },
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
        encryption = { encryption },
        deviceId = { DEVICE },
        isImage = isImage,
    )

    private fun destination() = WebDavDestination(testClient(server), APP_FOLDER, sleep = {})

    private fun store() = MediaSyncStore(root, Dispatchers.IO)

    private fun puts() = server.requests.count { it.method == "PUT" }

    private fun mkcols() = server.requests.filter { it.method == "MKCOL" }.map { it.path }

    /** Los `PROPFIND` de subcarpetas de `media/` desde la petición [from]. */
    private fun shardListings(from: Int) =
        server.requests.drop(from)
            .filter { it.method == "PROPFIND" && it.path.startsWith("/dav/app/media/") }
            .map { it.path }
            .filter { it != "/dav/app/media/" }

    /** Dónde vive [name] en el servicio: en la subcarpeta de los dos primeros caracteres. */
    private fun remote(name: String) = "/dav/app/media/${name.take(2)}/$name"

    /** [count] semillas de [photo] cuyas sumas comparten subcarpeta. */
    private fun seedsInOneShard(count: Int): List<Int> {
        val byShard = HashMap<String, MutableList<Int>>()
        for (seed in 1..100_000) {
            val shard = sha256(Random(seed).nextBytes(4_000 + seed)).take(2)
            val seeds = byShard.getOrPut(shard) { mutableListOf() }
            seeds += seed
            if (seeds.size == count) return seeds
        }
        error("Sin semillas que compartan subcarpeta")
    }

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

    private fun localFile(photo: StoredImage) = File(media, StoredImage.fileNameOf(photo.sha256))

    /** Una foto de verdad, un WebP inventado de 64 x 48, que se decodifica como imagen. */
    private fun realPhoto(): StoredImage {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 120, 40))
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
        bitmap.recycle()
        val bytes = out.toByteArray()
        val sha = sha256(bytes)
        File(media, StoredImage.fileNameOf(sha)).writeBytes(bytes)
        return StoredImage(sha, "image/webp", 64, 48, bytes.size.toLong())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun control(metered: Boolean) = object : TransferControl {
        override fun isStopped(): Boolean = false

        override fun remainingMillis(): Long? = null

        override fun meteredStop(): Boolean = false

        override fun isMetered(): Boolean = metered
    }

    private companion object {
        const val DEVICE = "00000000-0000-4000-8000-000000000001"

        /** Una semilla grande, para que su foto no coincida con las de las primeras semillas. */
        const val SEED_ELSEWHERE = 200_001
        val CODE = RecoveryCode("0123456789ABCDEFGHJKMNPQRS")
        val OTHER_CODE = RecoveryCode("SRQPNMKJHGFEDCBA9876543210")
    }

    private object SilentLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }
}
