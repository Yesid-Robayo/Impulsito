package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage

/** Qué pasa con el logo del comprobante al guardar la plantilla. */
sealed interface ReceiptLogoChange {
    /** Queda el que había, o ninguno. */
    data object Keep : ReceiptLogoChange

    /** Se quita: el comprobante sale sin logo. */
    data object Remove : ReceiptLogoChange

    /** Se usa [image], ya recodificada y sin metadatos. */
    data class Use(val image: StoredImage) : ReceiptLogoChange
}

/**
 * La plantilla del comprobante tal como la escribió la persona: el logo y dos textos libres. Un
 * texto en blanco se guarda como ninguno. La leyenda de que no es una factura no está aquí: no se
 * escribe ni se quita.
 */
data class ReceiptTemplateInput(
    val logo: ReceiptLogoChange,
    val businessDetails: String,
    val footerText: String,
)

/** Los textos de la plantilla, para decir cuál pasa del largo permitido. */
enum class ReceiptTemplateField {
    /** `receipt_business_details`: dirección, contacto o lo que la persona quiera mostrar. */
    BUSINESS_DETAILS,

    /** `receipt_footer_text`: el pie, como un agradecimiento. */
    FOOTER,
}
