package io.github.yesidrobayo.shopledger.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.ui.components.PhotoState
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Las miniaturas de las fotos ya decodificadas, para que una lista de miles de productos no lea el
 * mismo archivo cada vez que una fila vuelve a la pantalla.
 *
 * Cada foto se decodifica fuera del hilo principal, muestreada al tamaño en que se dibuja y sin
 * transparencia, que una foto recodificada no tiene: así una miniatura de fila ocupa decenas de KB
 * y no el MB y medio de la imagen entera. La caché tiene un tope en bytes y suelta las que hace más
 * tiempo no se ven.
 *
 * Solo lee archivos que la app escribió en su carpeta privada, ya recodificados y con tope de
 * tamaño. Un archivo que falta, o que no se puede leer, nunca es un error: la fila de la base sigue
 * y la foto puede llegar después del respaldo. [whenMissing] dice qué mostrar entonces, según el
 * estado del respaldo de fotos.
 */
class PhotoCache(
    private val directory: () -> File,
    private val io: CoroutineDispatcher,
    maxBytes: Int = DEFAULT_MAX_BYTES,
    private val whenMissing: suspend (sha256: String) -> PhotoState = { PhotoState.Unavailable },
) {
    private val memory =
        object : LruCache<String, ImageBitmap>(maxBytes) {
            override fun sizeOf(key: String, value: ImageBitmap): Int =
                value.width * value.height * BYTES_PER_PIXEL
        }

    /** La miniatura ya decodificada, sin tocar el disco, o `null` si hay que leerla. */
    fun peek(sha256: String, sidePx: Int): PhotoState.Ready? =
        memory.get(keyOf(sha256, sidePx))?.let(PhotoState::Ready)

    /** La foto [sha256] para dibujarla con [sidePx] de lado, leída de su archivo si hace falta. */
    suspend fun load(sha256: String, sidePx: Int): PhotoState {
        peek(sha256, sidePx)?.let { return it }
        val bitmap =
            withContext(io) {
                val file = File(directory(), StoredImage.fileNameOf(sha256))
                if (file.isFile) decode(file, sidePx) else null
            } ?: return whenMissing(sha256)
        memory.put(keyOf(sha256, sidePx), bitmap)
        return PhotoState.Ready(bitmap)
    }

    /** Suelta la miniatura de [sha256], por ejemplo porque su archivo acaba de llegar. */
    fun forget(sha256: String) {
        memory.snapshot().keys.filter { it.startsWith("$sha256@") }.forEach(memory::remove)
    }

    /** Lee [file] muestreado para que su lado menor quede apenas sobre [sidePx]. */
    private fun decode(file: File, sidePx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sidePx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }

    companion object {
        /** Unas 250 miniaturas de fila o 25 de detalle. Es una estimación. */
        const val DEFAULT_MAX_BYTES: Int = 8 * 1024 * 1024

        /** Sin transparencia: 5 bits de rojo, 6 de verde y 5 de azul. */
        private const val BYTES_PER_PIXEL = 2

        private fun keyOf(sha256: String, sidePx: Int) = "$sha256@$sidePx"

        /**
         * La mayor potencia de 2 que deja el lado menor en [sidePx] o más: la miniatura se recorta
         * al centro, así que es el lado menor el que debe llenar el cuadro.
         */
        fun sampleSizeFor(width: Int, height: Int, sidePx: Int): Int {
            val shortSide = minOf(width, height)
            var sample = 1
            while (shortSide / (sample * 2) >= max(sidePx, 1)) sample *= 2
            return sample
        }
    }
}
