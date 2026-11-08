package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind

/**
 * Cuánto mide como máximo una imagen ya recodificada, según para qué se guarda: lado mayor en
 * píxeles y tamaño del archivo. Producto, código de cobro y logo solo deben reconocerse; un
 * comprobante debe poder leerse.
 */
internal data class ImageLimits(val maxLongSide: Int, val maxBytes: Int) {
    companion object {
        private const val KIB = 1_024

        /** Lado mayor de una foto de producto, un código de cobro o un logo. */
        private const val SMALL_LONG_SIDE = 640

        /** Tamaño máximo de una foto de producto, un código de cobro o un logo. */
        private const val SMALL_BYTES = 64 * KIB

        /** Lado mayor de un comprobante. */
        private const val DOCUMENT_LONG_SIDE = 1_600

        /** Tamaño máximo de un comprobante. */
        private const val DOCUMENT_BYTES = 300 * KIB

        fun of(kind: MediaKind): ImageLimits = when (kind) {
            MediaKind.PRODUCT_PHOTO,
            MediaKind.PAYMENT_QR,
            MediaKind.BUSINESS_LOGO,
            -> ImageLimits(SMALL_LONG_SIDE, SMALL_BYTES)

            MediaKind.DOCUMENT_ATTACHMENT -> ImageLimits(DOCUMENT_LONG_SIDE, DOCUMENT_BYTES)
        }
    }
}
