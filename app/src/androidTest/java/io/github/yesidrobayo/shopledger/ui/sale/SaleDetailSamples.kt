package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleResults
import io.github.yesidrobayo.shopledger.ui.product.SAMPLE_UNIT
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

// Una venta inventada para las pruebas y capturas del detalle: la de tres productos de la guía, con
// un descuento, un producto archivado, un pago con tarjeta que se acredita después y deja su
// comisión, y el resto en efectivo. Con [withCharges], también impuesto, ajuste, propina y redondeo.

internal val DETAIL_DATE = BusinessDate(LocalDate(2026, 11, 2))

internal fun saleDetailView(
    voided: Boolean = false,
    discreetNote: String? = "Mesa 4",
    withCharges: Boolean = false,
) = SaleDetailView(
    saleId = 1_204L,
    total = Money(31_000L),
    businessDate = DETAIL_DATE,
    occurredAt = Instant.fromEpochMilliseconds(1_793_631_600_000L),
    zone = BusinessTimeZone.of("Etc/GMT+5"),
    voiding = if (voided) {
        SaleVoidView(
            VoidReason.CUSTOMER_RETURN,
            DETAIL_DATE.plusDays(1),
            null,
        )
    } else {
        null
    },
    lines = listOf(
        SaleDetailLine(
            1L,
            "Jabón de avena",
            QuantityMilli(2_000L),
            SAMPLE_UNIT,
            Money(3_500L),
            Money.ZERO,
            Money(7_000L),
            false,
        ),
        SaleDetailLine(
            2L,
            "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos",
            QuantityMilli(1_000L),
            SAMPLE_UNIT,
            Money(6_161L),
            Money(161L),
            Money(6_000L),
            true,
        ),
        SaleDetailLine(
            3L,
            "Crema",
            QuantityMilli(1_000L),
            SAMPLE_UNIT,
            Money(20_000L),
            Money(2_000L),
            Money(18_000L),
            false,
        ),
    ),
    payments = listOf(
        SaleDetailPayment(
            1L,
            "Tarjeta",
            Money(24_000L),
            DETAIL_DATE.plusDays(2),
            fee = Money(899L),
            netReceived = Money(23_101L),
        ),
        SaleDetailPayment(2L, "Efectivo", Money(7_000L), null),
    ),
    results = SaleResults(
        cost = Money(17_817L),
        profit = Money(13_183L),
        margin = Margin.Defined(BasisPoints(4_253L)),
        fees = Money(899L),
        netReceived = Money(30_101L),
    ),
    charges = if (withCharges) {
        SaleDetailCharges(
            taxes = listOf(
                SaleDetailTax("Tasa general", Money(4_950L)),
                SaleDetailTax(null, Money(300L)),
            ),
            orderAdjustment = Money(-500L),
            tip = Money(1_000L),
            cashRounding = Money(-100L),
        )
    } else {
        SaleDetailCharges()
    },
    note = discreetNote,
)
