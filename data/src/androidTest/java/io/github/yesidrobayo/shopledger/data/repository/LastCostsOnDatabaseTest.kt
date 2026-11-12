package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo último pagado a cada proveedor por un producto, sobre la base real: una fila por proveedor y
 * una por las compras sin proveedor, de la línea más reciente de una compra activa, y una compra
 * anulada no cuenta. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class LastCostsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val suppliers by lazy { RoomSupplierRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    /** Un día y medio después de las filas de prueba: la fecha de ellas ya pasó en cualquier zona. */
    private val now = AT + 36.hours

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

    private suspend fun buy(productId: Long, cost: Long, supplierId: Long?): Long {
        val line = PurchaseLineRequest(
            productId,
            QuantityMilli(1_000),
            PurchaseLineAmount.UnitCost(Money(cost)),
        )
        val result = register(PurchaseRequest(listOf(line), supplierId, date = DATE))
        return (result as RegisterPurchaseResult.Registered).purchaseId
    }

    @Test
    fun eachSupplierShowsItsLatestActiveLine() = runBlocking {
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
        buy(productId, cost = 100, supplierId = supplierId)
        val latest = buy(productId, cost = 120, supplierId = supplierId)
        val voided = buy(productId, cost = 900, supplierId = supplierId)
        VoidPurchaseUseCase(runner, settings, products, purchases, ledger, Clock { now })(
            voided,
            VoidReason.ENTRY_ERROR,
            null,
        )
        val withoutSupplier = buy(productId, cost = 90, supplierId = null)

        val costs = purchases.observeLastCosts(productId).first()

        // Las dos del mismo día: primero la línea más reciente.
        assertEquals(listOf(withoutSupplier, latest), costs.map { it.purchaseId })
        assertEquals(listOf(null, "Proveedor inventado"), costs.map { it.supplierName })
        assertEquals(listOf(Money(90), Money(120)), costs.map { it.unitCost })
        assertEquals(DATE, costs.last().businessDate)
    }
}
