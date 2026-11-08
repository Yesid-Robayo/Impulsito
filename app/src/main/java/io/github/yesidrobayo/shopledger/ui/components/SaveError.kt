package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R

/**
 * El texto de un guardado que falló, para cualquier pantalla: sin espacio, pide liberar un poco y
 * reintentar, que es lo único que la persona puede hacer; con cualquier otra falla, dice que no se
 * guardó y que lo escrito sigue ahí. En ningún caso quedó nada a medias.
 *
 * Recibe solo si la falla es de espacio, para que el estado de una pantalla no dependa de la lista
 * de fallas de la base.
 */
@StringRes
fun saveErrorText(outOfSpace: Boolean): Int =
    if (outOfSpace) R.string.error_device_storage else R.string.state_error_save
