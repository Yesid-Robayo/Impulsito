package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode

/**
 * El impuesto de una línea y la base sin impuesto de un precio.
 *
 * La app no conoce ningún impuesto: el modo, la tasa y su nombre los escribe la persona, y aquí
 * solo se reparte un monto con esa tasa. El impuesto se calcula línea por línea, nunca sobre el
 * total del documento: así el total de una venta es siempre la suma de sus netos más la de sus
 * impuestos, y cada línea guarda lo que se cobró de verdad.
 *
 * Una tasa fuera de 0 a 100 % es un error de programación: la base no guarda otra, y la pantalla la
 * rechaza antes.
 */
object TaxCalculator {
    /**
     * Reparte [lineAfter], el monto de la línea tras su descuento y su parte del ajuste de toda la
     * venta, entre neto e impuesto, según [mode] y [rate].
     *
     * - Sin impuesto, todo es neto y [rate] no cuenta.
     * - Con el impuesto incluido, [lineAfter] es lo que se cobra: el neto sale hacia atrás,
     *   `lineAfter × 10000 / (10000 + tasa)` al entero más cercano, y el impuesto es la diferencia.
     *   Así el total es exactamente el precio escrito.
     * - Con el impuesto añadido, [lineAfter] es el neto y el impuesto se suma:
     *   `neto × tasa / 10000` al entero más cercano.
     *
     * Como el impuesto sale de la línea ya ajustada, un descuento o un recargo de toda la venta
     * paga impuesto en proporción.
     *
     * Devuelve [Checked.AboveLimit] si el total de la línea pasa del total máximo de un documento:
     * con el impuesto añadido, una línea al tope deja de caber. Devuelve [Checked.Overflow] si ni
     * siquiera cabe en `Long`.
     */
    fun line(lineAfter: Money, mode: TaxMode, rate: BasisPoints): Checked<LineTax> {
        requireRate(rate)
        return when (mode) {
            TaxMode.NONE ->
                chargedAsWritten(lineAfter) {
                    Checked.Value(LineTax(lineAfter, Money.ZERO, lineAfter))
                }

            TaxMode.INCLUDED -> chargedAsWritten(lineAfter) { included(lineAfter, rate) }

            // neto + redondear(neto × tasa / 10000) es redondear(neto × (10000 + tasa) / 10000):
            // el neto es entero y tiene el signo del impuesto, así que sumarlo antes o después de
            // redondear da lo mismo. Calculado así, el total pasa por el tope y por los 128 bits
            // en una sola cuenta, y el impuesto, la diferencia, nunca se desborda.
            TaxMode.ADDED ->
                mulDivRound(
                    lineAfter.minor,
                    Scale.BASIS_POINTS + rate.bp,
                    Scale.BASIS_POINTS,
                    Limit.DOCUMENT_TOTAL,
                ).map { total -> LineTax(lineAfter, Money(total) - lineAfter, Money(total)) }
        }
    }

    /**
     * El precio de catálogo sin impuesto, que es la base del margen de un producto.
     *
     * Solo con el impuesto incluido el precio escrito lleva impuesto, y la base sale hacia atrás:
     * `precio × 10000 / (10000 + tasa)`, al entero más cercano. Sin impuesto, o con el impuesto
     * sumado al cobrar, el precio escrito ya es la base. Quien mide el margen sobre un precio con
     * impuesto incluido cree ganar más de lo que gana.
     *
     * El resultado nunca supera al precio, así que nunca se rechaza: el tipo [Checked] es el de
     * toda división de un producto.
     */
    fun catalogNetPrice(salePrice: Money, mode: TaxMode, rate: BasisPoints): Checked<Money> {
        requireRate(rate)
        return when (mode) {
            TaxMode.NONE, TaxMode.ADDED -> Checked.Value(salePrice)
            TaxMode.INCLUDED -> netOf(salePrice, rate).map(::Money)
        }
    }

    /**
     * Una línea que se cobra tal como está escrita: sin impuesto, o con el impuesto dentro. Su
     * total es [charged], y se rechaza si pasa del total máximo de un documento.
     */
    private fun chargedAsWritten(charged: Money, split: () -> Checked<LineTax>): Checked<LineTax> =
        if (Limit.DOCUMENT_TOTAL.allows(charged.minor)) {
            split()
        } else {
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL)
        }

    /**
     * El neto de un monto con el impuesto incluido. Nunca se rechaza: el neto no supera al monto.
     * El impuesto es la resta, y tampoco se desborda: tiene el signo del monto y es menor que él.
     */
    private fun included(charged: Money, rate: BasisPoints): Checked<LineTax> =
        netOf(charged, rate).map { net -> LineTax(Money(net), charged - Money(net), charged) }

    private fun netOf(charged: Money, rate: BasisPoints): Checked<Long> =
        mulDivRound(charged.minor, Scale.BASIS_POINTS, Scale.BASIS_POINTS + rate.bp)

    private fun requireRate(rate: BasisPoints) {
        require(rate.bp in 0L..Scale.BASIS_POINTS) { "La tasa de impuesto va de 0 a 100 %" }
    }
}
