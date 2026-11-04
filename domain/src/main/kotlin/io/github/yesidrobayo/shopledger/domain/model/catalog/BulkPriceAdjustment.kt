package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * El cálculo de una actualización masiva, con el único dato que pide su modo.
 *
 * Cada modo lleva un dato distinto, y una fila con el dato de otro modo no se podría repetir. Por
 * eso el modo y su dato viajan juntos, y el modo se deduce del tipo.
 */
sealed interface BulkPriceAdjustment {
    /** El modo que se guarda junto a la fila. */
    val mode: BulkPriceMode
        get() = when (this) {
            is Percent -> BulkPriceMode.PERCENT
            is Amount -> BulkPriceMode.AMOUNT
            is TargetMargin -> BulkPriceMode.TARGET_MARGIN
            is ReferenceRate -> BulkPriceMode.REFERENCE_RATE
        }

    /** Cada precio cambia [percent], con signo. */
    data class Percent(val percent: BasisPoints) : BulkPriceAdjustment

    /** Cada precio cambia [delta], con signo, en unidad menor. */
    data class Amount(val delta: Money) : BulkPriceAdjustment

    /** Cada precio se fija para lograr [margin] sobre su costo. */
    data class TargetMargin(val margin: BasisPoints) : BulkPriceAdjustment

    /**
     * Cada precio sale de su precio de referencia por la cotización [fxRateMicro]: unidades
     * enteras de la moneda del libro por una unidad entera de la de referencia, por un millón.
     */
    data class ReferenceRate(val fxRateMicro: Long) : BulkPriceAdjustment
}
