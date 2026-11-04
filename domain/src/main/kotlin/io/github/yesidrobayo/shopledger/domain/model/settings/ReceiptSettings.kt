package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Lo que lleva el comprobante que entrega el negocio, todo escrito por la persona y opcional.
 *
 * - [logoMediaId] es la imagen del logo. Al borrar la imagen, el comprobante queda sin logo.
 * - [businessDetails] y [footerText] son textos libres de hasta 300 caracteres.
 * - [fiscalPortalUrl] es la dirección que la persona usa para sus trámites, de hasta 300
 *   caracteres. La app no la visita: solo la abre en otra app cuando la persona la toca.
 */
data class ReceiptSettings(
    val logoMediaId: Long?,
    val businessDetails: String?,
    val footerText: String?,
    val fiscalPortalUrl: String?,
)
