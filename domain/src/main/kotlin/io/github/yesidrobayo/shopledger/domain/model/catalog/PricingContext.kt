package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode

/**
 * Lo que el negocio fija para medir el margen de un precio y sugerir uno, igual para todos sus
 * productos.
 *
 * [defaultTargetMargin] es el margen objetivo del negocio, que vale cuando ni el producto ni su
 * categoría fijan uno; [categoryTargets] es el de cada categoría que fija el suyo, por id. Con
 * [taxMode] en `INCLUDED`, el precio escrito lleva el impuesto de su tasa, de [taxRates] por id, y
 * el margen se mide sin él; un producto sin tasa propia paga [defaultTaxRateId]. [roundingStep] es
 * el paso al que sube un precio sugerido, en unidad menor, y cero no redondea.
 */
data class PricingContext(
    val defaultTargetMargin: BasisPoints,
    val categoryTargets: Map<Long, BasisPoints>,
    val taxMode: TaxMode,
    val defaultTaxRateId: Long?,
    val taxRates: Map<Long, BasisPoints>,
    val roundingStep: Money,
) {
    /**
     * El margen objetivo de un producto: [productTarget], el suyo, si lo fija; si no, el de la
     * categoría [categoryId]; y al final el del negocio.
     */
    fun targetMargin(productTarget: BasisPoints?, categoryId: Long?): BasisPoints =
        productTarget ?: categoryId?.let(categoryTargets::get) ?: defaultTargetMargin

    /**
     * La tasa que lleva el precio de un producto con [productTaxRateId]: la suya, o la del negocio
     * si no tiene. Sin impuesto en el negocio, o con una tasa que ya no existe, ninguna.
     */
    fun taxRate(productTaxRateId: Long?): BasisPoints = when (taxMode) {
        TaxMode.NONE -> BasisPoints.ZERO

        TaxMode.INCLUDED, TaxMode.ADDED ->
            (productTaxRateId ?: defaultTaxRateId)?.let(taxRates::get) ?: BasisPoints.ZERO
    }
}
