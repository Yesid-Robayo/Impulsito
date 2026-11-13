package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.sale.PaymentRejection
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.money.TransferRules
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ReferenceRateRules

/**
 * Un pago recibido en otra moneda. La venta sigue en la moneda del negocio, y el pago también: lo
 * recibido se pasa a ella con la cotización escrita por la persona, y se guarda aparte.
 *
 * ```text
 * amount = mulDivRound(tender_amount × 10^currency_decimals, fx_rate_micro, 10^tender_currency_decimals × 1000000)
 * ```
 *
 * Con un libro de 0 decimales, `2000` de una moneda de 2 decimales a 4150,50 son `83010`. Es la
 * misma cuenta que una transferencia entre monedas y que la moneda de referencia.
 */
object ForeignTenderRules {
    /** Por qué [tender] no se puede guardar, sin leer la base, o `null` si su forma vale. */
    fun shapeRejection(tender: ForeignTender): PaymentRejection? = when {
        tender.decimals !in 0..ReferenceRateRules.MAX_DECIMALS -> PaymentRejection.INVALID_FOREIGN

        tender.amountMinor < 1L || !Limit.DOCUMENT_TOTAL.allows(tender.amountMinor) ->
            PaymentRejection.INVALID_FOREIGN

        !ReferenceRateRules.isValidRate(tender.rateMicro) -> PaymentRejection.INVALID_FOREIGN

        else -> null
    }

    /**
     * [tender] en la moneda del negocio, [business], que tiene [bookDecimals], o `null` si es la
     * misma moneda del negocio, o si no cabe en el tope de un documento.
     */
    fun toBook(tender: ForeignTender, business: CurrencyCode, bookDecimals: Int): Money? {
        if (tender.currency == business) return null
        return TransferRules.convert(
            Money(tender.amountMinor),
            tender.decimals,
            bookDecimals,
            tender.rateMicro,
        ).valueOrNull()
    }
}
