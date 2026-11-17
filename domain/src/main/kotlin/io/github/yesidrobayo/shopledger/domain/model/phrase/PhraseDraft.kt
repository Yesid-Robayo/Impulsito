package io.github.yesidrobayo.shopledger.domain.model.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason

/**
 * Lo que la tarjeta muestra y la persona corrige antes de registrar. Sale de una lectura con
 * `toDraft` y solo lleva lo que se dijo o lo que la persona eligió: un precio que no se dijo no se
 * inventa aquí, lo pone el registro igual que la pantalla de siempre.
 *
 * Lo que no aplica a la operación queda en `null`, vacío o `false`, como en la lectura:
 *
 * - [reason] solo en un ajuste de stock.
 * - [paymentMethodId] es el método de pago dicho o elegido.
 * - [partyId] es el cliente en una venta o un abono, y el proveedor en una compra o un pago.
 * - [categoryId] solo en un gasto.
 * - [total] es el monto de un gasto, un abono o un pago, o el "todo por" de una venta o compra.
 * - [credit] solo en una venta o compra a crédito.
 * - [target] y [targetQuantity] son el producto de una receta y cuánto rinde, o el producto que se
 *   crea; [price], [cost] y [unitId] son el precio, el costo y la unidad del producto nuevo.
 */
data class PhraseDraft(
    val intent: PhraseIntent,
    val reason: StockAdjustmentReason? = null,
    val lines: List<DraftLine> = emptyList(),
    val paymentMethodId: Long? = null,
    val partyId: Long? = null,
    val categoryId: Long? = null,
    val total: Money? = null,
    val credit: Boolean = false,
    val target: DraftTarget? = null,
    val targetQuantity: QuantityMilli? = null,
    val price: Money? = null,
    val cost: Money? = null,
    val unitId: Long? = null,
)

/**
 * Una línea de la tarjeta. [product] `null` es un producto que falta elegir, y [quantity] `null`
 * una cantidad que falta escribir. [quantity] va en milésimas de [unitId], y [unitId] `null` es la
 * unidad base del producto. [unitPrice] es el precio o costo de una unidad entera de [unitId], y
 * [lineTotal] lo que se dijo por toda la línea.
 */
data class DraftLine(
    val product: DraftTarget?,
    val quantity: QuantityMilli?,
    val unitId: Long?,
    val unitPrice: Money? = null,
    val lineTotal: Money? = null,
)

/** Un producto de la tarjeta: uno del catálogo, o uno nuevo que se nombró y todavía no existe. */
sealed interface DraftTarget {
    /** Un producto que ya existe. */
    data class Existing(val productId: Long) : DraftTarget

    /** Un producto que no existe, con el nombre como se dijo, para ofrecer crearlo. */
    data class New(val name: String) : DraftTarget
}

/**
 * Lo que falta para poder registrar la tarjeta. Es un valor que se compara en pruebas y que la
 * pantalla traduce: su nombre no cambia.
 */
enum class DraftGap {
    /** Falta elegir un producto, o crear uno que se nombró y no existe. */
    PRODUCT,

    /** Falta la cantidad de una línea, o no es mayor que cero. */
    QUANTITY,

    /** Falta el método de pago de una venta. */
    PAYMENT,

    /** Falta la persona: el cliente o el proveedor que la operación exige. */
    PARTY,

    /** Falta la categoría de un gasto. */
    CATEGORY,

    /** Falta el monto, no es mayor que cero, o el total dicho no cuadra con las líneas. */
    AMOUNT,

    /** Falta el producto de una receta, o el nombre del producto que se crea. */
    TARGET,

    /** Falta el precio nuevo de un producto. */
    PRICE,

    /** Falta el costo: el de una línea de compra o el nuevo de un producto. */
    COST,

    /** La tarjeta no tiene nada que se pueda registrar así. */
    NOTHING,
}
