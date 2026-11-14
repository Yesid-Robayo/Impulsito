package io.github.yesidrobayo.shopledger.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Traer una imagen elegida: se recodifica a WebP sin EXIF ni ubicación, cabe en su tope, queda
 * derecha, se nombra por su contenido, el original nunca se guarda y lo que no es una imagen se
 * rechaza sin escribir nada. Todas las imágenes son inventadas, dibujadas por la prueba.
 */
@RunWith(AndroidJUnit4::class)
internal class MediaImporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var media: File
    private lateinit var inputs: File

    @Before
    fun createFolders() {
        val run = "media-test-" + Random.nextLong().toULong()
        media = File(context.filesDir, run)
        inputs = File(context.cacheDir, run).apply { mkdirs() }
    }

    @After
    fun deleteFolders() {
        media.deleteRecursively()
        inputs.deleteRecursively()
    }

    @Test
    fun aPhotoWithLocationBecomesAnUprightWebpWithoutExifWithinItsLimit() = runBlocking {
        // Una foto apaisada de 1200 x 800 con la etiqueta de girarla y con ubicación.
        val photo = jpeg("foto.jpg", 1_200, 800)
        ExifInterface(photo.path).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "4/1,36/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_MAKE, "Marca inventada")
            saveAttributes()
        }

        val image =
            imported(importer().import(Uri.fromFile(photo).toString(), MediaKind.PAYMENT_QR))

        val file = File(media, image.fileName)
        val bytes = file.readBytes()
        assertTrue(bytes.size <= 64 * 1024)
        assertEquals(bytes.size.toLong(), image.sizeBytes)
        assertEquals(MediaImporter.sha256Of(bytes), image.sha256)
        assertEquals("image/webp", image.mimeType)
        // Girada: ahora es vertical, con el lado mayor en 640.
        assertEquals(640, image.heightPx)
        assertEquals(427, image.widthPx)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(image.widthPx, decoded.width)
        val chunks = requireNotNull(webpChunks(bytes))
        assertFalse(chunks.toString(), chunks.any { it == "EXIF" || it == "XMP " })
        // Solo queda el WebP: ni el original ni un temporal.
        assertEquals(listOf(image.fileName), media.list()?.toList())
    }

    @Test
    fun theSameImageTwiceIsOneFile() = runBlocking {
        val photo = jpeg("qr.jpg", 500, 500)
        val first =
            imported(importer().import(Uri.fromFile(photo).toString(), MediaKind.PAYMENT_QR))
        val modified = File(media, first.fileName).lastModified()

        val second =
            imported(importer().import(Uri.fromFile(photo).toString(), MediaKind.PAYMENT_QR))

        assertEquals(first, second)
        assertEquals(1, media.list()?.size)
        assertEquals(modified, File(media, second.fileName).lastModified())
    }

    @Test
    fun aNoisyLargeImageStillFitsItsLimit() = runBlocking {
        val noise = Bitmap.createBitmap(2_000, 1_500, Bitmap.Config.ARGB_8888)
        val random = Random(SEED)
        for (y in 0 until noise.height step 4) {
            for (x in 0 until noise.width step 4) noise.setPixel(x, y, random.nextInt() or OPAQUE)
        }
        val file = File(inputs, "ruido.png")
        file.outputStream().use { noise.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val image =
            imported(importer().import(Uri.fromFile(file).toString(), MediaKind.PRODUCT_PHOTO))

        assertTrue(image.sizeBytes <= 64 * 1024)
        assertTrue(maxOf(image.widthPx, image.heightPx) <= 640)
    }

    @Test
    fun whatIsNotAnImageIsRejectedWithoutWritingAnything() = runBlocking {
        val notImage = File(inputs, "texto.jpg").apply { writeBytes(Random(SEED).nextBytes(4_096)) }
        val truncated = File(inputs, "cortada.jpg")
        truncated.writeBytes(jpeg("entera.jpg", 300, 300).readBytes().copyOf(40))
        val missing = File(inputs, "no-existe.jpg")

        for (source in listOf(notImage, truncated, missing)) {
            val result = importer().import(Uri.fromFile(source).toString(), MediaKind.PAYMENT_QR)
            assertEquals(source.name, ImageImport.Unreadable, result)
        }
        assertTrue(media.list().isNullOrEmpty())
    }

    @Test
    fun anImageOverTheReadLimitIsRejected() = runBlocking {
        val photo = jpeg("grande.jpg", 800, 800)
        val small = importer(maxSourceBytes = photo.length() - 1)

        assertEquals(
            ImageImport.Unreadable,
            small.import(Uri.fromFile(photo).toString(), MediaKind.PAYMENT_QR),
        )
        assertTrue(media.list().isNullOrEmpty())
    }

    @Test
    fun twoProductPhotosWithTheSameContentAreOneFile() = runBlocking {
        val photo = jpeg("pan.jpg", 900, 700)
        val copy = File(inputs, "pan-copia.jpg").apply { writeBytes(photo.readBytes()) }

        val first =
            imported(importer().import(Uri.fromFile(photo).toString(), MediaKind.PRODUCT_PHOTO))
        val second =
            imported(importer().import(Uri.fromFile(copy).toString(), MediaKind.PRODUCT_PHOTO))

        assertEquals(first, second)
        assertEquals(listOf(first.fileName), media.list()?.toList())
        assertTrue(first.sizeBytes <= 64 * 1024)
        assertEquals(640, maxOf(first.widthPx, first.heightPx))
    }

    @Test
    fun anImageThatDeclaresHugeSizesIsRejectedWithoutDecodingIt() = runBlocking {
        // Unos cientos de bytes que dicen ser una imagen de 30.000 x 30.000: 900 millones de píxeles.
        val bomb = File(inputs, "bomba.png").apply { writeBytes(pngHeader(30_000, 30_000)) }
        val wide = File(inputs, "ancha.png").apply { writeBytes(pngHeader(20_001, 10)) }
        val markup = File(inputs, "dibujo.png").apply {
            writeText("<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'/>")
        }

        for (source in listOf(bomb, wide, markup)) {
            val result =
                importer().import(Uri.fromFile(source).toString(), MediaKind.PRODUCT_PHOTO)
            assertEquals(source.name, ImageImport.Unreadable, result)
        }
        assertTrue(media.list().isNullOrEmpty())
    }

    @Test
    fun onlyReasonableDeclaredSizesAreRead() {
        assertTrue(MediaImporter.acceptsDeclaredSize(16_320, 12_240))
        assertTrue(MediaImporter.acceptsDeclaredSize(20_000, 10_000))
        assertFalse(MediaImporter.acceptsDeclaredSize(20_000, 10_001))
        assertFalse(MediaImporter.acceptsDeclaredSize(20_001, 1))
        assertFalse(MediaImporter.acceptsDeclaredSize(1, 20_001))
        assertFalse(MediaImporter.acceptsDeclaredSize(0, 100))
        assertFalse(MediaImporter.acceptsDeclaredSize(100, -1))
    }

    @Test
    fun theSampleSizeNeverLoadsTheWholeImage() {
        assertEquals(1, MediaImporter.sampleSizeFor(1_000, 800, 640))
        assertEquals(2, MediaImporter.sampleSizeFor(1_280, 900, 640))
        assertEquals(8, MediaImporter.sampleSizeFor(6_000, 4_000, 640))
        assertEquals(1, MediaImporter.sampleSizeFor(300, 200, 640))
    }

    @Test
    fun eachKindHasItsLimit() {
        assertEquals(ImageLimits(640, 64 * 1024), ImageLimits.of(MediaKind.PAYMENT_QR))
        assertEquals(ImageLimits(640, 64 * 1024), ImageLimits.of(MediaKind.PRODUCT_PHOTO))
        assertEquals(ImageLimits(640, 64 * 1024), ImageLimits.of(MediaKind.BUSINESS_LOGO))
        assertEquals(ImageLimits(1_600, 300 * 1024), ImageLimits.of(MediaKind.DOCUMENT_ATTACHMENT))
    }

    private fun importer(maxSourceBytes: Long = MediaImporter.MAX_SOURCE_BYTES) =
        MediaImporter(context.contentResolver, { media }, Dispatchers.IO, maxSourceBytes)

    private fun imported(result: ImageImport): StoredImage =
        (result as? ImageImport.Imported)?.image ?: throw AssertionError("No se importó: $result")

    /** Un JPEG inventado: un fondo y un cuadro, para que tenga algo que comprimir. */
    private fun jpeg(name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(240, 230, 210))
            drawRect(
                width * 0.2f,
                height * 0.2f,
                width * 0.6f,
                height * 0.8f,
                Paint().apply {
                    color =
                        Color.BLACK
                },
            )
        }
        val file = File(inputs, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    /**
     * Un PNG con su firma, una cabecera válida que declara [width] por [height] y un bloque de datos
     * casi vacío: decodificarlo de verdad obligaría a recorrer todos esos píxeles.
     */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        val header = ByteBuffer.allocate(13)
            .putInt(width).putInt(height)
            .put(8).put(2).put(0).put(0).put(0)
            .array()
        chunk(out, "IHDR", header)
        chunk(out, "IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x03, 0x00))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typed = type.toByteArray(Charsets.US_ASCII) + data
        val crc = CRC32().apply { update(typed) }.value
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(typed)
        out.write(ByteBuffer.allocate(4).putInt(crc.toInt()).array())
    }

    /** Los bloques de un WebP, o `null` si no es un WebP bien formado. */
    private fun webpChunks(bytes: ByteArray): List<String>? {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" ||
            String(bytes, 8, 4) != "WEBP"
        ) {
            return null
        }
        val ids = mutableListOf<String>()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size =
                (bytes[offset + 4].toLong() and 0xFF) or
                    ((bytes[offset + 5].toLong() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toLong() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toLong() and 0xFF) shl 24)
            ids += String(bytes, offset, 4)
            offset += (8 + size + (size and 1)).toInt()
        }
        return ids.takeIf { offset == bytes.size }
    }

    private companion object {
        const val SEED = 20_261_108
        const val OPAQUE = 0xFF000000.toInt()
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
