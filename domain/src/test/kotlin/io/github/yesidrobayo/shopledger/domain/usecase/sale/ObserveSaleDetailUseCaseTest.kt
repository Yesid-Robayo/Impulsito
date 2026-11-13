package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El detalle de una venta: cabecera, líneas y pago, que se ve anulada al anularla sin reabrir, y
 * marca las líneas de un producto que hoy está archivado. Productos inventados.
 */
class ObserveSaleDetailUseCaseTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addProduct(product(1L, salePrice = 3_500L, onHandMilli = 5_000L, stockValue = 6_000L))
        addProduct(product(2L, salePrice = 1_000L, stockMode = ProductStockMode.UNTRACKED))
    }
    private val archived = MutableStateFlow<List<Product>>(emptyList())
    private val products = object : ProductRepository by world.products {
        override fun observe(filter: ArchiveFilter): Flow<List<Product>> = archived
    }
    private val observe = ObserveSaleDetailUseCase(world.sales, world.payments, products)

    private suspend fun sell(): Long {
        val request = SaleRequest(
            listOf(
                SaleLineRequest(1L, QuantityMilli(2_000L)),
                SaleLineRequest(2L, QuantityMilli(1_000L)),
            ),
            SaleWorld.CASH,
        )
        return (world.registerSale()(request) as RegisterSaleResult.Registered).saleId
    }

    @Test
    fun `the detail has the sale, its lines in order and its payment`() = runTest {
        val saleId = sell()

        val detail = requireNotNull(observe(saleId).first())

        assertEquals(saleId, detail.sale.id)
        assertEquals(listOf(1L, 2L), detail.lines.map { it.content.productId })
        assertEquals(Money(8_000L), detail.payments.single().content.amount)
        assertEquals(emptySet<Long>(), detail.archivedProductIds)
    }

    @Test
    fun `a voided sale shows as voided, and an archived product is marked`() = runTest {
        val saleId = sell()
        world.voidSale()(saleId, VoidReason.CUSTOMER_RETURN, null)
        archived.value = listOf(world.tables.products.getValue(2L), product(9L))

        val detail = requireNotNull(observe(saleId).first())

        assertEquals(DocumentStatus.VOIDED, detail.sale.status)
        assertEquals(VoidReason.CUSTOMER_RETURN, detail.sale.voiding?.reason)
        assertEquals(setOf(2L), detail.archivedProductIds)
    }

    @Test
    fun `a sale that does not exist has no detail`() = runTest {
        assertNull(observe(404L).first())
    }

    @Test
    fun `a return shows in the detail, and the sale is no longer offered to void`() = runTest {
        val saleId = sell()
        val withReturns =
            ObserveSaleDetailUseCase(world.sales, world.payments, products, world.saleReturns)
        val soapLine = world.tables.lines.values.first { it.content.productId == 1L }
        world.registerSaleReturn()(
            SaleReturnRequest(
                saleId,
                listOf(ReturnLineRequest(soapLine.id, QuantityMilli(1_000L))),
                ReturnSettlement.Refund(null),
            ),
        )

        val detail = requireNotNull(withReturns(saleId).first())

        assertEquals(1, detail.returns.size)
        assertEquals(QuantityMilli(1_000L), detail.returned.getValue(soapLine.id).quantity)
        assertEquals(QuantityMilli(1_000L), detail.remainingOf(soapLine))
        assertEquals(false, detail.canBeVoided)
        assertEquals(true, detail.canBeReturned)
        // Sin el libro de devoluciones, el detalle no las ve.
        assertEquals(emptyList<Any>(), requireNotNull(observe(saleId).first()).returns)
    }
}
