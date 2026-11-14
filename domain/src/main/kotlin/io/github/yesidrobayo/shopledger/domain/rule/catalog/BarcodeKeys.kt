package io.github.yesidrobayo.shopledger.domain.rule.catalog

/**
 * Las formas en que se busca un código leído.
 *
 * Un mismo código UPC-A llega con 12 cifras de un lector y con 13, un cero delante, de otro, que
 * lo lee como EAN-13: son el mismo producto. Por eso un código de 12 cifras también se busca con
 * el cero delante, y uno de 13 que empieza en cero, sin él. Primero va siempre el código tal como
 * llegó, recortado. Cualquier otro código se busca solo tal cual.
 */
object BarcodeKeys {
    /** Las formas de [code] en el orden en que se buscan, o ninguna si está en blanco. */
    fun forLookup(code: String): List<String> {
        val trimmed = CatalogText.barcode(code) ?: return emptyList()
        if (!trimmed.all { it in '0'..'9' }) return listOf(trimmed)
        return when {
            trimmed.length == UPC_A_LENGTH -> listOf(trimmed, "0$trimmed")

            trimmed.length == EAN_13_LENGTH && trimmed.startsWith('0') ->
                listOf(trimmed, trimmed.substring(1))

            else -> listOf(trimmed)
        }
    }

    private const val UPC_A_LENGTH = 12
    private const val EAN_13_LENGTH = 13
}
