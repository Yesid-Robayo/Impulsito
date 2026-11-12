package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La alerta de stock bajo en la misma transacción que la venta, sobre Room y sus guardas: una venta
 * con dos líneas del mismo producto que cruza el mínimo deja una sola alerta pendiente, en el mismo
 * cambio contado que la venta, y anularla la resuelve. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class LowStockAlertsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val alerts by lazy { RoomLowStockAlertRepository(database, RestoreLock { false }) }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
            alerts = alerts,
        )
    }

    private val voidSale by lazy {
        VoidSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            ledger = ledger,
            clock = Clock { LATER_AT },
            alerts = alerts,
        )
    }

    @Test
    fun aSaleThatCrossesLeavesOnePendingAlertAndItsVoidResolvesIt() = runBlocking {
        val id = commit {
            val id = products.insert(
                newProduct("Jabón inventado", productDetails("Jabón inventado", minStock = 5_000)),
            )
            ledger.append(openingRow(id, units = 10))
            id
        }
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val before = changeSeq()
        val request = SaleRequest(
            listOf(
                SaleLineRequest(id, QuantityMilli(3_000L)),
                SaleLineRequest(id, QuantityMilli(3_000L)),
            ),
            method,
        )

        val saleId = (registerSale(request) as RegisterSaleResult.Registered).saleId

        assertEquals(before + 1, changeSeq())
        val alert = alerts.findActiveByProducts(listOf(id)).single()
        assertEquals(LowStockNotificationStatus.PENDING, alert.notificationStatus)
        assertEquals(QuantityMilli(4_000L), alert.onHandAtTrigger)
        assertEquals(QuantityMilli(5_000L), alert.minStockAtTrigger)
        assertEquals(1L, count("SELECT COUNT(*) FROM low_stock_alerts WHERE product_id = $id"))

        assertEquals(VoidSaleResult.Voided, voidSale(saleId, VoidReason.ENTRY_ERROR, null))

        assertTrue(alerts.findActiveByProducts(listOf(id)).isEmpty())
        val resolved = count("SELECT COUNT(*) FROM low_stock_alerts WHERE resolved_at IS NOT NULL")
        assertEquals(1L, resolved)
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
