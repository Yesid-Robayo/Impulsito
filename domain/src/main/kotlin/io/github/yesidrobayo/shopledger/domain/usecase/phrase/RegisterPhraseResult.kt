package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.production.RecipeInputsResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult

/** Cómo terminó registrar una tarjeta. Sin textos: la pantalla traduce cada caso. */
sealed interface RegisterPhraseResult {
    /**
     * Quedó registrada la operación [intent], con el id [documentId] de lo que se guardó: la
     * venta, la compra, el gasto, la producción, la fila del ajuste o de la corrección de costo, el
     * abono, el pago, la fila del historial de precios o el producto creado. Una receta no tiene id
     * propio: va el del producto de la receta.
     */
    data class Registered(val intent: PhraseIntent, val documentId: Long?) : RegisterPhraseResult

    /** Falta [gaps] para poder registrar. No se escribió nada. */
    data class Incomplete(val gaps: Set<DraftGap>) : RegisterPhraseResult

    /** No se registró, por [reason]. No se escribió nada. */
    data class Rejected(val reason: PhraseRejection) : RegisterPhraseResult

    /** La base no pudo escribir, por [failure]. No se escribió nada, y la tarjeta se conserva. */
    data class StorageFailed(val failure: StorageFailure) : RegisterPhraseResult
}

/**
 * Por qué no se registró una tarjeta completa. Un rechazo del caso de uso de siempre llega con su
 * propio resultado, el mismo que ya traduce la pantalla de esa operación.
 */
sealed interface PhraseRejection {
    /** La venta rechazó lo pedido. */
    data class Sale(val result: RegisterSaleResult) : PhraseRejection

    /** La compra rechazó lo pedido. */
    data class Purchase(val result: RegisterPurchaseResult) : PhraseRejection

    /** El gasto rechazó lo pedido. */
    data class Expense(val result: RegisterExpenseResult) : PhraseRejection

    /** La producción rechazó lo pedido. */
    data class Production(val result: RegisterProductionResult) : PhraseRejection

    /** La receta del producto que se produce no deja calcular los insumos de esta cantidad. */
    data class ProductionInputs(val result: RecipeInputsResult) : PhraseRejection

    /** El ajuste rechazó lo pedido, también un conteo igual a lo que dice el libro. */
    data class Adjustment(val result: StockAdjustmentResult) : PhraseRejection

    /** El abono del cliente rechazó lo pedido. */
    data class CustomerPayment(val result: RecordCustomerPaymentResult) : PhraseRejection

    /** El pago al proveedor rechazó lo pedido. */
    data class SupplierPayment(val result: PaySupplierResult) : PhraseRejection

    /** La corrección de costo rechazó lo pedido, también un costo igual al de ahora. */
    data class Cost(val result: CorrectCostResult) : PhraseRejection

    /** El cambio de precio rechazó lo pedido, también un precio igual al de ahora. */
    data class Price(val result: ChangePriceResult) : PhraseRejection

    /** Crear el producto rechazó lo pedido, como un nombre que ya existe. */
    data class Product(val result: SaveProductResult) : PhraseRejection

    /** Guardar la receta rechazó lo pedido. */
    data class Recipe(val result: SaveRecipeResult) : PhraseRejection

    /**
     * Esta operación se registra con un solo producto, y la tarjeta trae varios: un cambio de
     * precio o de costo va uno por uno, y lo que se dijo que llevó una producción es de un solo
     * producto.
     */
    data object OneProductAtATime : PhraseRejection

    /**
     * Un producto de la tarjeta ya no existe o se archivó mientras tanto. [productId] es el que
     * falta.
     */
    data class ProductUnavailable(val productId: Long) : PhraseRejection

    /**
     * La unidad de la línea [index] no se puede llevar a la del producto: no es su base, no tiene
     * una conversión guardada y no es una medida métrica que se convierta, o la cantidad convertida
     * redondea a cero.
     */
    data class UnitNotConvertible(val index: Int) : PhraseRejection

    /**
     * La unidad del insumo dicho [index] de una producción no se puede llevar a la del insumo, como
     * [UnitNotConvertible] con una línea.
     */
    data class InputUnitNotConvertible(val index: Int) : PhraseRejection

    /**
     * Un precio nuevo dicho en una presentación, como una caja, no cambia el precio del producto:
     * la presentación tiene el suyo, que se cambia en el producto.
     */
    data object PriceNotInBaseUnit : PhraseRejection

    /**
     * El método de pago de la tarjeta ya no existe o se archivó mientras tanto: registrar sin él
     * sacaría el dinero de otra parte que la que se ve.
     */
    data object PaymentMethodUnavailable : PhraseRejection

    /**
     * Un monto de la tarjeta no se puede llevar a la operación sin cambiarlo: el total de una
     * línea o de la venta pasa de los topes, o no se puede calcular el precio de una línea.
     */
    data object AmountNotApplicable : PhraseRejection
}
