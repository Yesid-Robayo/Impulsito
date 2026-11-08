package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Para qué se guardó una imagen. Es un valor guardado en la base: su nombre no cambia nunca.
 *
 * Decide el tamaño al recodificarla: un comprobante debe poder leerse, y una foto de producto, un
 * código de cobro o un logo solo deben reconocerse.
 */
enum class MediaKind {
    /** La foto de un producto. */
    PRODUCT_PHOTO,

    /** La foto de un comprobante, adjunta a un documento. */
    DOCUMENT_ATTACHMENT,

    /** La imagen con los datos de cobro de un método de pago, como un código QR. */
    PAYMENT_QR,

    /** El logo del negocio en los comprobantes. */
    BUSINESS_LOGO,
}
