package io.github.yesidrobayo.shopledger.ui.document

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.moneyText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import kotlinx.datetime.YearMonth

/**
 * El resumen de los pendientes de facturar como texto, para compartirlo por el selector del
 * sistema: el período, cuántas ventas y su total, una línea por venta con su número, fecha y
 * total, y la nota de que es para uso interno y no es una factura ni un documento fiscal.
 *
 * Sale de [FiscalSale], que no tiene costo, margen ni ganancia: este texto no puede llevarlos. No
 * genera ningún archivo con forma de documento fiscal. Los textos salen de los recursos en el
 * idioma de la interfaz, y los números del formato regional, como en pantalla.
 */
internal object FiscalSummaryText {
    fun of(
        month: YearMonth,
        sales: List<FiscalSale>,
        total: Money?,
        resources: Resources,
        formats: AppFormats,
    ): String {
        val chain = CurrencySymbolFonts.chain(resources)

        fun money(amount: Money): String {
            val parts = formats.money.parts(amount)
            return moneyText(parts, chain.resolve(parts.symbol, parts.currencyCode)).text
        }

        fun number(value: Long): String =
            formats.quantity.format(QuantityMilli.ofUnits(value), allowsFraction = false)

        val period =
            resources.getString(R.string.fiscal_share_period, formats.date.monthYear(month))
        val summary = resources.getString(
            R.string.fiscal_share_total,
            number(sales.size.toLong()),
            total?.let(::money) ?: NO_VALUE_TEXT,
        )
        val lines = sales.map { sale ->
            resources.getString(
                R.string.fiscal_share_line,
                number(sale.saleId),
                formats.date.dayMonth(sale.businessDate),
                money(sale.total),
            )
        }
        val note = resources.getString(R.string.fiscal_share_note)
        return (listOf(period, summary) + lines + note).joinToString("\n")
    }
}
