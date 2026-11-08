package io.github.yesidrobayo.shopledger.domain.model.tax

import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings

/**
 * Todo lo que muestra la pantalla de impuestos: el modo, la tasa por defecto, si el impuesto de
 * compra es recuperable, y las tasas vigentes y archivadas, cada lista en el orden en que se
 * crearon.
 */
data class TaxSetup(
    val settings: TaxSettings,
    val active: List<TaxRateEntry>,
    val archived: List<TaxRateEntry>,
) {
    /**
     * Si se cobra impuesto pero ninguna tasa queda por defecto: los productos sin tasa propia se
     * venden con impuesto en cero. No bloquea vender, pero la pantalla lo avisa.
     */
    val isMissingDefaultRate: Boolean
        get() = settings.mode != TaxMode.NONE && settings.defaultTaxRateId == null
}
