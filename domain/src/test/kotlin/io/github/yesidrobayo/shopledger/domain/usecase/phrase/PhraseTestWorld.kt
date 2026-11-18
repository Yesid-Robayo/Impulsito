package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentAllocation
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionStep
import io.github.yesidrobayo.shopledger.domain.usecase.production.RegisterProductionsResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.measureUnit
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentsResult
import kotlin.time.Instant

/** Un cambio de costo o de precio pedido a un caso de uso de mentira. */
internal data class AmountCall(val productId: Long, val amount: Money)

/** Una receta pedida a un caso de uso de mentira. */
internal data class RecipeCall(val parentId: Long, val lines: List<RecipeLine>)

/**
 * Los casos de uso de mentira de una tarjeta: anotan cada petición en [requests], en orden, y
 * devuelven el resultado que la prueba elige. Por omisión cada uno registra con un id propio.
 */
internal class RecordingWriters {
    val requests = mutableListOf<Any>()

    /** La categoría nueva que se pidió crear con cada producto, `null` si ninguna. */
    val newCategories = mutableListOf<String?>()

    var sale: RegisterSaleResult = RegisterSaleResult.Registered(saleId = 11L)
    var purchase: RegisterPurchaseResult = RegisterPurchaseResult.Registered(12L, emptyList())
    var expense: RegisterExpenseResult = RegisterExpenseResult.Registered(13L)
    var productions: RegisterProductionsResult = RegisterProductionsResult.Registered(listOf(14L))
    var adjustments: StockAdjustmentsResult = StockAdjustmentsResult.Adjusted(listOf(15L))
    var customerPayment: RecordCustomerPaymentResult = RecordCustomerPaymentResult.Recorded(
        entryId = 16L,
        movementId = null,
        balanceAfter = Money(0),
        allocation = PaymentAllocation(emptyList(), Money(0)),
    )
    var supplierPayment: PaySupplierResult = PaySupplierResult.Paid(17L)
    var cost: CorrectCostResult = CorrectCostResult.Corrected(18L)
    var price: ChangePriceResult = ChangePriceResult.Changed(19L)
    var product: SaveProductResult = SaveProductResult.Saved(20L)
    var recipe: SaveRecipeResult = SaveRecipeResult.Saved

    fun writers(): PhraseWriters = PhraseWriters(
        sale = { request: SaleRequest ->
            requests += request
            sale
        },
        purchase = { request: PurchaseRequest ->
            requests += request
            purchase
        },
        expense = { request: ExpenseRequest ->
            requests += request
            expense
        },
        productions = { steps: List<ProductionStep> ->
            requests.addAll(steps)
            productions
        },
        adjustments = { list: List<StockAdjustmentRequest> ->
            requests.addAll(list)
            adjustments
        },
        customerPayment = { request: CustomerPaymentRequest ->
            requests += request
            customerPayment
        },
        supplierPayment = { request: SupplierPaymentRequest ->
            requests += request
            supplierPayment
        },
        cost = { productId, amount ->
            requests += AmountCall(productId, amount)
            cost
        },
        price = { productId, amount ->
            requests += AmountCall(productId, amount)
            price
        },
        product = { form: ProductForm, newCategory: String? ->
            requests += form
            newCategories += newCategory
            product
        },
        recipe = { parentId, lines ->
            requests += RecipeCall(parentId, lines)
            recipe
        },
    )
}

/**
 * Un negocio de prueba para las tarjetas, sobre la base en memoria: unidades iniciales con su
 * clave, una unidad propia, y lo que siembra cada prueba. Los nombres son inventados y ninguno
 * supone un país.
 */
internal class PhraseWorld {
    val database = InMemoryDatabase()
    val products = FakeProductRepository(database)
    val productUnits = FakeProductUnitRepository(database)
    val units = FakeUnitRepository(database)
    val components = FakeProductComponentRepository(database)
    val paymentMethods = FakePaymentMethodRepository(database)
    val writers = RecordingWriters()

    init {
        database.seed { tables ->
            tables.copy(
                units = listOf(
                    measureUnit(UNIT, "UNIT", UnitKind.COUNT, allowsFraction = false),
                    measureUnit(KILOGRAM, "KILOGRAM", UnitKind.MASS, allowsFraction = true),
                    measureUnit(GRAM, "GRAM", UnitKind.MASS, allowsFraction = true),
                    measureUnit(LITER, "LITER", UnitKind.VOLUME, allowsFraction = true),
                    measureUnit(POUND, "POUND", UnitKind.MASS, allowsFraction = true),
                    measureUnit(BOX, "BOX", UnitKind.COUNT, allowsFraction = false),
                    measureUnit(DOZEN, "DOZEN", UnitKind.COUNT, allowsFraction = false),
                    MeasureUnit(SACK, null, "Bulto", null, UnitKind.COUNT, false, null),
                ).associateBy { it.id },
            )
        }
    }

    fun register(): RegisterPhraseUseCase = RegisterPhraseUseCase(
        writers = writers.writers(),
        products = products,
        productUnits = productUnits,
        units = units,
        components = components,
        paymentMethods = paymentMethods,
    )

    fun addProducts(vararg added: Product) = database.seed { tables ->
        tables.copy(products = tables.products + added.associateBy { it.id })
    }

    fun addConversion(
        id: Long,
        productId: Long,
        unitId: Long,
        factorMilli: Long,
        salePrice: Long? = null,
        forSale: Boolean = true,
        forPurchase: Boolean = true,
    ) = database.seed { tables ->
        val details = ConversionDetails(
            unitId = unitId,
            factorMilli = factorMilli,
            salePrice = salePrice?.let(::Money),
            barcode = null,
            forSale = forSale,
            forPurchase = forPurchase,
        )
        tables.copy(
            productUnits = tables.productUnits + (id to ProductConversion(id, productId, details)),
        )
    }

    fun addRecipe(vararg rows: RecipeComponent) = database.seed { tables ->
        tables.copy(productComponents = tables.productComponents + rows.associateBy { it.id })
    }

    fun addMethods(vararg methods: PaymentMethodRecord) = database.seed { tables ->
        tables.copy(paymentMethods = tables.paymentMethods + methods.associateBy { it.id })
    }

    fun addSuppliers(vararg suppliers: Supplier) = database.seed { tables ->
        tables.copy(suppliers = tables.suppliers + suppliers.associateBy { it.id })
    }

    fun addCustomers(vararg customers: Customer) = database.seed { tables ->
        tables.copy(customers = tables.customers + customers.associateBy { it.id })
    }

    companion object {
        const val UNIT = 1L
        const val KILOGRAM = 2L
        const val GRAM = 3L
        const val LITER = 4L
        const val POUND = 5L
        const val BOX = 6L
        const val DOZEN = 7L
        const val SACK = 8L

        val EPOCH: Instant = Instant.fromEpochMilliseconds(0)
    }
}

/** Un proveedor de prueba, con nombre inventado. */
internal fun supplier(id: Long, name: String, archived: Boolean = false) = Supplier(
    id = id,
    details = SupplierDetails(name, name.lowercase(), null, null, null, null),
    balance = Money(0),
    archivedAt = if (archived) PhraseWorld.EPOCH else null,
    createdAt = PhraseWorld.EPOCH,
    updatedAt = PhraseWorld.EPOCH,
)

/** Un cliente de prueba, con nombre inventado; anonimizado no tiene nombre. */
internal fun customer(
    id: Long,
    name: String,
    archived: Boolean = false,
    anonymized: Boolean = false,
) = Customer(
    id = id,
    contact = CustomerContact(name, name.lowercase(), null, null, null).takeIf { !anonymized },
    priceListId = null,
    balance = Money(0),
    pointsBalance = 0L,
    anonymizedAt = if (anonymized) PhraseWorld.EPOCH else null,
    archivedAt = if (archived) PhraseWorld.EPOCH else null,
    createdAt = PhraseWorld.EPOCH,
    updatedAt = PhraseWorld.EPOCH,
)
