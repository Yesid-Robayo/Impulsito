package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDirection.EITHER
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDirection.IN
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDirection.OUT

/**
 * Hacia dónde mueve el dinero un tipo de movimiento: el signo que su monto tiene que tener.
 */
enum class MoneyDirection {
    /** Entra a la cuenta: monto positivo. */
    IN,

    /** Sale de la cuenta: monto negativo. */
    OUT,

    /** Puede entrar o salir: un saldo inicial, una diferencia de caja o una anulación. */
    EITHER,
}

/**
 * De qué documento viene un movimiento: lo que su tipo exige que apunte. [NONE] es un movimiento que
 * la persona registra a mano, sin documento.
 */
enum class MoneyOriginKind {
    NONE,
    SALE_PAYMENT,
    CUSTOMER_PAYMENT,
    SUPPLIER_PAYMENT,
    EXPENSE,
    PURCHASE,
    SALE_RETURN,
    PURCHASE_RETURN,
    TRANSFER,
    VOID,
}

/**
 * El tipo de un movimiento del libro de dinero, con el signo y el documento que exige, igual que la
 * guarda de la base. Los nombres se guardan tal cual y no cambian nunca.
 *
 * Ningún tipo cambia la ganancia por sí mismo: la venta, el gasto y la compra ya la cuentan en su
 * documento, y el dinero solo dice dónde quedó. Por eso un retiro de la persona dueña no es un gasto,
 * y otro ingreso no es una venta.
 *
 * [isManual] marca lo que la persona registra a mano desde la cuenta, y solo eso se anula desde la
 * cuenta: lo que viene de un documento se anula al anular ese documento.
 */
enum class MoneyMovementType(
    val direction: MoneyDirection,
    val originKind: MoneyOriginKind,
    val isManual: Boolean,
) {
    /** El dinero que la cuenta ya tenía al empezar a llevarla. */
    OPENING_BALANCE(EITHER, MoneyOriginKind.NONE, isManual = true),

    /** Lo cobrado en una venta, por su pago. */
    SALE_RECEIPT(IN, MoneyOriginKind.SALE_PAYMENT, isManual = false),

    /** Un abono de un cliente a su deuda. */
    CUSTOMER_PAYMENT(IN, MoneyOriginKind.CUSTOMER_PAYMENT, isManual = false),

    /** Lo devuelto a un cliente por una devolución. */
    REFUND(OUT, MoneyOriginKind.SALE_RETURN, isManual = false),

    /** La comisión que cobra el medio de pago de una venta. */
    FEE(OUT, MoneyOriginKind.SALE_PAYMENT, isManual = false),

    /** Lo pagado por un gasto. */
    EXPENSE_PAYMENT(OUT, MoneyOriginKind.EXPENSE, isManual = false),

    /** Lo pagado por una compra al confirmarla. */
    PURCHASE_PAYMENT(OUT, MoneyOriginKind.PURCHASE, isManual = false),

    /** Un pago posterior a un proveedor. */
    SUPPLIER_PAYMENT(OUT, MoneyOriginKind.SUPPLIER_PAYMENT, isManual = false),

    /** Lo que un proveedor devuelve en dinero por una devolución. */
    SUPPLIER_REFUND(IN, MoneyOriginKind.PURCHASE_RETURN, isManual = false),

    /** Dinero que la persona dueña saca para su uso. No es un gasto. */
    OWNER_DRAW(OUT, MoneyOriginKind.NONE, isManual = true),

    /** Dinero que la persona dueña pone en el negocio. No es una venta. */
    OWNER_CONTRIBUTION(IN, MoneyOriginKind.NONE, isManual = true),

    /** Dinero que no viene de una venta, como un reintegro. No entra en la ganancia. */
    OTHER_INCOME(IN, MoneyOriginKind.NONE, isManual = true),

    /** La parte que sale de una transferencia entre cuentas. */
    TRANSFER_OUT(OUT, MoneyOriginKind.TRANSFER, isManual = true),

    /** La parte que entra de una transferencia entre cuentas. */
    TRANSFER_IN(IN, MoneyOriginKind.TRANSFER, isManual = true),

    /** La diferencia entre lo esperado y lo contado al cerrar una caja. */
    CASH_DIFFERENCE(EITHER, MoneyOriginKind.NONE, isManual = false),

    /** Compensa otro movimiento con el monto opuesto. Nunca se anula a sí misma. */
    MOVEMENT_VOID(EITHER, MoneyOriginKind.VOID, isManual = false),
    ;

    /** Si [amount] tiene el signo que este tipo exige. Un monto de cero nunca lo tiene. */
    fun allows(amount: Long): Boolean = when (direction) {
        IN -> amount > 0
        OUT -> amount < 0
        EITHER -> amount != 0L
    }
}
