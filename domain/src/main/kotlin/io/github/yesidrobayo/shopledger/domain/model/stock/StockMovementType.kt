package io.github.yesidrobayo.shopledger.domain.model.stock

/**
 * El tipo de una fila del libro de stock. Es un valor guardado en la base: su nombre no cambia
 * nunca, y agregar uno exige una versión nueva del esquema.
 *
 * El libro es la verdad del stock y sus filas nunca se editan ni se borran. Anular un documento
 * agrega, por cada fila suya, un **compensatorio**: otra fila con la cantidad opuesta que apunta a
 * la original. Los tipos terminados en `_VOID` son esos compensatorios. Así el pasado queda tal
 * como ocurrió, y el saldo sigue siendo la suma de todas las filas, porque cada par suma cero.
 *
 * Cada tipo fija el signo de su cantidad ([quantitySign]) y qué compensatorio lo anula
 * ([voidType]). Qué hace cada tipo con el costo promedio lo decide `WeightedAverageCost`.
 */
enum class StockMovementType {
    /** Mercancía que ya existía antes de usar la app. No es compra ni gasto. */
    OPENING_BALANCE,

    /** Una línea de compra. */
    PURCHASE,

    /** Una línea de venta, o un componente de un combo vendido. */
    SALE,

    /** Un ajuste con motivo: conteo, daño, vencimiento, pérdida, consumo propio, regalo u otro. */
    ADJUSTMENT,

    /** Fija un costo nuevo sin mover unidades. */
    COST_CORRECTION,

    /** Anula una fila de compra. */
    PURCHASE_VOID,

    /** Anula una fila de venta. */
    SALE_VOID,

    /** Anula un ajuste o un inventario inicial. */
    ADJUSTMENT_VOID,

    /** Mercancía vendida que quien la compró devuelve, y que vuelve al stock. */
    SALE_RETURN,

    /** Anula una devolución de una venta. */
    SALE_RETURN_VOID,

    /** Mercancía que se devuelve a un proveedor. */
    PURCHASE_RETURN,

    /** Anula una devolución a proveedor. */
    PURCHASE_RETURN_VOID,

    /** Un insumo que se consume al producir. */
    PRODUCTION_OUT,

    /** Lo producido que entra al stock. */
    PRODUCTION_IN,

    /** Anula una salida o una entrada de producción. */
    PRODUCTION_VOID,

    /** Sale de una ubicación hacia otra. No cambia el saldo total del producto ni su costo. */
    TRANSFER_OUT,

    /** Entra a una ubicación desde otra. No cambia el saldo total del producto ni su costo. */
    TRANSFER_IN,

    /** Anula una salida o una entrada de transferencia. */
    TRANSFER_VOID,
    ;

    /** El signo que admite la cantidad de una fila de este tipo. */
    val quantitySign: QuantitySign
        get() = when (this) {
            OPENING_BALANCE, PURCHASE, SALE_VOID, SALE_RETURN, PURCHASE_RETURN_VOID,
            PRODUCTION_IN, TRANSFER_IN,
            -> QuantitySign.POSITIVE

            SALE, PURCHASE_VOID, SALE_RETURN_VOID, PURCHASE_RETURN, PRODUCTION_OUT, TRANSFER_OUT,
            -> QuantitySign.NEGATIVE

            // Un ajuste sube o baja. Cada uno de estos compensatorios anula tipos de los dos
            // signos, y lleva el opuesto del que anula.
            ADJUSTMENT, ADJUSTMENT_VOID, PRODUCTION_VOID, TRANSFER_VOID -> QuantitySign.NON_ZERO

            COST_CORRECTION -> QuantitySign.ZERO
        }

    /**
     * El tipo del compensatorio que anula una fila de este tipo, o `null` si no se anula.
     *
     * Una corrección de costo no se anula: se registra otra corrección. Un compensatorio tampoco:
     * anularlo devolvería al libro un documento anulado, y un documento anulado no se reactiva.
     */
    val voidType: StockMovementType?
        get() = when (this) {
            OPENING_BALANCE, ADJUSTMENT -> ADJUSTMENT_VOID

            PURCHASE -> PURCHASE_VOID

            SALE -> SALE_VOID

            SALE_RETURN -> SALE_RETURN_VOID

            PURCHASE_RETURN -> PURCHASE_RETURN_VOID

            PRODUCTION_OUT, PRODUCTION_IN -> PRODUCTION_VOID

            TRANSFER_OUT, TRANSFER_IN -> TRANSFER_VOID

            COST_CORRECTION, PURCHASE_VOID, SALE_VOID, ADJUSTMENT_VOID, SALE_RETURN_VOID,
            PURCHASE_RETURN_VOID, PRODUCTION_VOID, TRANSFER_VOID,
            -> null
        }

    /**
     * Si una fila de este tipo es un compensatorio: anula otra y tiene que apuntarle.
     *
     * Sale de [voidType], para que la lista de compensatorios y la de lo que cada uno anula no
     * puedan contradecirse.
     */
    val isCompensating: Boolean
        get() = entries.any { it.voidType == this }

    /**
     * Si una entrada de este tipo no puede dejar el stock del producto sobre su tope.
     *
     * Solo lo validan las entradas que la persona escribe: compra, inventario inicial, producción y
     * ajuste hacia arriba. Una venta nunca se bloquea por stock, y una anulación corrige un error,
     * así que tampoco se rechaza por el tope aunque deje el saldo encima.
     */
    val checksOnHandLimit: Boolean
        get() = when (this) {
            OPENING_BALANCE, PURCHASE, ADJUSTMENT, PRODUCTION_IN -> true

            SALE, COST_CORRECTION, PURCHASE_VOID, SALE_VOID, ADJUSTMENT_VOID, SALE_RETURN,
            SALE_RETURN_VOID, PURCHASE_RETURN, PURCHASE_RETURN_VOID, PRODUCTION_OUT,
            PRODUCTION_VOID, TRANSFER_OUT, TRANSFER_IN, TRANSFER_VOID,
            -> false
        }

    /** Si una fila de este tipo puede anular una de tipo [original]. */
    fun compensates(original: StockMovementType): Boolean = original.voidType == this
}
