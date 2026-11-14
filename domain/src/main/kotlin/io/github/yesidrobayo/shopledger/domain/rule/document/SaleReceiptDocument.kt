package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import io.github.yesidrobayo.shopledger.domain.model.sum
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleLineRules

/**
 * El comprobante de una venta guardada, listo para dibujar.
 *
 * Toma de la venta solo lo que va al cliente: nombres congelados, cantidades, lo que cobró cada
 * línea, el impuesto, los pagos y el cambio. El costo, la comisión y la nota se quedan afuera
 * porque [ReceiptDocument] no tiene dónde ponerlos.
 */
object SaleReceiptDocument {
    /**
     * El comprobante de [detail] con la plantilla [template], encabezado por [businessName], y con
     * [customerName] si la venta tiene cliente. [creditBefore] es el saldo a favor que el cliente
     * tenía justo antes de la venta, cero si no tenía o no hay cliente.
     *
     * Las cifras salen de lo que la venta congeló, en el orden del papel: subtotal, ajuste,
     * impuesto, redondeo, propina, total, pagos, cambio, saldo a favor aplicado, saldo pendiente y
     * devoluciones activas. Cada fila, salvo el total, solo aparece si no es cero.
     *
     * Lo pagado a crédito primero consume el saldo a favor, como lo hizo el libro del cliente al
     * vender: esa parte es un anticipo aplicado, no deuda. Solo lo que el saldo a favor no cubrió
     * queda como saldo pendiente.
     *
     * El subtotal es lo que cobran las líneas tras su descuento, antes del ajuste de toda la
     * venta. Si subtotal y ajuste ya dan el total, el impuesto venía en los precios: se informa
     * como incluido y no suma. Si no, se sumó encima. Se decide con la venta guardada y no con el
     * ajuste de hoy, porque el documento repite lo que se cobró entonces.
     */
    fun of(
        detail: SaleDetail,
        template: ReceiptSettings,
        businessName: String?,
        customerName: String?,
        creditBefore: Money,
    ): ReceiptDocument {
        val sale = detail.sale.content
        val rows = detail.lines.map { it.content }
        val lines = rows.map { row ->
            ReceiptLine(
                productName = row.productNameSnapshot,
                quantity = row.enteredQuantity,
                unitId = row.unitId,
                amount = baseOf(row),
                extra = row.parentLineId != null,
            )
        }
        val subtotal = lines.map { it.amount }.sum()
        val included = subtotal + sale.orderAdjustment == sale.total
        val payments = detail.payments.map { it.content }
        val amounts = buildList {
            add(ReceiptAmount.Subtotal(subtotal))
            addIfNotZero(sale.orderAdjustment, ReceiptAmount::Adjustment)
            addAll(taxesOf(rows, included))
            addIfNotZero(sale.cashRoundingAmount, ReceiptAmount::Rounding)
            addIfNotZero(sale.tipAmount, ReceiptAmount::Tip)
            add(ReceiptAmount.Total(sale.amountDue))
            payments.forEach { add(ReceiptAmount.Payment(it.methodNameSnapshot, it.amount)) }
            addIfNotZero(payments.mapNotNull { it.changeAmount }.sum(), ReceiptAmount::Change)
            val onAccount = payments.filter { it.methodKind == PaymentMethodKind.ON_ACCOUNT }
                .map { it.amount }
                .sum()
            val applied = minOf(onAccount, creditBefore.coerceAtLeast(Money.ZERO))
            addIfNotZero(applied, ReceiptAmount::CreditApplied)
            addIfNotZero(onAccount - applied, ReceiptAmount::BalanceDue)
            detail.returns
                .filter { it.status == DocumentStatus.ACTIVE }
                .forEach { add(ReceiptAmount.Return(it.id, -it.content.totalRefund)) }
        }
        return ReceiptDocument(
            kind = ReceiptKind.SALE,
            number = detail.sale.id,
            voided = detail.sale.status == DocumentStatus.VOIDED,
            header = ReceiptHeader(
                logoMediaId = template.logoMediaId,
                businessName = businessName.trimmedOrNull(),
                businessDetails = template.businessDetails.trimmedOrNull(),
            ),
            businessDate = sale.businessDate,
            occurredAt = sale.occurredAt,
            customerName = customerName.trimmedOrNull(),
            lines = lines,
            amounts = amounts,
            footerText = template.footerText.trimmedOrNull(),
        )
    }

    /**
     * Lo que cobra [row] tras su descuento y antes del ajuste de toda la venta: lo mismo que
     * mostró el cobro. Una venta guardada ya pasó el tope de una línea; si aun así la cuenta no
     * cupiera, se usa el total de la línea, que es una cifra guardada.
     */
    private fun baseOf(row: NewSaleLine): Money =
        SaleLineRules.gross(row.enteredQuantity, row.unitPrice).valueOrNull()
            ?.let { it - row.lineDiscount }
            ?: row.lineTotal

    /**
     * El impuesto de cada tasa, con el nombre que tenía al vender, en el orden de su primera
     * línea. Una línea sin impuesto no aporta fila.
     */
    private fun taxesOf(rows: List<NewSaleLine>, included: Boolean): List<ReceiptAmount.Tax> =
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

    /** Agrega la fila de [amount] solo si no es cero. */
    private fun MutableList<ReceiptAmount>.addIfNotZero(
        amount: Money,
        row: (Money) -> ReceiptAmount,
    ) {
        if (amount != Money.ZERO) add(row(amount))
    }

    private fun String?.trimmedOrNull(): String? =
        this?.let { text -> text.trim().takeIf { it.isNotEmpty() } }
}
