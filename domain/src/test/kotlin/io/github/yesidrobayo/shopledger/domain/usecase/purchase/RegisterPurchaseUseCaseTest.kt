package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCostChange
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.taxRate
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registrar una compra: suma stock, recalcula el costo promedio con el total de cada línea y deja
 * todo en una sola transacción. Los valores esperados están escritos a mano, con los ejemplos de
 * las reglas del costo y de la compra. Montos en unidad menor de una moneda cualquiera.
 */
class RegisterPurchaseUseCaseTest {
    private val world = SaleWorld()

    private suspend fun register(
        vararg lines: PurchaseLineRequest,
        supplierId: Long? = null,
        reference: String? = null,
        date: BusinessDate? = null,
    ): RegisterPurchaseResult =
        world.registerPurchase()(PurchaseRequest(lines.toList(), supplierId, reference, date))

    @Test
    fun `a purchase adds its quantity and points its row to the line`() = runTest {
        world.addProduct(product(1))
        world.open(1, 2_000, total = 2_000)

        val purchaseId = world.buy(atUnitCost(1, 10_000, cost = 1_000))

        assertEquals(QuantityMilli(12_000), world.tables.products.getValue(1).onHand)
        val row = world.movements().last()
        assertEquals(StockMovementType.PURCHASE, row.movement.type)
        assertEquals(QuantityMilli(10_000), row.movement.quantity)
        val line = world.tables.purchaseLines.values.single()
        assertEquals(purchaseId, line.purchaseId)
        assertEquals(StockMovementSource(purchaseLineId = line.id), row.source)
        assertEquals(QuantityMilli(12_000), row.balanceAfter)
    }

    @Test
    fun `the moving average follows the documented sequence`() = runTest {
        world.addProduct(product(1))

        world.buy(atUnitCost(1, 10_000, cost = 1_200))
        assertEquals(cost(10_000, 1_200, 12_000), world.costOf(1))

        world.buy(atUnitCost(1, 5_000, cost = 1_500))
        assertEquals(cost(15_000, 1_300, 19_500), world.costOf(1))

        // Una venta se lleva su parte del valor y no cambia el promedio.
        world.sell(1, 8_000)
        assertEquals(cost(7_000, 1_300, 9_100), world.costOf(1))

        world.buy(atUnitCost(1, 4_000, cost = 1_450))
        // 14900 × 1000 / 11000 = 1354,54: el promedio sube a 1355 y el valor no se redondea.
        assertEquals(cost(11_000, 1_355, 14_900), world.costOf(1))
    }

    @Test
    fun `a line total gives the unit cost and the average uses the total`() = runTest {
        world.addProduct(product(1, unitId = SaleWorld.KILOGRAM))
        world.open(1, 2_000, total = 16_000)

        world.buy(forTotal(1, 3_000, total = 25_000))

        val line = world.tables.purchaseLines.values.single().content
        assertEquals(Money(8_333), line.unitCost)
        assertEquals(Money(25_000), line.lineTotal)
        assertEquals(Money(25_000), line.costAmount)
        // Con el total, el valor suma 25000, no 8333 × 3 = 24999.
        assertEquals(cost(5_000, 8_200, 41_000), world.costOf(1))
        assertEquals(Money(8_333), world.movements().last().unitCost)
    }

    @Test
    fun `a unit cost gives the line total`() = runTest {
        world.addProduct(product(1))

        world.buy(atUnitCost(1, 10_000, cost = 1_200))

        val purchase = world.tables.purchases.values.single().content
        val line = world.tables.purchaseLines.values.single().content
        assertEquals(Money(1_200), line.unitCost)
        assertEquals(Money(12_000), line.lineTotal)
        assertEquals(Money(12_000), purchase.total)
        assertEquals(Money(12_000), purchase.netTotal)
        assertEquals(Money.ZERO, purchase.taxTotal)
        assertEquals(Money(12_000), purchase.costTotal)
        assertEquals(Money(12_000), purchase.amountPaid)
        assertEquals(DocumentStatus.ACTIVE, world.tables.purchases.values.single().status)
    }

    @Test
    fun `the cost edge cases of the moving average`() = runTest {
        // Cada fila: estado antes, lo que entra y el promedio, el valor y el saldo después.
        class Case(
            val onHand: Long,
            val average: Long,
            val value: Long,
            val milli: Long,
            val total: Long,
            val expected: Triple<Long, Long, Long>,
        )
        val cases = listOf(
            // Primera compra.
            Case(0, 0, 0, 10_000, 12_000, Triple(1_200, 12_000, 10_000)),
            // Stock en cero con costo anterior: no pesa.
            Case(0, 1_300, 0, 5_000, 8_000, Triple(1_600, 8_000, 5_000)),
            // Stock negativo: el costo anterior no pesa.
            Case(-2_000, 1_300, 0, 10_000, 15_000, Triple(1_500, 12_000, 8_000)),
            // La compra no cubre el negativo.
            Case(-5_000, 1_300, 0, 2_000, 3_000, Triple(1_500, 0, -3_000)),
            // Stock positivo sin costo: se trata como desconocido.
            Case(5_000, 0, 0, 5_000, 5_000, Triple(1_000, 10_000, 10_000)),
            // Bonificación gratis: baja el promedio.
            Case(10_000, 1_000, 10_000, 2_000, 0, Triple(833, 10_000, 12_000)),
            // Bonificación con stock en cero.
            Case(0, 0, 0, 3_000, 0, Triple(0, 0, 3_000)),
        )
        for ((index, case) in cases.withIndex()) {
            val id = index + 1L
            world.addProduct(
                product(
                    id,
                    onHandMilli = case.onHand,
                    averageCost = case.average,
                    stockValue = case.value,
                ),
            )
            world.buy(forTotal(id, case.milli, case.total))
            val (average, value, onHand) = case.expected
            assertEquals("Caso ${index + 1}", cost(onHand, average, value), world.costOf(id))
        }
    }

    @Test
    fun `a repeated product chains its cost line by line`() = runTest {
        world.addProduct(product(1))

        world.buy(atUnitCost(1, 10_000, cost = 1_000), atUnitCost(1, 10_000, cost = 2_000))

        val rows = world.movements()
        assertEquals(2, rows.size)
        assertEquals(cost(10_000, 1_000, 10_000).averageCost, rows[0].averageCostAfter)
        assertEquals(Money(1_500), rows[1].averageCostAfter)
        assertEquals(Money(30_000), rows[1].stockValueAfter)
        assertEquals(QuantityMilli(20_000), rows[1].balanceAfter)
        assertEquals(cost(20_000, 1_500, 30_000), world.costOf(1))
    }

    @Test
    fun `the result says how each product cost moved, once per product`() = runTest {
        world.addProduct(product(1, onHandMilli = 10_000, averageCost = 1_000, stockValue = 10_000))
        world.addProduct(product(2))

        val result = register(
            atUnitCost(1, 10_000, cost = 2_000),
            atUnitCost(2, 1_000, cost = 700),
            atUnitCost(1, 20_000, cost = 3_000),
        )

        val purchaseId = world.tables.purchases.keys.single()
        assertEquals(
            RegisterPurchaseResult.Registered(
                purchaseId,
                listOf(
                    // 10000 + 20000 + 60000 = 90000 por 40 unidades.
                    PurchaseCostChange(1, Money(1_000), Money(2_250)),
                    PurchaseCostChange(2, Money.ZERO, Money(700)),
                ),
            ),
            result,
        )
    }

    @Test
    fun `a purchase never changes the sale price nor the past sales`() = runTest {
        world.addProduct(product(1, salePrice = 2_500))
        world.open(1, 10_000, total = 10_000)
        val saleId = world.sell(1, 2_000)
        val sale = world.tables.lines.values.single { it.saleId == saleId }

        world.buy(atUnitCost(1, 10_000, cost = 3_000))

        assertEquals(Money(2_500), world.tables.products.getValue(1).salePrice)
        assertEquals(sale, world.tables.lines.values.single { it.saleId == saleId })
        assertEquals(1, world.tables.sales.size)
    }

    @Test
    fun `a supplier is optional and an active one is saved`() = runTest {
        world.addProduct(product(1))
        world.addSupplier(7)

        val without = world.buy(atUnitCost(1, 1_000, cost = 100))
        val with = world.buy(atUnitCost(1, 1_000, cost = 100), supplierId = 7)

        assertEquals(null, world.tables.purchases.getValue(without).content.supplierId)
        assertEquals(7L, world.tables.purchases.getValue(with).content.supplierId)
    }

    @Test
    fun `a missing or archived supplier is rejected`() = runTest {
        world.addProduct(product(1))
        world.addSupplier(8, archived = true)

        assertEquals(
            RegisterPurchaseResult.SupplierNotFound,
            register(atUnitCost(1, 1_000, cost = 100), supplierId = 9),
        )
        assertEquals(
            RegisterPurchaseResult.SupplierArchived,
            register(atUnitCost(1, 1_000, cost = 100), supplierId = 8),
        )
        assertTrue(world.tables.purchases.isEmpty())
    }

    @Test
    fun `the invoice reference is trimmed and capped`() = runTest {
        world.addProduct(product(1))

        register(atUnitCost(1, 1_000, cost = 100), reference = "  INV-0042 ")
        register(atUnitCost(1, 1_000, cost = 100), reference = "   ")

        val saved = world.tables.purchases.values.sortedBy { it.id }.map {
            it.content.invoiceReference
        }
        assertEquals(listOf("INV-0042", null), saved)
        assertEquals(
            RegisterPurchaseResult.InvoiceReferenceTooLong,
            register(atUnitCost(1, 1_000, cost = 100), reference = "R".repeat(41)),
        )
        assertEquals(2, world.tables.purchases.size)
    }

    @Test
    fun `a recoverable tax stays out of the cost`() = runTest {
        world.taxRates = mapOf(5L to taxRate(5, "Invented tax", 2_100))
        world.setTaxes(TaxMode.INCLUDED, defaultTaxRateId = 5)
        world.addProduct(product(1))
        world.addProduct(product(2))

        setRecoverable(true)
        world.buy(forTotal(1, 10_000, total = 12_100))
        setRecoverable(false)
        world.buy(forTotal(2, 10_000, total = 12_100))

        val (first, second) = world.tables.purchaseLines.values.sortedBy {
            it.id
        }.map { it.content }
        assertEquals(Money(10_000), first.costAmount)
        assertEquals(Money(2_100), first.taxAmount)
        assertEquals(BasisPoints(2_100), first.taxRate)
        assertEquals("Invented tax", first.taxNameSnapshot)
        assertEquals(Money(1_000), world.costOf(1).averageCost)
        assertEquals(Money(12_100), second.costAmount)
        assertEquals(Money(1_210), world.costOf(2).averageCost)
        // La fila del libro congela el costo que entró al stock, sin el impuesto recuperable.
        assertEquals(Money(1_000), world.movements().first().unitCost)
        val header = world.tables.purchases.values.minBy { it.id }.content
        assertEquals(Money(10_000), header.netTotal)
        assertEquals(Money(2_100), header.taxTotal)
        assertEquals(Money(12_100), header.total)
        assertEquals(Money(10_000), header.costTotal)
    }

    @Test
    fun `an added tax is summed on the written net and the product rate wins`() = runTest {
        world.taxRates = mapOf(
            5L to taxRate(5, "Default tax", 2_100),
            6L to taxRate(6, "Own tax", 1_000),
        )
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 5)
        setRecoverable(false)
        world.addProduct(product(1, taxRateId = 6))

        world.buy(atUnitCost(1, 10_000, cost = 1_000))

        val line = world.tables.purchaseLines.values.single().content
        assertEquals(Money(1_000), line.unitCost)
        assertEquals(Money(11_000), line.lineTotal)
        assertEquals(Money(1_000), line.taxAmount)
        assertEquals(Money(11_000), line.costAmount)
        assertEquals("Own tax", line.taxNameSnapshot)
        assertEquals(Money(1_100), world.costOf(1).averageCost)
    }

    @Test
    fun `without tax in the business no line carries one`() = runTest {
        world.taxRates = mapOf(5L to taxRate(5, "Default tax", 2_100))
        world.setTaxes(TaxMode.NONE, defaultTaxRateId = 5)
        world.addProduct(product(1, taxRateId = 5))
        world.addProduct(product(2))
        world.setTaxes(TaxMode.INCLUDED)

        world.buy(atUnitCost(2, 1_000, cost = 500))
        world.setTaxes(TaxMode.NONE, defaultTaxRateId = 5)
        world.buy(atUnitCost(1, 1_000, cost = 500))

        for (line in world.tables.purchaseLines.values.map { it.content }) {
            assertEquals(Money.ZERO, line.taxAmount)
            assertEquals(BasisPoints.ZERO, line.taxRate)
            assertEquals(null, line.taxNameSnapshot)
        }
    }

    @Test
    fun `a past date stamps noon of that day and a future one is rejected`() = runTest {
        world.addProduct(product(1))
        val past = BusinessDate(LocalDate(2026, 10, 30))

        val id = world.buy(atUnitCost(1, 1_000, cost = 100), date = past)

        val purchase = world.tables.purchases.getValue(id).content
        assertEquals(past, purchase.businessDate)
        // 12:00 en Etc/GMT+5 son las 17:00 UTC.
        assertEquals(Instant.parse("2026-10-30T17:00:00Z"), purchase.occurredAt)
        assertEquals(NOW, purchase.createdAt)
        val row = world.movements().single()
        assertEquals(past, row.businessDate)
        assertEquals(purchase.occurredAt, row.occurredAt)

        assertEquals(
            RegisterPurchaseResult.FutureDate,
            register(atUnitCost(1, 1_000, cost = 100), date = BusinessDate(LocalDate(2026, 11, 3))),
        )
        val today = world.buy(atUnitCost(1, 1_000, cost = 100), date = TODAY)
        assertEquals(NOW, world.tables.purchases.getValue(today).content.occurredAt)
    }

    @Test
    fun `the shape of the purchase is checked first`() = runTest {
        world.addProduct(product(1))

        assertEquals(RegisterPurchaseResult.EmptyPurchase, register())
        val tooMany = Array(201) { atUnitCost(1, 1_000, cost = 1) }
        assertEquals(RegisterPurchaseResult.TooManyLines, register(*tooMany))
        val most = Array(200) { atUnitCost(1, 1_000, cost = 1) }
        assertTrue(register(*most) is RegisterPurchaseResult.Registered)
    }

    @Test
    fun `each product problem rejects its line`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2, archived = true))
        world.addProduct(product(3, stockMode = ProductStockMode.UNTRACKED))
        world.addProduct(product(4, stockMode = ProductStockMode.COMPONENTS))
        world.addProduct(product(5, tracksLots = true))

        val ok = atUnitCost(1, 1_000, cost = 100)
        val expected = mapOf(
            9L to PurchaseLineRejection.PRODUCT_NOT_FOUND,
            2L to PurchaseLineRejection.PRODUCT_ARCHIVED,
            3L to PurchaseLineRejection.NOT_STOCKED,
            4L to PurchaseLineRejection.NOT_STOCKED,
            5L to PurchaseLineRejection.LOTS_NOT_SUPPORTED,
        )
        for ((productId, reason) in expected) {
            assertEquals(
                RegisterPurchaseResult.LineRejected(1, reason),
                register(ok, atUnitCost(productId, 1_000, cost = 100)),
            )
        }
        assertTrue(world.tables.purchases.isEmpty())
        assertTrue(world.tables.movements.isEmpty())
    }

    @Test
    fun `each quantity and amount problem rejects its line`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2, unitId = SaleWorld.KILOGRAM))

        val cases = listOf(
            atUnitCost(1, 0, cost = 100) to PurchaseLineRejection.INVALID_QUANTITY,
            atUnitCost(1, -1_000, cost = 100) to PurchaseLineRejection.INVALID_QUANTITY,
            atUnitCost(2, 1_000_000_001, cost = 1) to PurchaseLineRejection.INVALID_QUANTITY,
            atUnitCost(1, 1_500, cost = 100) to PurchaseLineRejection.FRACTION_NOT_ALLOWED,
            atUnitCost(1, 1_000, cost = -1) to PurchaseLineRejection.NEGATIVE_AMOUNT,
            forTotal(1, 1_000, total = -1) to PurchaseLineRejection.NEGATIVE_AMOUNT,
            atUnitCost(1, 1_000, cost = 1_000_000_000_001) to
                PurchaseLineRejection.UNIT_COST_TOO_LARGE,
            // Un gramo por un monto enorme da un costo por kilogramo imposible.
            forTotal(2, 1, total = 1_000_000_001) to PurchaseLineRejection.UNIT_COST_TOO_LARGE,
            forTotal(1, 1_000, total = 1_000_000_000_000_001) to
                PurchaseLineRejection.LINE_TOTAL_TOO_LARGE,
            atUnitCost(2, 1_000_000_000, cost = 1_000_000_000_000) to
                PurchaseLineRejection.LINE_TOTAL_TOO_LARGE,
        )
        for ((line, reason) in cases) {
            assertEquals(RegisterPurchaseResult.LineRejected(0, reason), register(line))
        }
        // Una fracción en una unidad que la admite, y una bonificación en cero, sí valen.
        assertTrue(register(atUnitCost(2, 1_500, cost = 100)) is RegisterPurchaseResult.Registered)
        assertTrue(register(forTotal(1, 1_000, total = 0)) is RegisterPurchaseResult.Registered)
    }

    @Test
    fun `an added tax that pushes the line over the cap is rejected`() = runTest {
        world.taxRates = mapOf(5L to taxRate(5, "Invented tax", 10_000))
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 5)
        world.addProduct(product(1))

        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.LINE_TOTAL_TOO_LARGE),
            register(forTotal(1, 1_000_000, total = 600_000_000_000_000)),
        )
    }

    @Test
    fun `a non recoverable added tax over the unit cap is rejected`() = runTest {
        world.taxRates = mapOf(5L to taxRate(5, "Invented tax", 10_000))
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 5)
        setRecoverable(false)
        world.addProduct(product(1))

        // Un millón de millones por unidad es el tope; con el impuesto al doble, lo pasa.
        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.UNIT_COST_TOO_LARGE),
            register(atUnitCost(1, 1_000, cost = 1_000_000_000_000)),
        )
    }

    @Test
    fun `stock or value over their caps reject the line`() = runTest {
        world.addProduct(product(1, onHandMilli = 7_999_999_000))
        world.addProduct(
            product(2, onHandMilli = 1_000, averageCost = 1, stockValue = 8_999_999_999_999_999),
        )

        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.STOCK_ABOVE_LIMIT),
            register(atUnitCost(1, 2_000, cost = 1)),
        )
        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.STOCK_ABOVE_LIMIT),
            register(forTotal(2, 1_000, total = 1_000)),
        )
        // El tope exacto del stock sí entra.
        assertTrue(register(atUnitCost(1, 1_000, cost = 1)) is RegisterPurchaseResult.Registered)
    }

    @Test
    fun `a stock value that does not fit in a long rejects the line`() = runTest {
        // Un valor imposible, como el de una copia dañada: sumarle la compra no cabe en Long.
        world.addProduct(
            product(1, onHandMilli = 1_000, averageCost = 1, stockValue = Long.MAX_VALUE - 10),
        )

        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.STOCK_ABOVE_LIMIT),
            register(forTotal(1, 1_000, total = 1_000)),
        )
    }

    @Test
    fun `an average cost over the unit cap rejects the line`() = runTest {
        world.addProduct(
            product(1, unitId = SaleWorld.KILOGRAM, onHandMilli = 1, stockValue = 2_000_000_000),
        )

        // La línea cuesta justo el tope por kilogramo, y el valor cabe; pero 3000000000 sobre dos
        // gramos da un promedio de 1500000000000 por kilogramo, sobre el tope de un costo.
        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.STOCK_ABOVE_LIMIT),
            register(forTotal(1, 1, total = 1_000_000_000)),
        )
    }

    @Test
    fun `the sum of the lines over the document cap is rejected`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2))

        assertEquals(
            RegisterPurchaseResult.PurchaseTotalTooLarge,
            register(
                forTotal(1, 1_000_000, total = 600_000_000_000_000),
                forTotal(2, 1_000_000, total = 600_000_000_000_000),
            ),
        )
    }

    @Test
    fun `the first purchase locks the currency and an unconfirmed one rejects`() = runTest {
        val unlocked = SaleWorld(currencyLocked = false).apply { addProduct(product(1)) }
        unlocked.buy(atUnitCost(1, 1_000, cost = 100))
        assertEquals(listOf(NOW), unlocked.settings.lockCalls)

        val unconfirmed = SaleWorld(currencyConfirmed = false).apply { addProduct(product(1)) }
        val result = unconfirmed.registerPurchase()(
            PurchaseRequest(listOf(atUnitCost(1, 1_000, cost = 100))),
        )
        assertEquals(RegisterPurchaseResult.CurrencyNotConfirmed, result)
        assertTrue(unconfirmed.tables.purchases.isEmpty())
    }

    @Test
    fun `an unknown zone rejects without writing`() = runTest {
        val lost = SaleWorld(zoneId = "Invented/Zone").apply { addProduct(product(1)) }

        val result =
            lost.registerPurchase()(PurchaseRequest(listOf(atUnitCost(1, 1_000, cost = 1))))

        assertEquals(RegisterPurchaseResult.UnknownTimeZone("Invented/Zone"), result)
        assertTrue(lost.tables.purchases.isEmpty())
    }

    @Test
    fun `a failure at any write leaves nothing`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2))
        val before = world.tables
        // Cabecera, y por línea: su id y la línea, y la fila con su id y el producto. Después, la
        // reposición de cada producto.
        val writes = 2 + 2 * 5 + 2
        for (failAt in 1..writes) {
            world.failAtWrite = failAt
            val failed = runCatching {
                register(atUnitCost(1, 1_000, cost = 100), atUnitCost(2, 1_000, cost = 200))
            }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertEquals(before, world.tables)
        }
        world.failAtWrite = null
        assertTrue(
            register(atUnitCost(1, 1_000, cost = 100), atUnitCost(2, 1_000, cost = 200))
                is RegisterPurchaseResult.Registered,
        )
        assertEquals(1, world.commits)
    }

    @Test
    fun `a storage failure comes back as a result`() = runTest {
        world.addProduct(product(1))
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            RegisterPurchaseResult.StorageFailed(StorageFailure.DISK_FULL),
            register(atUnitCost(1, 1_000, cost = 100)),
        )
        assertTrue(world.tables.purchases.isEmpty())
    }

    @Test
    fun `a missing tax rate means a damaged database`() = runTest {
        world.setTaxes(TaxMode.INCLUDED, defaultTaxRateId = 99)
        world.addProduct(product(1))

        assertEquals(
            RegisterPurchaseResult.StorageFailed(StorageFailure.INVARIANT_VIOLATION),
            register(atUnitCost(1, 1_000, cost = 100)),
        )
    }

    @Test
    fun `a line amount written as a unit cost or a total gives the same row`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2))

        world.buy(
            PurchaseLineRequest(1, QuantityMilli(4_000), PurchaseLineAmount.UnitCost(Money(250))),
        )
        world.buy(
            PurchaseLineRequest(
                2,
                QuantityMilli(4_000),
                PurchaseLineAmount.LineTotal(Money(1_000)),
            ),
        )

        val (byCost, byTotal) = world.tables.purchaseLines.values.sortedBy { it.id }.map {
            it.content.copy(productId = 0)
        }
        assertEquals(byCost, byTotal)
    }

    private fun setRecoverable(recoverable: Boolean) {
        val current = world.settings.settings.value
        world.settings.settings.value =
            current.copy(taxes = current.taxes.copy(purchaseTaxRecoverable = recoverable))
    }
}
