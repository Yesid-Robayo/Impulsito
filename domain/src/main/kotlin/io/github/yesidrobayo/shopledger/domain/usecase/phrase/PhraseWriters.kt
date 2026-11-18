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
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionStep
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionsResult
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentsResult

/**
 * Los casos de uso de siempre con que se registra cada operación de una tarjeta. Cada uno abre su
 * propia transacción, y una tarjeta llama a uno solo: así lo que se dice registra exactamente lo
 * mismo que la pantalla de esa operación, con sus reglas y en una sola escritura. Las producciones
 * de una tarjeta, con la receta que se dijo, van juntas en una sola.
 *
 * Son funciones para que las pruebas vean la petición que arma la tarjeta sin armar cada caso de
 * uso; [of] los toma de los casos de uso reales.
 */
class PhraseWriters(
    val sale: suspend (SaleRequest) -> RegisterSaleResult,
    val purchase: suspend (PurchaseRequest) -> RegisterPurchaseResult,
    val expense: suspend (ExpenseRequest) -> RegisterExpenseResult,
    /**
     * Las producciones de una tarjeta, de uno o de varios productos, cada una con la receta que se
     * guarda antes si cambia, todas en una transacción.
     */
    val productions: suspend (List<ProductionStep>) -> RegisterProductionsResult,
    /** Los ajustes de una tarjeta, de una o de varias líneas, todos en una transacción. */
    val adjustments: suspend (List<StockAdjustmentRequest>) -> StockAdjustmentsResult,
    val customerPayment: suspend (CustomerPaymentRequest) -> RecordCustomerPaymentResult,
    val supplierPayment: suspend (SupplierPaymentRequest) -> PaySupplierResult,
    val cost: suspend (productId: Long, newCost: Money) -> CorrectCostResult,
    val price: suspend (productId: Long, newPrice: Money) -> ChangePriceResult,
    /** Crea un producto; con una categoría nueva por su nombre, la crea con él. */
    val product: suspend (ProductForm, newCategory: String?) -> SaveProductResult,
    val recipe: suspend (parentId: Long, lines: List<RecipeLine>) -> SaveRecipeResult,
) {
    companion object {
        /** Los de cada caso de uso real, tal como los arma el contenedor de la app. */
        fun of(
            sale: RegisterSaleUseCase,
            purchase: RegisterPurchaseUseCase,
            expense: RegisterExpenseUseCase,
            productions: RegisterProductionsUseCase,
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
            productions = { productions(it) },
            adjustments = { adjustment.all(it) },
            customerPayment = { customerPayment(it) },
            supplierPayment = { supplierPayment(it) },
            cost = { productId, newCost -> cost(productId, newCost) },
            price = { productId, newPrice -> price(productId, newPrice) },
            product = { form, newCategory -> product(form, newCategory = newCategory) },
            recipe = { parentId, lines -> recipe(parentId, lines) },
        )
    }
}
