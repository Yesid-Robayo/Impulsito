package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols

/**
 * Cómo se leen los números del formulario: los símbolos del formato regional del negocio para los
 * montos y para las cantidades, y los decimales de su moneda. La pantalla los da desde sus
 * formateadores, así el ViewModel lee lo escrito igual que lo muestran los campos.
 */
data class ProductNumberFormat(
    val moneySymbols: NumberSymbols,
    val currencyDecimals: Int,
    val quantitySymbols: NumberSymbols,
)
