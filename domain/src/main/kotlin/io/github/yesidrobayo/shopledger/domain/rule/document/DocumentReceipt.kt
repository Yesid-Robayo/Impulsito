package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.Order
import io.github.yesidrobayo.shopledger.domain.model.document.Quote
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import io.github.yesidrobayo.shopledger.domain.model.sum
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleLineRules

/**
 * El documento de una cotización o de un pedido, listo para dibujar con la misma plantilla del
 * comprobante de venta.
 *
 * Lleva su propio tipo, y con él su título y su leyenda: nunca se confunde con un comprobante de
 * venta ni se llama factura. Como el de venta, solo toma lo que va al cliente: nombres congelados,
 * cantidades, lo que cobra cada línea, el impuesto, el envío y el total. Las líneas de documento no
 * guardan costo, y la nota y la dirección se quedan afuera porque [ReceiptDocument] no tiene dónde
 * ponerlas.
 */
object DocumentReceipt {
    /**
     * La cotización [quote] con sus [lines], encabezada por [businessName] con la plantilla
     * [template], y con [customerName] si tiene cliente. Lleva su vigencia, si tiene.
     */
    fun ofQuote(
        quote: Quote,
        lines: List<DocumentLine>,
        template: ReceiptSettings,
        businessName: String?,
        customerName: String?,
    ): ReceiptDocument = documentOf(
        kind = ReceiptKind.QUOTE,
        number = quote.id,
        rows = lines.map { it.content },
        total = quote.totals.total,
        shipping = Money.ZERO,
        template = template,
        businessName = businessName,
        customerName = customerName,
        stamp = RecordTime(quote.occurredAt, quote.businessDate),
    ).copy(validUntil = quote.validUntil)

    /**
     * El pedido [order] con sus [lines], encabezado por [businessName] con la plantilla
     * [template], y con [customerName] si tiene cliente. El cobro por envío va en su propia fila,
     * y la fecha de entrega, si tiene.
     */
    fun ofOrder(
        order: Order,
        lines: List<DocumentLine>,
        template: ReceiptSettings,
        businessName: String?,
        customerName: String?,
    ): ReceiptDocument = documentOf(
        kind = ReceiptKind.ORDER,
        number = order.id,
        rows = lines.map { it.content },
        total = order.total,
        shipping = order.shippingAmount,
        template = template,
        businessName = businessName,
        customerName = customerName,
        stamp = RecordTime(order.occurredAt, order.businessDate),
    ).copy(dueDate = order.dueDate)

    /**
     * Lo común: líneas, subtotal, impuesto, envío y total. Si el subtotal ya da lo que cobran las
     * líneas, el impuesto venía en los precios: se informa como incluido y no suma.
     */
    private fun documentOf(
        kind: ReceiptKind,
        number: Long,
        rows: List<NewDocumentLine>,
        total: Money,
        shipping: Money,
        template: ReceiptSettings,
        businessName: String?,
        customerName: String?,
        stamp: RecordTime,
    ): ReceiptDocument {
        val lines = rows.map { row ->
            ReceiptLine(
                productName = row.productNameSnapshot,
                quantity = row.enteredQuantity,
                unitId = row.unitId,
                amount = baseOf(row),
                extra = false,
            )
        }
        val subtotal = lines.map { it.amount }.sum()
        val included = subtotal == total - shipping
        val amounts = buildList {
            add(ReceiptAmount.Subtotal(subtotal))
            addAll(taxesOf(rows, included))
            if (shipping != Money.ZERO) add(ReceiptAmount.Shipping(shipping))
            add(ReceiptAmount.Total(total))
        }
        return ReceiptDocument(
            kind = kind,
            number = number,
            voided = false,
            header = ReceiptHeader(
                logoMediaId = template.logoMediaId,
                businessName = businessName.trimmedOrNull(),
                businessDetails = template.businessDetails.trimmedOrNull(),
            ),
            businessDate = stamp.businessDate,
            occurredAt = stamp.occurredAt,
            customerName = customerName.trimmedOrNull(),
            lines = lines,
            amounts = amounts,
            footerText = template.footerText.trimmedOrNull(),
        )
    }

    /** Lo que cobra [row] tras su descuento: el bruto menos el descuento, o su total guardado. */
    private fun baseOf(row: NewDocumentLine): Money =
        SaleLineRules.gross(row.enteredQuantity, row.unitPrice).valueOrNull()
            ?.let { it - row.lineDiscount }
            ?: row.lineTotal

    /** El impuesto de cada tasa, con el nombre que tenía al guardar, en el orden de su línea. */
    private fun taxesOf(rows: List<NewDocumentLine>, included: Boolean): List<ReceiptAmount.Tax> =
        rows.filter { it.taxAmount != Money.ZERO }
            .groupBy { it.taxNameSnapshot to it.taxRate }
            .map { (key, taxed) ->
                ReceiptAmount.Tax(
                    name = key.first,
                    rate = key.second,
                    amount = taxed.map { it.taxAmount }.sum(),
                    included = included,
                )
            }

    private fun String?.trimmedOrNull(): String? =
        this?.let { text -> text.trim().takeIf { it.isNotEmpty() } }
}
