package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult

/**
 * Los casos de uso de siempre con que se registra cada operación de una tarjeta. Cada uno abre su
 * propia transacción, y una tarjeta llama a uno solo: así lo que se dice registra exactamente lo
 * mismo que la pantalla de esa operación, con sus reglas y en una sola escritura.
 *
 * Son funciones para que las pruebas vean la petición que arma la tarjeta sin armar cada caso de
 * uso; [of] los toma de los casos de uso reales.
 */
class PhraseWriters(
    val sale: suspend (SaleRequest) -> RegisterSaleResult,
    val purchase: suspend (PurchaseRequest) -> RegisterPurchaseResult,
    val expense: suspend (ExpenseRequest) -> RegisterExpenseResult,
    val production: suspend (ProductionRequest) -> RegisterProductionResult,
    val adjustment: suspend (StockAdjustmentRequest) -> StockAdjustmentResult,
    val customerPayment: suspend (CustomerPaymentRequest) -> RecordCustomerPaymentResult,
    val supplierPayment: suspend (SupplierPaymentRequest) -> PaySupplierResult,
    val cost: suspend (productId: Long, newCost: Money) -> CorrectCostResult,
    val price: suspend (productId: Long, newPrice: Money) -> ChangePriceResult,
    val product: suspend (ProductForm) -> SaveProductResult,
    val recipe: suspend (parentId: Long, lines: List<RecipeLine>) -> SaveRecipeResult,
) {
    companion object {
        /** Los de cada caso de uso real, tal como los arma el contenedor de la app. */
        fun of(
            sale: RegisterSaleUseCase,
            purchase: RegisterPurchaseUseCase,
            expense: RegisterExpenseUseCase,
            production: RegisterProductionUseCase,
            adjustment: AdjustStockUseCase,
            customerPayment: RecordCustomerPaymentUseCase,
            supplierPayment: PaySupplierUseCase,
            cost: CorrectCostUseCase,
            price: ChangePriceUseCase,
            product: CreateProductUseCase,
            recipe: SaveRecipeUseCase,
        ): PhraseWriters = PhraseWriters(
            sale = { sale(it) },
            purchase = { purchase(it) },
            expense = { expense(it) },
            production = { production(it) },
            adjustment = { adjustment(it) },
            customerPayment = { customerPayment(it) },
            supplierPayment = { supplierPayment(it) },
            cost = { productId, newCost -> cost(productId, newCost) },
            price = { productId, newPrice -> price(productId, newPrice) },
            product = { product(it) },
            recipe = { parentId, lines -> recipe(parentId, lines) },
        )
    }
}
