package io.github.yesidrobayo.shopledger.ui.document

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Saca el texto de un PDF con el sistema, que solo lo sabe hacer desde Android 15. Vive aparte de
 * las pruebas que lo usan: así una versión anterior carga esas clases sin tocar las de Android 15,
 * y la prueba se salta con su suposición.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
object PdfPageText {
    /** El texto de la primera página de [file]. */
    fun firstPage(file: File): String =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page -> page.textContents.joinToString(" ") { it.text } }
            }
        }
}
