package io.github.yesidrobayo.shopledger.data.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.ImageImporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlinx.coroutines.withContext

/**
 * Trae una imagen que eligió la persona y la guarda recodificada en `filesDir/media/<sha256>.webp`.
 *
 * La imagen viene de otra app, así que es entrada no confiable:
 *
 * - **Lectura con tope.** Se leen como máximo [maxSourceBytes]; más que eso se rechaza sin
 *   decodificar. Se lee una vez entero para medirlo, porque el decodificador puede no llegar al
 *   final.
 * - **Primero las medidas, después una decodificación acotada.** Se decodifica con un muestreo que
 *   deja el lado mayor apenas sobre el tope, así nunca se carga la imagen entera en memoria.
 * - **Siempre se recodifica a WebP,** que no copia EXIF ni ubicación. El giro que indica EXIF se
 *   aplica antes, para que la imagen se vea derecha sin conservar la etiqueta.
 * - **El original nunca se guarda:** se lee del documento que dio el selector, cuatro veces, y no
 *   se copia a ningún archivo. Solo se escribe el WebP, con un temporal y un renombrado para que
 *   nunca quede uno a medias.
 *
 * El nombre del archivo es la suma de su contenido: dos imágenes iguales son un solo archivo, y un
 * archivo nunca se reemplaza. No registra nada: ni la dirección del documento, ni su contenido.
 */
internal class MediaImporter(
    private val resolver: ContentResolver,
    private val mediaDirectory: () -> File,
    private val ioContext: CoroutineContext,
    private val maxSourceBytes: Long = MAX_SOURCE_BYTES,
) : ImageImporter {
    override suspend fun import(source: String, kind: MediaKind): ImageImport =
        withContext(ioContext) {
            val uri = Uri.parse(source)
            val limits = ImageLimits.of(kind)
            val encoded =
                try {
                    recode(uri, limits)
                } catch (unreadable: IOException) {
                    null
                } catch (denied: SecurityException) {
                    // El permiso de lectura del documento venció, o nunca se dio.
                    null
                } catch (tooBig: OutOfMemoryError) {
                    // Una imagen hostil que aun muestreada no cabe en memoria.
                    null
                }
            if (encoded == null) ImageImport.Unreadable else store(encoded)
        }

    /** La imagen recodificada en memoria, o `null` si no es una imagen que se pueda leer. */
    private fun recode(uri: Uri, limits: ImageLimits): EncodedImage? {
        // El decodificador puede dejar de leer antes del final: el tope se mide leyendo todo una vez.
        open(uri).use(::readToEnd)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = open(uri).use(::orientationOf)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, limits.maxLongSide)
            }
        val sampled = open(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        try {
            return encodeWithin(sampled, orientation, limits)
        } finally {
            sampled.recycle()
        }
    }

    /**
     * Ajusta, gira y codifica [source] hasta que quepa en [limits]: baja la calidad, y si ni así cabe,
     * reduce la imagen. Una imagen que no cabe ni muy pequeña no es una imagen razonable.
     */
    private fun encodeWithin(source: Bitmap, orientation: Int, limits: ImageLimits): EncodedImage? {
        var longSide = limits.maxLongSide
        while (longSide >= MIN_LONG_SIDE) {
            val shaped = shape(source, orientation, longSide)
            try {
                for (quality in WEBP_QUALITIES) {
                    val bytes = ByteArrayOutputStream()
                    shaped.compress(webpFormat(), quality, bytes)
                    val data = bytes.toByteArray()
                    if (data.isNotEmpty() && data.size <= limits.maxBytes) {
                        return EncodedImage(data, shaped.width, shaped.height)
                    }
                }
            } finally {
                if (shaped !== source) shaped.recycle()
            }
            longSide = longSide * SHRINK_NUMERATOR / SHRINK_DENOMINATOR
        }
        return null
    }

    /** [source] reducido para que su lado mayor quepa en [longSide], y girado como dice EXIF. */
    private fun shape(source: Bitmap, orientation: Int, longSide: Int): Bitmap {
        val sourceLong = max(source.width, source.height)
        val scale = if (sourceLong > longSide) longSide.toFloat() / sourceLong else 1f
        val matrix = orientationMatrix(orientation)
        if (scale == 1f && matrix.isIdentity) return source
        matrix.preScale(scale, scale)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Guarda [encoded] con el nombre de su suma, si no estaba ya. */
    private fun store(encoded: EncodedImage): ImageImport {
        val sha256 = sha256Of(encoded.bytes)
        val image =
            StoredImage(
                sha256 = sha256,
                mimeType = WEBP_MIME_TYPE,
                widthPx = encoded.width,
                heightPx = encoded.height,
                sizeBytes = encoded.bytes.size.toLong(),
            )
        val directory = mediaDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) return ImageImport.Unreadable
        val target = File(directory, image.fileName)
        // Mismo nombre es mismo contenido: no se reescribe nunca.
        if (target.isFile && target.length() == image.sizeBytes) return ImageImport.Imported(image)
        if (directory.usableSpace < encoded.bytes.size.toLong() * SPACE_MARGIN) {
            return ImageImport.OutOfSpace
        }
        val temporary = File(directory, image.fileName + TEMPORARY_SUFFIX)
        return try {
            temporary.outputStream().use { output ->
                output.write(encoded.bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException()
            ImageImport.Imported(image)
        } catch (failed: IOException) {
            // Sin espacio, la persona puede liberar y reintentar; otra falla del disco es como no
            // poder leerla: se elige otra.
            if (directory.usableSpace < encoded.bytes.size) {
                ImageImport.OutOfSpace
            } else {
                ImageImport.Unreadable
            }
        } finally {
            temporary.delete()
        }
    }

    /** Lee [stream] hasta el final sin guardar nada: solo para que su tope se cumpla. */
    private fun readToEnd(stream: InputStream) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (stream.read(buffer) >= 0) {
            // Solo se cuenta lo leído.
        }
    }

    private fun open(uri: Uri): InputStream {
        val stream = resolver.openInputStream(uri) ?: throw IOException()
        return BoundedInputStream(stream, maxSourceBytes)
    }

    private class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

    internal companion object {
        /**
         * Tope de lo que se lee del documento elegido. Es una estimación: una foto de 50 megapíxeles
         * en JPEG ronda la mitad.
         */
        const val MAX_SOURCE_BYTES: Long = 32L * 1024 * 1024

        const val WEBP_MIME_TYPE = "image/webp"

        private const val TEMPORARY_SUFFIX = ".tmp"

        private const val READ_BUFFER_BYTES = 64 * 1024

        /** Calidades que se prueban, de mayor a menor, hasta que la imagen quepa. */
        private val WEBP_QUALITIES = listOf(80, 70, 60, 50, 40, 30)

        /** Si ni la menor calidad cabe, la imagen se reduce a tres cuartos y se prueba otra vez. */
        private const val SHRINK_NUMERATOR = 3
        private const val SHRINK_DENOMINATOR = 4

        /** Por debajo de este lado mayor ya no se reduce más. */
        private const val MIN_LONG_SIDE = 64

        /** Espacio libre que se pide, en veces el tamaño del archivo, antes de escribir. */
        private const val SPACE_MARGIN = 2L

        /**
         * La mayor potencia de 2 que, al decodificar, deja el lado mayor todavía en [maxLongSide] o
         * más. Así la decodificación nunca carga la imagen entera, y el ajuste final solo reduce.
         */
        fun sampleSizeFor(width: Int, height: Int, maxLongSide: Int): Int {
            val longSide = max(width, height)
            var sample = 1
            while (longSide / (sample * 2) >= maxLongSide) sample *= 2
            return sample
        }

        /** La transformación que deja derecha una imagen con la orientación EXIF [orientation]. */
        fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)

                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(HALF_TURN)

                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    setRotate(HALF_TURN)
                    postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(QUARTER_TURN)
                    postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(QUARTER_TURN)

                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-QUARTER_TURN)
                    postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-QUARTER_TURN)

                else -> Unit
            }
        }

        private const val QUARTER_TURN = 90f
        private const val HALF_TURN = 180f

        fun sha256Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
            }

        private const val BYTE_MASK = 0xFF
        private const val HEX_RADIX = 16

        /** La orientación EXIF de la imagen, o normal si no trae o no se puede leer. */
        private fun orientationOf(stream: InputStream): Int = try {
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (unreadable: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

        private fun webpFormat(): Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                legacyWebpFormat()
            }

        // Antes de Android 11, WEBP con calidad menor que 100 es WebP con pérdida.
        @Suppress("DEPRECATION")
        private fun legacyWebpFormat(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP
    }
}

/** Un flujo que falla con [IOException] si se lee más de [limit] bytes. */
private class BoundedInputStream(stream: InputStream, private val limit: Long) :
    FilterInputStream(stream) {
    private var read = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) count(count.toLong())
        return count
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count(skipped)
        return skipped
    }

    private fun count(bytes: Long) {
        read += bytes
        if (read > limit) throw IOException()
    }
}
