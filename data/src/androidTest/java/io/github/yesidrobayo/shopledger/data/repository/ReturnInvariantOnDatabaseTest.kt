package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaCatalog
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSaleReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La invariante de las devoluciones como prueba doble, sobre la base real: con devoluciones de
 * verdad, de a poco y hasta lo vendido, da cero filas; y detecta las dos corrupciones que dice, una
 * línea devuelta más de lo vendido y una venta anulada con su devolución activa, que solo pueden
 * llegar de una base alterada fuera de la app, porque las guardas no las dejan entrar.
 *
 * El producto y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ReturnInvariantOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val stock by lazy { RoomStockLedgerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val sales by lazy { RoomSaleRepository(database) }

    private var soapId = 0L

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = stock,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    private val giveBack by lazy {
        RegisterSaleReturnUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            returns = RoomSaleReturnRepository(database),
            units = RoomUnitRepository(database),
            ledger = stock,
            clock = Clock { AT },
        )
    }

    private val voidSale by lazy {
        VoidSaleUseCase(runner, settings, products, sales, stock, Clock { AT })
    }

    @Before
    fun createProduct() = runBlocking {
        soapId = commit {
            val id = products.insert(newProduct("Jabón inventado").copy(salePrice = Money(3_500)))
            stock.append(openingRow(id, 10))
            id
        }
    }

    private suspend fun sale(units: Long): Long {
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val request =
            SaleRequest(listOf(SaleLineRequest(soapId, QuantityMilli.ofUnits(units))), cash)
        return (registerSale(request) as RegisterSaleResult.Registered).saleId
    }

    private suspend fun giveBack(saleId: Long, units: Long): RegisterSaleReturnResult {
        val lineId = sales.findLines(saleId).single().id
        return giveBack(
            SaleReturnRequest(
                saleId,
                listOf(ReturnLineRequest(lineId, QuantityMilli.ofUnits(units))),
                ReturnSettlement.Refund(null),
            ),
        )
    }

    /** Las filas que da la consulta de la invariante. */
    private suspend fun violations(): Long {
        val query = InvariantQueries.MODEL.single { it.label == "I-19" }
        return count("SELECT COUNT(*) FROM (${query.sql.trimEnd().removeSuffix(";")})")
    }

    /** Corre [sql] sin las guardas, como lo haría quien altera la base fuera de la app. */
    private suspend fun withoutGuards(sql: String) {
        val guards = SchemaCatalog.current.triggers
        for (guard in guards) execute("DROP TRIGGER ${guard.name}")
        execute(sql)
        for (guard in guards) execute(guard.sql)
    }

    @Test
    fun realReturnsUpToWhatWasSoldLeaveNoRows() = runBlocking {
        val partly = sale(3)
        val whole = sale(2)
        giveBack(partly, 1)
        giveBack(partly, 1)
        giveBack(whole, 2)

        assertEquals(0L, violations())
        assertEquals(
            RegisterSaleReturnResult.AboveRemaining(0, QuantityMilli.ZERO),
            giveBack(whole, 1),
        )
        assertEquals(VoidSaleResult.HasActiveReturns, voidSale(whole, VoidReason.OTHER, null))
        assertEquals(0L, violations())
    }

    @Test
    fun aLineReturnedBeyondWhatWasSoldIsDetected() = runBlocking {
        val saleId = sale(3)
        giveBack(saleId, 2)
        assertEquals(0L, violations())

        withoutGuards("UPDATE sale_return_lines SET quantity_milli = 3001")

        assertEquals(1L, violations())
    }

    @Test
    fun aVoidedSaleWithAnActiveReturnIsDetected() = runBlocking {
        val saleId = sale(3)
        giveBack(saleId, 1)

        withoutGuards(
            "UPDATE sales SET status = 'VOIDED', voided_at = 1, void_reason = 'OTHER' " +
                "WHERE id = $saleId",
        )

        assertEquals(1L, violations())
    }
}
