package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Ajustes de la venta. [defaultRoundingStep] es el paso al que se redondean los precios
 * sugeridos, en unidad menor, y cero es sin redondeo: ningún paso depende de una moneda.
 * [tipsEnabled] ofrece propina al cobrar.
 */
data class SaleSettings(val defaultRoundingStep: Money, val tipsEnabled: Boolean)
