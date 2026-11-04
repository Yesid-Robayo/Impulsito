package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Un tope de ingresos que la persona quiere vigilar: [amount] en unidad menor, sumado en los
 * últimos [months] meses, de 1 a 120. Lo escribe ella: la app no conoce ningún tope legal.
 */
data class RevenueLimit(val amount: Money, val months: Int)
