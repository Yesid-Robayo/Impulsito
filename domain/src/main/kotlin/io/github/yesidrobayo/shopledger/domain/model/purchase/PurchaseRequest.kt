package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que la persona pide registrar como compra, tal como lo escribió.
 *
 * [supplierId] es opcional: una compra en plaza o sin proveedor fijo no lo lleva. [invoiceReference]
 * es el número del documento del proveedor, no algo que genere la app. [date] es la fecha de negocio
 * elegida, o `null` para hoy: puede ser pasada, nunca futura.
 *
 * [amountPaid] es lo pagado al confirmar, de cero al total, o `null` si se paga completa. Pagar menos
 * deja la compra a plazo: exige proveedor, y lo que falta va a su libro. [dueDate] es el vencimiento
 * de esa deuda, o `null` para proponerlo con el plazo habitual del proveedor. Sin deuda no se usa.
 *
 * [moneyAccountId] es la cuenta de la que sale lo pagado al confirmar, o `null` si la persona no usa
 * cuentas. Sin nada pagado no se usa.
 *
 * [locationId] es la ubicación donde entra lo comprado, o `null` para la inicial: mientras haya una
 * sola, nadie pregunta dónde.
 */
data class PurchaseRequest(
    val lines: List<PurchaseLineRequest>,
    val supplierId: Long? = null,
    val invoiceReference: String? = null,
    val date: BusinessDate? = null,
    val amountPaid: Money? = null,
    val dueDate: BusinessDate? = null,
    val moneyAccountId: Long? = null,
    val locationId: Long? = null,
)

/**
 * Una línea de la compra: [quantity] milésimas de la unidad [unitId] de [productId], y lo que
 * costó, escrito como costo unitario o como total de la línea, a elección de la persona.
 *
 * [unitId] `null` es la unidad base. Otra unidad tiene que ser una conversión de compra del
 * producto, como la caja: su factor lleva la cantidad a la unidad base, que es la del libro. El
 * costo unitario va por unidad entera de [unitId]: una caja, no una unidad suelta.
 *
 * [lot] es el lote con que entra, solo en un producto que se controla por lotes. Sin él, la línea
 * entra al lote sin asignar del producto. Una serie es una línea de una unidad con su código.
 */
data class PurchaseLineRequest(
    val productId: Long,
    val quantity: QuantityMilli,
    val amount: PurchaseLineAmount,
    val unitId: Long? = null,
    val lot: PurchaseLineLot? = null,
)

/** El lote con que entra una línea de compra de un producto que se controla por lotes. */
sealed interface PurchaseLineLot {
    /** Entra a un lote que el producto ya tiene, como otra entrega del mismo lote. */
    data class Existing(val lotId: Long) : PurchaseLineLot

    /**
     * Crea un lote con [code], tal como lo escribió la persona, y su vencimiento [expiry], si lo
     * tiene. El código es único dentro del producto.
     */
    data class New(val code: String, val expiry: BusinessDate?) : PurchaseLineLot
}

/**
 * Cómo escribió la persona lo que costó una línea. El otro valor se deduce, y los dos se guardan.
 *
 * Los dos van en la forma en que se escriben los montos del negocio: con el impuesto incluido, lo
 * pagado; con el impuesto sumado, el neto, al que después se suma el impuesto.
 */
sealed interface PurchaseLineAmount {
    /** El costo de una unidad entera. El total de la línea sale de multiplicarlo por la cantidad. */
    data class UnitCost(val cost: Money) : PurchaseLineAmount

    /**
     * El total de la línea, como lo trae el documento del proveedor. El costo unitario sale de
     * dividirlo por la cantidad, y el costo promedio usa este total, no el unitario redondeado.
     */
    data class LineTotal(val total: Money) : PurchaseLineAmount
}
