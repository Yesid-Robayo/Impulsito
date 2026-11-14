package io.github.yesidrobayo.shopledger.data.print

import android.graphics.Bitmap
import io.github.yesidrobayo.shopledger.domain.rule.print.MonochromeThreshold

/**
 * Pasa un `Bitmap` a un bit por punto con [MonochromeThreshold], la misma regla con la que se
 * dibuja la etiqueta. Un `Bitmap` que ya es blanco y negro queda idéntico.
 */
fun Bitmap.toMonochromeRaster(): MonochromeRaster {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val black = BooleanArray(pixels.size) { MonochromeThreshold.isDark(pixels[it]) }
    return MonochromeRaster(width, height, black)
}
