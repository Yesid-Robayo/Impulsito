package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.open
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El detalle de una devolución a proveedor: su proveedor, sus líneas y su anulación. */
class ObserveSupplierReturnDetailUseCaseTest {
    private val world = SaleWorld().apply {
        addProduct(product(1, name = "Flour"))
        addSupplier(SUPPLIER)
    }

    private val observe = ObserveSupplierReturnDetailUseCase(
        purchaseReturns = world.purchaseReturnRepository,
        products = world.products,
        suppliers = world.supplierRepository,
        units = world.unitRepository,
    )

    private suspend fun returned(supplierId: Long?): Long = (
        world.registerSupplierReturn()(
            SupplierReturnRequest(
                lines = listOf(SupplierReturnLineRequest.Loose(1, QuantityMilli(1_000))),
                resolution = PurchaseReturnResolution.CASH_REFUND,
                supplierId = supplierId,
            ),
        ) as RegisterSupplierReturnResult.Registered
        ).purchaseReturnId

    @Test
    fun `a return shows its supplier, its lines and its void`() = runTest {
        world.open(1, 2_000, total = 2_000)
        val returnId = returned(SUPPLIER)

        val detail = requireNotNull(observe(returnId).first())
        assertEquals("Supplier $SUPPLIER", detail.supplierName)
        assertEquals(listOf("Flour"), detail.lines.map { it.productName })
        assertEquals(SaleWorld.UNIT, detail.lines.single().unit?.id)

        world.voidSupplierReturn()(returnId, VoidReason.ENTRY_ERROR, null)
        assertEquals(DocumentStatus.VOIDED, observe(returnId).first()?.purchaseReturn?.status)
    }

    @Test
    fun `a return without supplier or a missing one`() = runTest {
        world.open(1, 2_000, total = 2_000)
        val returnId = returned(null)

        assertNull(observe(returnId).first()?.supplierName)
        assertNull(observe(99).first())
    }

    private companion object {
        const val SUPPLIER = 7L
    }
}
