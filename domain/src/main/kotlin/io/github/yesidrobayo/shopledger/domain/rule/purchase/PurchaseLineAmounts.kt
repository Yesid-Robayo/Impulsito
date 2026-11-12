package io.github.yesidrobayo.shopledger.domain.rule.purchase

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Los dos valores de una línea de compra, sin impuesto aparte: [unitCost], por unidad entera, y
 * [written], el total de la línea en la forma en que se escriben los montos del negocio.
 */
data class PurchaseLineAmounts(val unitCost: Money, val written: Money)
