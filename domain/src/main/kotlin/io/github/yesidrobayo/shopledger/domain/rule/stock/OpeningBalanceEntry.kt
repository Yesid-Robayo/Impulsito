package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState

/**
 * Lo que deja un inventario inicial: [totalCost], lo que vale en conjunto la mercancía que entra, y
 * [after], cómo queda el costo del producto después.
 */
data class OpeningBalanceEntry(val totalCost: Money, val after: CostState)
