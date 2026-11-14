package io.github.yesidrobayo.shopledger.ui.sale

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleReceipt
import io.github.yesidrobayo.shopledger.format.IcuTextNormalizer
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.moneyText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats

/**
 * El comprobante de una venta como texto, para compartirlo con quien compró: el nombre del negocio
 * si lo tiene, la fecha, una línea por producto, el total y la leyenda de que no es una factura.
 *
 * Sale de [SaleReceipt], que no tiene costo, ganancia, comisión ni nota: este texto no puede
 * llevarlos. Los textos salen de los recursos en el idioma de la interfaz, y los números del
 * formato regional, como en pantalla.
 */
internal object SaleReceiptText {
    fun of(
        receipt: SaleReceipt,
        resources: Resources,
        formats: AppFormats,
        units: Map<Long, MeasureUnit>,
        zone: BusinessTimeZone?,
    ): String {
        val chain = CurrencySymbolFonts.chain(resources)

        fun money(amount: Money): String {
            val parts = formats.money.parts(amount)
            return moneyText(parts, chain.resolve(parts.symbol, parts.currencyCode)).text
        }

        val date = formats.date.longDate(receipt.businessDate)
        val heading = buildList {
            receipt.businessName?.let(::add)
            add(
                zone?.let {
                    resources.getString(
                        R.string.sale_detail_when,
                        date,
                        formats.date.time(receipt.occurredAt, it),
                    )
                } ?: date,
            )
        }
        val lines = receipt.lines.map { line ->
            resources.getString(
                R.string.share_receipt_sale_line,
                saleQuantityText(line.quantity, units[line.unitId], resources, formats),
                line.productName,
                money(line.total),
            )
        }
        val total = resources.getString(R.string.share_receipt_total, money(receipt.total))
        val legend = resources.getString(R.string.legal_receipt_not_invoice)
        // Un renglón en blanco entre el encabezado, las líneas y el cierre.
        return listOf(heading, lines, listOf(total, legend))
            .filter { it.isNotEmpty() }
            .joinToString(SECTION_BREAK) { it.joinToString(LINE_BREAK) }
    }

    private const val LINE_BREAK = "\n"
    private const val SECTION_BREAK = "\n\n"
}

/**
 * La cantidad de una línea vendida con su unidad, como en pantalla: `2 und` o `0,250 kg`. Sin
 * unidad conocida, la genérica. La usan el comprobante en texto y el de papel.
 */
internal fun saleQuantityText(
    quantity: QuantityMilli,
    unit: MeasureUnit?,
    resources: Resources,
    formats: AppFormats,
): String {
    val symbol =
        unit?.let {
            SeedText.unitSymbol(resources, it.systemKey, it.symbol)
                ?: SeedText.displayName(resources, SeedTable.UNITS, it.systemKey, it.name)
        } ?: resources.getString(R.string.seed_units_unit)
    val number = formats.quantity.format(quantity, unit?.allowsFraction ?: false)
    return if (unit != null && unit.systemKey == null) {
        resources.getString(R.string.format_custom_unit_quantity, number, symbol)
    } else {
        number + IcuTextNormalizer.NO_BREAK_SPACE + symbol
    }
}
