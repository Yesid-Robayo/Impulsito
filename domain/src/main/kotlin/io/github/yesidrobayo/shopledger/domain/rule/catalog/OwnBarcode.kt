package io.github.yesidrobayo.shopledger.domain.rule.catalog

/**
 * El código propio de un producto que no trae uno de fábrica: `P` y su id con 8 cifras, como
 * `P00000123`. Se imprime en Code 128.
 *
 * La `P` lo separa de los códigos de fábrica, que son solo cifras, y el id lo hace único dentro de
 * la app. Aun así, otro producto pudo haberlo recibido a mano, así que quien lo genera comprueba
 * que esté libre antes de guardarlo. Un id de más de 8 cifras se escribe entero.
 */
object OwnBarcode {
    fun forProduct(productId: Long): String {
        require(productId > 0) { "El id de un producto guardado es positivo" }
        return PREFIX + productId.toString().padStart(DIGITS, '0')
    }

    private const val PREFIX = "P"
    private const val DIGITS = 8
}
