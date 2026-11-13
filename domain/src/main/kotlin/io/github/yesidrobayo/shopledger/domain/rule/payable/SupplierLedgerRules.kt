package io.github.yesidrobayo.shopledger.domain.rule.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType

/**
 * Las reglas del libro de un proveedor: qué parte de una compra queda debiendo, cuándo vence, qué
 * signo lleva cada tipo de fila, cómo se encadena el saldo y cómo se compensa una fila.
 *
 * El libro sigue el mismo patrón que el de stock: solo crece, el saldo del proveedor es la suma de
 * sus filas, y cada fila guarda el saldo que deja, que es el de la anterior más su monto.
 */
object SupplierLedgerRules {
    /**
     * Lo que una compra de [total] deja debiendo si al confirmarla se pagó [amountPaid]. Lo pagado
     * va de cero al total: más que el total no es una compra a plazo, y negativo no es un pago.
     */
    fun creditOf(total: Money, amountPaid: Money): PurchaseCredit = when {
        amountPaid < Money.ZERO || amountPaid > total -> PurchaseCredit.OutOfRange
        amountPaid == total -> PurchaseCredit.PaidInFull
        else -> PurchaseCredit.OnCredit(total - amountPaid)
    }

    /**
     * El vencimiento que se propone para una compra fechada [businessDate] a un proveedor con plazo
     * habitual de [termsDays] días, o `null` si el proveedor no tiene plazo: entonces la deuda no
     * vence sola, y la persona puede escribir una fecha.
     */
    fun proposedDueDate(businessDate: BusinessDate, termsDays: Int?): BusinessDate? =
        termsDays?.let(businessDate::plusDays)

    /** Si [amount] tiene el signo que exige [type]. Ningún tipo admite cero. */
    fun signFits(type: SupplierLedgerEntryType, amount: Money): Boolean = when (type) {
        SupplierLedgerEntryType.CREDIT_PURCHASE -> amount > Money.ZERO

        SupplierLedgerEntryType.PAYMENT,
        SupplierLedgerEntryType.RETURN_CREDIT,
        -> amount < Money.ZERO

        SupplierLedgerEntryType.OPENING_BALANCE,
        SupplierLedgerEntryType.ADJUSTMENT,
        SupplierLedgerEntryType.ENTRY_VOID,
        -> amount != Money.ZERO
    }

    /**
     * El saldo que deja una fila de [amount] tras [previous], el que dejó la fila anterior del mismo
     * proveedor, o `null` si es la primera. Devuelve `null` si la suma no cabe: un saldo así solo
     * sale de cifras absurdas, y la fila no se escribe.
     */
    fun balanceAfter(previous: Money?, amount: Money): Money? = try {
        (previous ?: Money.ZERO) + amount
    } catch (overflow: ArithmeticException) {
        null
    }

    /**
     * La compensación de [entry]. [alreadyCompensated] dice si ya tiene una: una fila se anula una
     * sola vez, y anular de nuevo la restaría dos veces. Una compensación no se compensa.
     */
    fun compensationOf(
        entry: SupplierLedgerEntry,
        alreadyCompensated: Boolean,
    ): SupplierCompensation = when {
        entry.content.type == SupplierLedgerEntryType.ENTRY_VOID ->
            SupplierCompensation.NotCompensable

        alreadyCompensated -> SupplierCompensation.AlreadyCompensated

        else -> SupplierCompensation.Ready(-entry.content.amount)
    }
}

/** Lo que una compra deja debiendo al proveedor. */
sealed interface PurchaseCredit {
    /** Se pagó completa al confirmarla: el libro no recibe nada. */
    data object PaidInFull : PurchaseCredit

    /** Queda debiendo [amount], que es el cargo del libro. */
    data class OnCredit(val amount: Money) : PurchaseCredit

    /** Lo pagado es negativo o pasa del total. */
    data object OutOfRange : PurchaseCredit
}

/** La compensación de una fila del libro de un proveedor. */
sealed interface SupplierCompensation {
    /** Se compensa con una fila de [amount], el monto opuesto. */
    data class Ready(val amount: Money) : SupplierCompensation

    /** Ya tiene su compensación. */
    data object AlreadyCompensated : SupplierCompensation

    /** Es una compensación: no se anula. */
    data object NotCompensable : SupplierCompensation
}
