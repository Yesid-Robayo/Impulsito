package io.github.yesidrobayo.shopledger.data.print

/**
 * Imagen de un solo bit por punto, lista para la impresora: cada punto es negro o blanco, sin
 * grises. Es Kotlin puro para que el codificador se pruebe en la JVM, sin Android.
 *
 * @property width ancho en puntos de la impresora.
 * @property height alto en puntos.
 */
class MonochromeRaster(val width: Int, val height: Int, private val black: BooleanArray) {
    init {
        require(width > 0) { "El ancho debe ser positivo" }
        require(height > 0) { "El alto debe ser positivo" }
        require(black.size == width * height) { "Faltan o sobran puntos para el ancho y el alto" }
    }

    /** Bytes por fila en el formato de la impresora: 8 puntos por byte, con relleno al final. */
    val bytesPerRow: Int get() = (width + 7) / 8

    fun isBlack(x: Int, y: Int): Boolean = black[y * width + x]

    companion object {
        /**
         * Construye la imagen desde filas de texto, donde `#` es negro y cualquier otro carácter
         * es blanco. Sirve para escribir pruebas legibles.
         */
        fun fromRows(rows: List<String>): MonochromeRaster {
            require(rows.isNotEmpty()) { "Hace falta al menos una fila" }
            val width = rows.first().length
            require(rows.all { it.length == width }) { "Las filas deben tener el mismo ancho" }
            val black = BooleanArray(width * rows.size)
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, char -> black[y * width + x] = char == '#' }
            }
            return MonochromeRaster(width, rows.size, black)
        }
    }
}
