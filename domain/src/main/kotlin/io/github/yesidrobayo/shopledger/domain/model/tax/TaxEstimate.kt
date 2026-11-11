package io.github.yesidrobayo.shopledger.domain.model.tax

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * El impuesto cobrado y pagado en un período: una cifra orientativa, nunca una declaración.
 *
 * [collected] es lo cobrado en ventas menos lo devuelto en devoluciones, y [paid] lo pagado en
 * compras. La app no resta una cifra de la otra, no calcula un saldo a pagar ni conoce plazos o
 * autoridades: no sabe qué impuesto es, ni qué parte se puede descontar. Quien la muestra la
 * rotula como estimación y dice que no es una declaración.
 */
data class TaxEstimate(val collected: Money, val paid: Money)
