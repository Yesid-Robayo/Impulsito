package io.github.yesidrobayo.shopledger.domain.model.returns

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import kotlin.time.Instant

/**
 * Cómo se resuelve una devolución de venta. Es un valor guardado en la base: su nombre no cambia
 * nunca.
 */
enum class ReturnResolution {
    /** Se devuelve el dinero, desde una cuenta si la persona lleva cuentas. */
    REFUND,

    /** Lo devuelto queda como saldo a favor del cliente. */
    STORE_CREDIT,

    /** Lo devuelto paga una venta nueva, la del cambio. */
    EXCHANGE,
}

/**
 * Lo que una línea vendida trae, o lo que ya se llevaron de ella sus devoluciones activas: la
 * cantidad en milésimas de la unidad base, lo neto, el impuesto y el costo congelado.
 *
 * El total no se guarda aparte porque siempre es lo neto más el impuesto.
 */
data class LineAmounts(
    val quantity: QuantityMilli,
    val net: Money,
    val tax: Money,
    val cost: Money,
) {
    companion object {
        /** Una línea de la que todavía no se devolvió nada. */
        val NONE: LineAmounts = LineAmounts(QuantityMilli.ZERO, Money.ZERO, Money.ZERO, Money.ZERO)
    }
}

/**
 * Una devolución de venta lista para guardar, todavía sin id.
 *
 * Los montos van en la unidad menor de la moneda del negocio y salen de sus líneas: [netRefund] y
 * [taxRefund] son sus sumas, [totalRefund] es lo neto más el impuesto, y [costReturned] suma el costo
 * solo de las líneas que vuelven al stock. Lo que se perdió sigue en el costo de lo vendido.
 *
 * [moneyAccountId] es la cuenta de la que salió el dinero, solo si se movió alguno.
 * [replacementSaleId] es la venta nueva de un cambio.
 */
data class NewSaleReturn(
    val saleId: Long,
    val resolution: ReturnResolution,
    val netRefund: Money,
    val taxRefund: Money,
    val totalRefund: Money,
    val costReturned: Money,
    val moneyAccountId: Long?,
    val replacementSaleId: Long?,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)

/**
 * Una línea de una devolución, lista para guardar: cuánto vuelve de la línea vendida [saleLineId],
 * en milésimas de la unidad base, y su parte de lo neto, del impuesto, del total y del costo
 * congelado de la venta. [restock] dice si la mercancía vuelve al stock.
 */
data class NewSaleReturnLine(
    val saleLineId: Long,
    val quantity: QuantityMilli,
    val netAmount: Money,
    val taxAmount: Money,
    val lineTotal: Money,
    val lineCost: Money,
    val restock: Boolean,
)

/**
 * Una devolución guardada. Nunca se edita: solo se anula, y entonces [voiding] guarda cuándo y por
 * qué.
 */
data class SaleReturn(val id: Long, val content: NewSaleReturn, val voiding: Voiding?) {
    /** Activa o anulada, según [voiding]. */
    val status: DocumentStatus get() = DocumentStatus.of(voiding)
}

/** Una línea de devolución guardada. Nunca se edita ni se borra, ni al anular su devolución. */
data class SaleReturnLine(val id: Long, val saleReturnId: Long, val content: NewSaleReturnLine)
