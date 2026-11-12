package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierFilter
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El historial de compras sobre la base real: tramos sin repetir ni saltar compras de la misma
 * fecha, el filtro de período, de proveedor, de `Sin proveedor` y de anuladas, lo comprado por día
 * sin las anuladas, y la marca que cambia con cada compra o anulación. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PurchaseHistorySourceTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val suppliers by lazy { RoomSupplierRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val source by lazy { RoomPurchaseHistorySource(database) }

    private val register by lazy {
        RegisterPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = purchases,
            suppliers = suppliers,
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { now },
        )
    }

    private val void by lazy {
        VoidPurchaseUseCase(runner, settings, products, purchases, ledger, Clock { now })
    }

    private suspend fun buy(
        productId: Long,
        units: Long,
        date: BusinessDate,
        supplierId: Long? = null,
        reference: String? = null,
    ): Long {
        val line = PurchaseLineRequest(
            productId,
            QuantityMilli(units * 1_000),
            PurchaseLineAmount.UnitCost(Money(100)),
        )
        val request = PurchaseRequest(listOf(line), supplierId, reference, date)
        return (register(request) as RegisterPurchaseResult.Registered).purchaseId
    }

    /** Un día y medio después de las filas de prueba: la fecha de ellas ya pasó en cualquier zona. */
    private val now = AT + 36.hours

    @Test
    fun pagesFiltersAndDayTotalsFollowTheLedger() = runBlocking {
        val productId = commit { products.insert(newProduct("Jabón inventado")) }
        val supplierId = commit {
            suppliers.insert(
                SupplierDetails(
                    "Proveedor inventado",
                    "proveedor inventado",
                    null,
                    null,
                    null,
                    null,
                ),
                AT,
            )
        }
        val older = DATE.plusDays(-1)
        val first = buy(productId, 1, older)
        val second = buy(productId, 2, DATE, supplierId, "F-0042")
        val third = buy(productId, 3, DATE)
        val marked = source.observeChanges().first()
        assertEquals(VoidPurchaseResult.Voided, void(third, VoidReason.ENTRY_ERROR, null))
        assertNotEquals(marked, source.observeChanges().first())

        // Dos tramos de dos: la fecha más nueva primero, y en la misma fecha el id mayor.
        val all = PurchaseHistoryFilter()
        val head = source.page(all, after = null, limit = 2)
        assertEquals(listOf(third, second), head.map { it.id })
        val tail = source.page(all, after = head.last().cursor, limit = 2)
        assertEquals(listOf(first), tail.map { it.id })

        val withSupplier = head.single { it.id == second }
        assertEquals("Proveedor inventado", withSupplier.supplierName)
        assertEquals("F-0042", withSupplier.invoiceReference)
        assertEquals(1, withSupplier.lineCount)
        assertEquals(Money(200), withSupplier.total)
        assertEquals(true, head.single { it.id == third }.voided)

        assertEquals(
            listOf(second),
            source.page(PurchaseHistoryFilter(supplier = SupplierFilter.Of(supplierId)), null, 10)
                .map { it.id },
        )
        assertEquals(
            listOf(third, first),
            source.page(PurchaseHistoryFilter(supplier = SupplierFilter.None), null, 10)
                .map { it.id },
        )
        assertEquals(
            listOf(third),
            source.page(PurchaseHistoryFilter(onlyVoided = true), null, 10).map { it.id },
        )
        assertEquals(
            listOf(first),
            source.page(PurchaseHistoryFilter(from = older, to = older), null, 10).map { it.id },
        )

        // La anulada no suma en su día.
        assertEquals(
            mapOf(DATE to Money(200), older to Money(100)),
            source.dayTotals(all, setOf(DATE, older)),
        )
        assertEquals(
            mapOf(older to Money(100)),
            source.dayTotals(
                PurchaseHistoryFilter(supplier = SupplierFilter.None),
                setOf(DATE, older),
            ),
        )
        assertEquals(emptyMap<BusinessDate, Money>(), source.dayTotals(all, emptySet()))
    }
}
