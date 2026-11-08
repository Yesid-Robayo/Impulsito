package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/**
 * Cómo se limpian y se miden los textos del catálogo antes de guardarlos.
 *
 * Un nombre se guarda recortado en los extremos, y su forma normalizada es la que decide si dos
 * nombres son el mismo. El largo se cuenta en caracteres, como lo cuenta la base, y no en unidades
 * de UTF-16: un emoji es un carácter aunque ocupe dos.
 */
object CatalogText {
    /** Largo máximo del nombre de un producto. */
    const val PRODUCT_NAME_MAX: Int = 80

    /** Largo máximo del nombre de una categoría. */
    const val CATEGORY_NAME_MAX: Int = 40

    /** Largo máximo de un código de barras. */
    const val BARCODE_MAX: Int = 64

    /**
     * [raw] como nombre de hasta [maxLength] caracteres: recortado, con su forma normalizada. Un
     * nombre que solo tiene espacios o marcas sin letra queda vacío.
     */
    fun name(raw: String, maxLength: Int): CatalogName {
        val name = raw.trim()
        val normalized = TextNormalizer.normalize(name)
        return when {
            normalized.isEmpty() -> CatalogName.Empty
            length(name) > maxLength -> CatalogName.TooLong
            else -> CatalogName.Valid(name, normalized)
        }
    }

    /** [raw] como código de barras: recortado, y `null` si queda en blanco. */
    fun barcode(raw: String?): String? = raw?.let { it.trim().ifEmpty { null } }

    /** Cuántos caracteres tiene [text]: un par de sustitutos de UTF-16 cuenta como uno. */
    fun length(text: String): Int = text.length - text.count { it.isLowSurrogate() }
}
