package io.github.yesidrobayo.shopledger.domain.model.label

/** Qué lleva cada etiqueta. Ninguna lleva costo ni margen: es papel que sale del negocio. */
enum class LabelTemplate(val showsPrice: Boolean, val showsCode: Boolean) {
    NAME_PRICE(showsPrice = true, showsCode = false),
    NAME_PRICE_CODE(showsPrice = true, showsCode = true),
    CODE_ONLY(showsPrice = false, showsCode = true),
}
