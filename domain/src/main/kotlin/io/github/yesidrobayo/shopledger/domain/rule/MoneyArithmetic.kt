package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.map

/**
 * El monto de [quantity] a este precio por unidad entera: `precio × milésimas / 1000`, redondeado.
 *
 * Un precio o un costo siempre es por una unidad entera, nunca por milésima: 0,250 kg a 12500 por
 * kilogramo dan 3125. La unidad menor no tiene fracciones, así que un producto barato que se vende
 * por peso se lleva mejor en kilogramos que en gramos.
 */
fun Money.timesQuantity(quantity: QuantityMilli): Checked<Money> =
    mulDivRound(minor, quantity.milli, Scale.MILLI).map(::Money)

/** Igual que [timesQuantity], y rechaza con [Checked.AboveLimit] un monto mayor que [limit]. */
fun Money.timesQuantity(quantity: QuantityMilli, limit: Limit): Checked<Money> =
    mulDivRound(minor, quantity.milli, Scale.MILLI, limit).map(::Money)

/** La parte [rate] de este monto, redondeada: el 12,50 % de 12345 es 1543. */
fun Money.timesRate(rate: BasisPoints): Checked<Money> =
    mulDivRound(minor, rate.bp, Scale.BASIS_POINTS).map(::Money)

/** Igual que [timesRate], y rechaza con [Checked.AboveLimit] un monto mayor que [limit]. */
fun Money.timesRate(rate: BasisPoints, limit: Limit): Checked<Money> =
    mulDivRound(minor, rate.bp, Scale.BASIS_POINTS, limit).map(::Money)
