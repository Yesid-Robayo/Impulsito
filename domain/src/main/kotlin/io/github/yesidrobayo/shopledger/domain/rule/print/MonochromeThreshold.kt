package io.github.yesidrobayo.shopledger.domain.rule.print

/**
 * La única regla que decide si un color es negro o blanco al pasar a un bit. La usan el dibujo de
 * la etiqueta en la interfaz y la conversión a ESC/POS en los datos, para que coincidan punto por
 * punto: la vista previa es lo que sale en papel.
 */
object MonochromeThreshold {
    /** Por debajo de este brillo, sobre 255, el punto se imprime. */
    const val LUMINANCE_CUTOFF = 128

    /**
     * Compone el color ARGB sobre papel blanco y compara su brillo con el corte. Usa los pesos de
     * Rec. 601 en enteros, sin coma flotante, para que dé lo mismo en cualquier dispositivo.
     */
    fun isDark(argb: Int): Boolean {
        val alpha = (argb ushr 24) and 0xFF
        val red = composeOverWhite((argb shr 16) and 0xFF, alpha)
        val green = composeOverWhite((argb shr 8) and 0xFF, alpha)
        val blue = composeOverWhite(argb and 0xFF, alpha)
        val luminance = (red * 299 + green * 587 + blue * 114) / 1000
        return luminance < LUMINANCE_CUTOFF
    }

    private fun composeOverWhite(channel: Int, alpha: Int): Int =
        (channel * alpha + 255 * (255 - alpha)) / 255
}
