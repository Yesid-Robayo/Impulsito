package io.github.yesidrobayo.shopledger.ui.payment

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.ui.components.PhotoState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La imagen guardada [image], decodificada fuera del hilo principal desde su archivo, o `null`
 * mientras se lee. Si el archivo falta o no se puede leer, queda como no disponible, sin aviso: la
 * fila de la base sigue, y la imagen puede llegar después del respaldo.
 *
 * Solo lee archivos que la app escribió, ya recodificados y con tope de tamaño.
 */
@Composable
fun rememberStoredPhoto(image: StoredImage): PhotoState? {
    val context = LocalContext.current
    val state by produceState<PhotoState?>(initialValue = null, image.sha256) {
        value =
            withContext(Dispatchers.IO) {
                val file = File(File(context.filesDir, StoredImage.FOLDER), image.fileName)
                BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.let(PhotoState::Ready)
                    ?: PhotoState.Unavailable
            }
    }
    return state
}
