package io.github.yesidrobayo.shopledger.data.backup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.APP_FOLDER
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.InMemoryWebDav
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestination
import io.github.yesidrobayo.shopledger.data.destination.testClient
import io.github.yesidrobayo.shopledger.data.media.MediaImporter
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import java.io.File
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La medición de fotos con mil archivos, con datos inventados y en el teléfono:
 *
 * - **Importar:** mil fotos como las de una cámara, de 1600 x 1200, pasan por el importador real.
 *   Cada una queda en 64 KiB o menos, y se mide cuánto tarda.
 * - **La copia de la base:** la misma base de mil productos se copia sin fotos y con una foto en
 *   cada producto. Las fotos nunca entran en la copia: solo crecen las filas de `media_assets`.
 *
 * Solo registra conteos, tamaños y duraciones.
 */
@RunWith(AndroidJUnit4::class)
class PhotoVolumeMeasureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "photo-volume")
    private val inputs = File(root, "inputs")
    private val media = File(root, "media")
    private val fixture = BackupFixture(context, "photo-volume")

    @Before
    fun open() = runBlocking {
        root.deleteRecursively()
        inputs.mkdirs()
        media.mkdirs()
        fixture.open()
    }

    @After
    fun close() {
        fixture.close()
        root.deleteRecursively()
    }

    @Test
    fun aThousandCameraPhotosImportUnderTheirLimit() = runBlocking {
        val importer = MediaImporter(context.contentResolver, { media }, Dispatchers.IO)
        val durations = ArrayList<Long>()
        var largest = 0L
        var total = 0L
        for (seed in 1..PHOTOS) {
            val source = cameraPhoto(seed)
            val mark = TimeSource.Monotonic.markNow()
            val result = importer.import(Uri.fromFile(source).toString(), MediaKind.PRODUCT_PHOTO)
            durations += mark.elapsedNow().inWholeMilliseconds
            source.delete()
            val image = (result as? ImageImport.Imported)?.image
                ?: throw AssertionError("No se importó la foto $seed: $result")
            largest = maxOf(largest, image.sizeBytes)
            total += image.sizeBytes
        }
        durations.sort()
        val median = durations[durations.size / 2]
        val p95 = durations[durations.size * 95 / 100]
        Log.i(
            TAG,
            "importar $PHOTOS fotos: total ${durations.sum()} ms, mediana " +
                "$median ms, p95 $p95 " +
                "ms, máxima ${durations.last()} ms; la mayor pesa $largest bytes y todas suman " +
                "$total bytes",
        )
        assertTrue("La mayor pesa $largest", largest <= MAX_PHOTO_BYTES)
        assertEquals(PHOTOS, media.listFiles().orEmpty().count { it.name.endsWith(".webp") })
    }

    @Test
    fun theCopyOfTheDatabaseDoesNotCarryThePhotos() = runBlocking<Unit> {
        val dav = InMemoryWebDav().also { it.collections += "/dav/app/" }
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle)
        try {
            fixture.connect(BackupDestinationKind.WEBDAV)
            for (index in 1..PHOTOS) fixture.addProduct("Producto de prueba $index")
            val withoutPhotos = copy(server, dav, "sin fotos")

            // Una foto distinta en cada producto: solo sus filas, la copia no lleva archivos.
            fixture.sql(
                "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < " +
                    "$PHOTOS) INSERT INTO media_assets (sha256, kind, mime_type, width_px, " +
                    "height_px, size_bytes, created_at, orphaned_at) SELECT printf('%064x', i), " +
                    "'PRODUCT_PHOTO', 'image/webp', 640, 480, 40000 + i, 1767225600000, NULL " +
                    "FROM n",
            )
            fixture.sql(
                "UPDATE products SET photo_media_id = (SELECT m.id FROM media_assets m " +
                    "WHERE m.sha256 = printf('%064x', products.id))",
            )
            fixture.advance(kotlin.time.Duration.parse("2m"))
            fixture.ports.backupState.forceUpload(fixture.now.toEpochMilliseconds())
            val withPhotos = copy(server, dav, "con fotos")

            val growth = withPhotos - withoutPhotos
            Log.i(TAG, "la copia crece $growth bytes con $PHOTOS filas de fotos")
            // Las fotos en la copia sumarían más de 40 MB: lo que crece son solo sus filas.
            assertTrue("Creció $growth bytes", growth < PHOTOS * ROW_BYTES_CEILING)
        } finally {
            server.close()
        }
    }

    /** Sube una copia y devuelve el tamaño del archivo nuevo en el destino. */
    private suspend fun copy(server: FakeHttpServer, dav: InMemoryWebDav, label: String): Long {
        val before = dav.files.keys.toSet()
        val mark = TimeSource.Monotonic.markNow()
        val run = fixture.runner(webDav(server)).run(TestControl())
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        assertTrue("$run", run is BackupRun.Verified)
        val file =
            dav.files.entries.single {
                it.key !in before && it.key.endsWith(".db.gz")
            }
        val size = file.value.size.toLong()
        Log.i(TAG, "copia $label: $size bytes en $elapsed ms")
        return size
    }

    private fun webDav(server: FakeHttpServer) =
        WebDavDestination(client = testClient(server), folder = APP_FOLDER, sleep = {
        }, jitterMillis = { 0 })

    /** Una foto inventada como la de una cámara: un fondo, formas al azar y ruido de JPEG. */
    private fun cameraPhoto(seed: Int): File {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(1_600, 1_200, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        Canvas(bitmap).apply {
            drawColor(Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
            repeat(SHAPES) {
                paint.color =
                    Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                val x = random.nextInt(1_600).toFloat()
                val y = random.nextInt(1_200).toFloat()
                drawCircle(x, y, random.nextInt(20, 300).toFloat(), paint)
            }
        }
        val file = File(inputs, "foto-$seed.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private companion object {
        const val TAG = "PhotoVolumeMeasure"
        const val PHOTOS = 1_000
        const val SHAPES = 40
        const val MAX_PHOTO_BYTES = 64L * 1024

        /** Una fila de `media_assets` comprimida pesa mucho menos que esto. */
        const val ROW_BYTES_CEILING = 200
    }
}
