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
 * - [inputs] solo en una producción de un producto: lo que se dijo que llevó. Sin ellos, los
 *   insumos salen de la receta. Con ellos, se produce con lo dicho y lo que la receta lleva y no se
 *   nombró sale de la receta; si el producto no tiene receta, lo dicho se guarda como su receta.
 * - [updatesRecipe] es el interruptor `Actualizar la receta`: con él encendido, lo dicho también
 *   cambia la receta guardada, en la misma escritura. Nace apagado.
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
    val inputs: List<DraftLine> = emptyList(),
    val updatesRecipe: Boolean = false,
)

/**
 * Una línea de la tarjeta. [product] `null` es un producto que falta elegir, y [quantity] `null`
 * una cantidad que falta escribir. [quantity] va en milésimas de [unitId], y [unitId] `null` es la
 * unidad base del producto. [unitPrice] es el precio o costo de una unidad entera de [unitId], y
 * [lineTotal] lo que se dijo por toda la línea.
 *
 * [candidates] son los productos entre los que se pidió elegir cuando falta el producto, como las
 * dos gaseosas de "2 gaseosas": así una corrección como "las grandes" elige entre ellos. Vacía si
 * no se pidió elegir o si ya hay producto.
 */
data class DraftLine(
    val product: DraftTarget?,
    val quantity: QuantityMilli?,
    val unitId: Long?,
    val unitPrice: Money? = null,
    val lineTotal: Money? = null,
    val candidates: List<Long> = emptyList(),
)

/** Un producto de la tarjeta: uno del catálogo, o uno nuevo que se nombró y todavía no existe. */
sealed interface DraftTarget {
    /** Un producto que ya existe. */
    data class Existing(val productId: Long) : DraftTarget

    /**
     * Un producto que no existe, con el nombre como se dijo, para ofrecer crearlo, y lo que se dijo
     * de él: su uso, si es un servicio y su categoría. Se crea con eso.
     */
    data class New(val name: String, val traits: NewProductTraits = NewProductTraits.NONE) :
        DraftTarget
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

    /**
     * Lo que se produce no tiene receta y no se dijo qué lleva: con lo que se diga, se produce y se
     * guarda como su receta.
     */
    INPUTS,

    /** La tarjeta no tiene nada que se pueda registrar así. */
    NOTHING,
}
