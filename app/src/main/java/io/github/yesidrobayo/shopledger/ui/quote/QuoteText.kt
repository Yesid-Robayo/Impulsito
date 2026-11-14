package io.github.yesidrobayo.shopledger.ui.quote

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.format.IcuTextNormalizer
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.moneyText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats

/**
 * La cotización como texto, para compartirla con el cliente: su número, la fecha, la vigencia, una
 * línea por producto, el total y la leyenda de que no es un comprobante de venta ni una factura.
 *
 * Sale de [QuoteDetail], que no tiene costo, margen ni ganancia: este texto no puede llevarlos.
 * Los textos salen de los recursos en el idioma de la interfaz, y los números del formato
 * regional.
 */
internal object QuoteText {
    fun of(quote: QuoteDetail, resources: Resources, formats: AppFormats): String {
        val chain = CurrencySymbolFonts.chain(resources)

        fun money(amount: Money): String {
            val parts = formats.money.parts(amount)
            return moneyText(parts, chain.resolve(parts.symbol, parts.currencyCode)).text
        }

        val number =
            formats.quantity.format(QuantityMilli.ofUnits(quote.id), allowsFraction = false)
        val heading = buildList {
            add(resources.getString(R.string.quote_doc_title, number))
            add(formats.date.longDate(quote.businessDate))
            quote.customerName?.let(::add)
            quote.validUntil?.let {
                val until = formats.date.longDate(it)
                add(resources.getString(R.string.quote_doc_valid_until, until))
            }
        }
        val lines = quote.lines.map { line ->
            resources.getString(
                R.string.share_receipt_sale_line,
                quantityOf(line, resources, formats),
                line.name,
                money(line.total),
            )
        }
        val total = resources.getString(R.string.share_receipt_total, money(quote.totals.total))
        val legend = resources.getString(R.string.quote_doc_legend)
        // Un renglón en blanco entre el encabezado, las líneas y el cierre.
        return listOf(heading, lines, listOf(total, legend))
            .filter { it.isNotEmpty() }
            .joinToString(SECTION_BREAK) { it.joinToString(LINE_BREAK) }
    }

    /** La cantidad con su unidad, como en pantalla: `2 und` o `0,250 kg`. */
    private fun quantityOf(line: QuoteLineUi, resources: Resources, formats: AppFormats): String {
        val unit = line.unit
        val symbol =
            unit?.let {
                SeedText.unitSymbol(resources, it.systemKey, it.symbol)
                    ?: SeedText.displayName(resources, SeedTable.UNITS, it.systemKey, it.name)
            } ?: resources.getString(R.string.seed_units_unit)
        val number = formats.quantity.format(line.quantity, unit?.allowsFraction ?: false)
        return if (unit != null && unit.systemKey == null) {
            resources.getString(R.string.format_custom_unit_quantity, number, symbol)
        } else {
            number + IcuTextNormalizer.NO_BREAK_SPACE + symbol
        }
    }

    private const val LINE_BREAK = "\n"
    private const val SECTION_BREAK = "\n\n"
}
