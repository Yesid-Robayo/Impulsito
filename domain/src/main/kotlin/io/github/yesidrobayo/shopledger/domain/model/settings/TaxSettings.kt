package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Cómo se calculan los impuestos del negocio.
 *
 * [defaultTaxRateId] es la tasa de los productos sin tasa propia, o `null` si no hay. Al borrar esa
 * tasa, el ajuste queda sin ella. Con [purchaseTaxRecoverable], el impuesto de una compra no entra
 * en el costo del producto.
 */
data class TaxSettings(
    val mode: TaxMode,
    val defaultTaxRateId: Long?,
    val purchaseTaxRecoverable: Boolean,
)
