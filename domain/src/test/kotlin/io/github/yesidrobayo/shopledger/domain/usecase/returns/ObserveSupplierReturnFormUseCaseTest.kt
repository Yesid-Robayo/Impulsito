package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.buy
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo que la devolución a proveedor lee para armarse: los productos que se pueden devolver sueltos,
 * los proveedores, el elegido con su saldo, y la compra de origen con lo ya devuelto de cada línea.
 */
class ObserveSupplierReturnFormUseCaseTest {
    private val world = SaleWorld().apply {
        addProduct(product(1))
        addProduct(product(2, stockMode = ProductStockMode.UNTRACKED))
        addProduct(product(3, tracksLots = true))
        addProduct(product(4, archived = true))
        addSupplier(SUPPLIER)
        addSupplier(ARCHIVED, archived = true)
    }

    /** Los productos y proveedores de la prueba, leídos como lo haría la base. */
    private val products = object : ProductRepository by world.products {
        override fun observe(filter: ArchiveFilter): Flow<List<Product>> = flowOf(
            world.tables.products.values.filter {
                (filter == ArchiveFilter.ACTIVE) == !it.isArchived
            }.sortedBy { it.id },
        )
    }

    private val suppliers = object : SupplierRepository by world.supplierRepository {
        override fun observe(filter: ArchiveFilter): Flow<List<Supplier>> = flowOf(
            world.tables.suppliers.values.filter {
                (filter == ArchiveFilter.ACTIVE) == !it.isArchived
            }.sortedBy { it.id },
        )
    }

    private val observe = ObserveSupplierReturnFormUseCase(
        products = products,
        units = world.unitRepository,
        suppliers = suppliers,
        purchases = world.purchases,
        purchaseReturns = world.purchaseReturnRepository,
    )

    @Test
    fun `in blank it offers the products that can leave loose and the active suppliers`() =
        runTest {
            val form = observe(purchaseId = null, supplierId = ARCHIVED).first()

            assertEquals(listOf(1L), form.products.map { it.id })
            assertEquals(listOf(SUPPLIER), form.suppliers.map { it.id })
            // El elegido llega aunque esté archivado: su saldo sigue contando.
            assertEquals(ARCHIVED, form.supplier?.id)
            assertNull(form.purchase)
            assertEquals(setOf(SaleWorld.UNIT, SaleWorld.KILOGRAM), form.units.keys)
        }

    @Test
    fun `a purchase brings each line with what was already returned`() = runTest {
        val purchaseId = world.buy(
            atUnitCost(1, 3_000, cost = 1_000),
            atUnitCost(1, 2_000, cost = 1_200),
            supplierId = SUPPLIER,
        )
        val (first, second) = world.tables.purchaseLines.values.sortedBy { it.id }
        world.registerSupplierReturn()(
            SupplierReturnRequest(
                lines = listOf(
                    SupplierReturnLineRequest.FromPurchaseLine(first.id, QuantityMilli(1_000)),
                ),
                resolution = PurchaseReturnResolution.SUPPLIER_CREDIT,
                purchaseId = purchaseId,
            ),
        )

        val purchase = requireNotNull(observe(purchaseId, SUPPLIER).first().purchase)

        assertEquals(purchaseId, purchase.purchase.id)
        assertEquals(
            listOf(QuantityMilli(1_000), QuantityMilli.ZERO),
            purchase.lines.map { it.returned },
        )
        assertEquals(
            listOf(QuantityMilli(2_000), QuantityMilli(2_000)),
            purchase.lines.map { it.remaining },
        )
        assertEquals(listOf(first.id, second.id), purchase.lines.map { it.line.id })
        assertEquals(1L, purchase.lines.first().product.id)
    }

    @Test
    fun `a voided or missing purchase is not offered`() = runTest {
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 1_000))
        world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        assertNull(observe(purchaseId, null).first().purchase)
        assertNull(observe(99, null).first().purchase)
    }

    @Test
    fun `the form follows the stock and the returns as they change`() = runTest {
        val purchaseId = world.buy(atUnitCost(1, 2_000, cost = 1_000), supplierId = SUPPLIER)
        val lineId = world.tables.purchaseLines.values.single().id
        val returned = observe(purchaseId, SUPPLIER).map { form ->
            form.purchase?.lines?.single()?.returned
        }
        assertEquals(QuantityMilli.ZERO, returned.first())

        world.registerSupplierReturn()(
            SupplierReturnRequest(
                lines = listOf(
                    SupplierReturnLineRequest.FromPurchaseLine(lineId, QuantityMilli(2_000)),
                ),
                resolution = PurchaseReturnResolution.CASH_REFUND,
                purchaseId = purchaseId,
            ),
        )

        assertEquals(QuantityMilli(2_000), returned.first())
    }

    private companion object {
        const val SUPPLIER = 7L
        const val ARCHIVED = 8L
    }
}
