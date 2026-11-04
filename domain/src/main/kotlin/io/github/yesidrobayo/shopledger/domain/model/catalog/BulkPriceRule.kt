package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que una actualización masiva guarda en cada fila del historial: su cálculo y su redondeo.
 *
 * [roundingStep] es el paso al que se redondea cada precio nuevo, en unidad menor; cero es sin
 * redondeo. Con esto la actualización se puede explicar y repetir después, fila por fila.
 */
data class BulkPriceRule(val adjustment: BulkPriceAdjustment, val roundingStep: Money)
