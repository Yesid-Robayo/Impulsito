package io.github.yesidrobayo.shopledger.domain.model.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason

/** Qué operación propone la frase. Es un valor que se compara en pruebas: su nombre no cambia. */
enum class PhraseIntent {
    /** Vender: "vendí", "se llevaron", o una lista de productos sin verbo. */
    SALE,

    /** Comprar mercancía o insumos. */
    PURCHASE,

    /** Un gasto que no es un producto: el arriendo, la luz, un taxi. */
    EXPENSE,

    /** Fabricar un producto con receta. */
    PRODUCTION,

    /** Algo que sale o cambia sin venderse, o el conteo real de lo que hay. */
    STOCK_ADJUSTMENT,

    /** Un cliente abona a lo que debe. */
    CUSTOMER_PAYMENT,

    /** Se le paga a un proveedor lo que se le debe. */
    SUPPLIER_PAYMENT,

    /** Definir o reemplazar la receta de un producto. */
    RECIPE,

    /** Crear un producto. */
    NEW_PRODUCT,

    /** Fijar el costo de un producto con stock propio. */
    COST_CORRECTION,

    /** Cambiar el precio de venta de un producto. */
    PRICE_CHANGE,
}

/** Lo que la frase no dejó resolver y la pantalla debe preguntar o advertir. */
enum class PhraseIssue {
    /** La frase mezcla dos operaciones, como vender y comprar. */
    MIXED_OPERATIONS,

    /** Se dijo una cantidad que no se pudo leer, como "unos panes". */
    UNREADABLE_QUANTITY,

    /** La unidad dicha no es una que el producto admita. */
    UNIT_NOT_FOR_PRODUCT,

    /** Se pidió producir algo que no tiene receta. */
    NO_RECIPE,

    /** Se corrigió el costo de algo que no lleva stock propio. */
    NO_STOCK,

    /** Se pidió crear un producto que ya existe. */
    ALREADY_EXISTS,

    /** La frase no dice nada que se pueda registrar. */
    NOTHING_UNDERSTOOD,
}

/** Un nombre de la frase: un método de pago, una persona o una categoría. */
sealed interface NameMatch {
    /** Hay uno claro. */
    data class Found(val id: Long) : NameMatch

    /** Varios son igual de posibles: la pantalla pregunta cuál. */
    data class Choose(val ids: List<Long>) : NameMatch

    /** Nombra a alguien que no está, tal como lo dijo: la pantalla ofrece crearlo. */
    data class Unknown(val spoken: String) : NameMatch
}

/** El producto de una línea. */
sealed interface ProductMatch {
    /** Hay uno claro. */
    data class Found(val id: Long) : ProductMatch

    /** Varios son igual de posibles, como "harina" con harina de trigo y de maíz. */
    data class Choose(val ids: List<Long>) : ProductMatch

    /**
     * No se parece a ninguno: el nombre dicho, en singular y con mayúscula inicial, para crearlo.
     */
    data class Unknown(val spokenName: String) : ProductMatch
}

/**
 * Una línea de la operación, en el orden dicho. [quantity] va en milésimas de la unidad dicha,
 * [unitId], y es `null` si no se dijo: la pantalla pone una. [unitId] es `null` si no se dijo
 * unidad, y entonces vale la base del producto. [unitPrice] es el precio de una unidad dicha y
 * [lineTotal] lo que se dijo por toda la línea.
 */
data class PhraseLine(
    val product: ProductMatch,
    val quantity: QuantityMilli?,
    val unitId: Long?,
    val unitPrice: Money?,
    val lineTotal: Money?,
)

/**
 * Lo que entendió el intérprete de una frase. Nunca registra nada: la pantalla lo muestra para
 * confirmar, y la persona corrige lo que haga falta.
 *
 * Es un valor inmutable con todo a la vista, para que una corrección posterior pueda tomar una
 * lectura, cambiar un dato con `copy` y devolver otra. Lo que no aplica a la operación queda en
 * `null`, vacío o `false`:
 *
 * - [reason] solo en un ajuste de stock.
 * - [category] solo en un gasto, y [party] en una venta, una compra o un pago de cliente o a
 *   proveedor.
 * - [total] es un total para toda la operación si se dijo; en un gasto o un pago, el monto.
 * - [credit] solo en una venta o una compra a crédito.
 * - [target], [targetQuantity], [price], [cost] y [unitId] solo en una receta o un producto nuevo:
 *   el producto de la receta y cuánto rinde, o el producto que se crea con su precio, su costo y su
 *   unidad.
 */
data class PhraseReading(
    val intent: PhraseIntent?,
    val reason: StockAdjustmentReason? = null,
    val lines: List<PhraseLine> = emptyList(),
    val payment: NameMatch? = null,
    val party: NameMatch? = null,
    val category: NameMatch? = null,
    val total: Money? = null,
    val credit: Boolean = false,
    val target: ProductMatch? = null,
    val targetQuantity: QuantityMilli? = null,
    val price: Money? = null,
    val cost: Money? = null,
    val unitId: Long? = null,
    val issues: Set<PhraseIssue> = emptySet(),
)
