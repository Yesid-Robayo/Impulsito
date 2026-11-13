package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDetails
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.sale.DiscardHeldSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HeldSaleChangeResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RenameHeldSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ResumeHeldSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ResumeHeldSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las ventas en espera sobre Room, con sus guardas y el runner de la app: guardar, renombrar y
 * descartar una espera dejan `change_seq` y `first_unsynced_at` como estaban, porque no son hechos
 * del negocio. Confirmar la venta retomada borra la espera en su misma transacción, que sí cuenta.
 * Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class HeldSalesOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val heldSales by lazy { RoomHeldSaleRepository(database) }
    private val clock = Clock { AT }

    private val hold by lazy { HoldSaleUseCase(runner, heldSales, products, clock) }
    private val resume by lazy {
        ResumeHeldSaleUseCase(runner, heldSales, products, RoomPriceHistoryRepository(database))
    }
    private val rename by lazy { RenameHeldSaleUseCase(runner, heldSales, Clock { LATER_AT }) }
    private val discard by lazy { DiscardHeldSaleUseCase(runner, heldSales) }

    private val register by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = RoomAppSettingsRepository(database, RestoreLock { false }),
            products = products,
            sales = RoomSaleRepository(database),
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = RoomStockLedgerRepository(database),
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = clock,
            heldSales = heldSales,
        )
    }

    /** Lo que mira el respaldo: los cambios sin respaldar y desde cuándo. */
    private suspend fun backupMarks(): Pair<Long, Long?> {
        val state = requireNotNull(database.backupStateDao().get())
        return state.changeSeq to state.firstUnsyncedAt
    }

    private suspend fun product(name: String): Long = commit { products.insert(newProduct(name)) }

    private suspend fun held(request: HoldSaleRequest): Long =
        (hold(request) as HoldSaleResult.Held).heldSaleId

    @Test
    fun holdingRenamingAndDiscardingAreNotChanges() = runBlocking {
        val soap = product("Jabón inventado")
        val before = backupMarks()

        val id = held(
            HoldSaleRequest(
                listOf(
                    SaleLineRequest(soap, QuantityMilli(2_000)),
                    SaleLineRequest(
                        soap,
                        QuantityMilli(1_000),
                        Money(3_000),
                        LineDiscount.Percent(BasisPoints(1_000)),
                    ),
                ),
                "Mesa 4",
            ),
        )
        assertEquals(before, backupMarks())

        val stored = requireNotNull(heldSales.findById(id))
        assertEquals("Mesa 4", stored.label)
        assertEquals(listOf(null, Money(3_000)), stored.lines.map { it.unitPrice })
        // El 10 % de 3000 se guarda como 300: la base no guarda porcentajes.
        assertEquals(listOf(Money.ZERO, Money(300)), stored.lines.map { it.discount })

        assertEquals(HeldSaleChangeResult.Changed, rename(id, "Mesa 7"))
        assertEquals("Mesa 7", heldSales.findById(id)?.label)
        assertEquals(LATER_AT, heldSales.findById(id)?.updatedAt)
        assertEquals(before, backupMarks())

        assertEquals(HeldSaleChangeResult.Changed, discard(id))
        assertNull(heldSales.findById(id))
        assertEquals(0L, count("SELECT COUNT(*) FROM sale_draft_lines"))
        assertEquals(before, backupMarks())
    }

    @Test
    fun aHeldSaleIsSavedAgainOverItself() = runBlocking {
        val soap = product("Jabón inventado")
        val tea = product("Té inventado")
        val id = held(HoldSaleRequest(listOf(SaleLineRequest(soap, QuantityMilli(1_000))), null))

        val again = hold(
            HoldSaleRequest(listOf(SaleLineRequest(tea, QuantityMilli(3_000))), "Mesa 2", id),
        )

        assertEquals(HoldSaleResult.Held(id), again)
        val stored = requireNotNull(heldSales.findById(id))
        assertEquals(listOf(tea), stored.lines.map { it.productId })
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_drafts"))
        val resumed = (resume(id) as ResumeHeldSaleResult.Resumed).sale
        assertEquals(listOf(SaleLineRequest(tea, QuantityMilli(3_000))), resumed.lines)
    }

    @Test
    fun theListOfTheWaitIsStoredAndComesBack() = runBlocking {
        val soap = product("Jabón inventado")
        val lists = RoomPriceListRepository(database)
        val wholesale = commit {
            lists.insert(
                PriceListDetails(
                    "Mayorista inventada",
                    "mayorista inventada",
                    PriceListPricing.Fixed,
                ),
                AT,
            )
        }
        val withLists = ResumeHeldSaleUseCase(
            runner,
            heldSales,
            products,
            RoomPriceHistoryRepository(database),
            lists,
        )

        val id = held(
            HoldSaleRequest(
                listOf(SaleLineRequest(soap, QuantityMilli(1_000))),
                null,
                null,
                wholesale,
            ),
        )

        assertEquals(wholesale, heldSales.findById(id)?.priceListId)
        assertTrue(lists.isInUse(wholesale))
        assertEquals(wholesale, (withLists(id) as ResumeHeldSaleResult.Resumed).sale.priceListId)
        // Guardarla otra vez con la inicial deja la espera sin lista.
        held(HoldSaleRequest(listOf(SaleLineRequest(soap, QuantityMilli(2_000))), null, id))
        assertNull(heldSales.findById(id)?.priceListId)
    }

    @Test
    fun theSaleItBecameTakesItOutOfTheWaitInTheSameTransaction() = runBlocking {
        val soap = commit {
            val id = products.insert(newProduct("Jabón inventado"))
            RoomStockLedgerRepository(database).append(openingRow(id, units = 10))
            id
        }
        val id = held(HoldSaleRequest(listOf(SaleLineRequest(soap, QuantityMilli(2_000))), null))
        val before = changeSeq()
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")

        val result = register(
            SaleRequest(listOf(SaleLineRequest(soap, QuantityMilli(2_000))), cash, heldSaleId = id),
        )

        assertTrue(result is RegisterSaleResult.Registered)
        assertNull(heldSales.findById(id))
        assertEquals(before + 1, changeSeq())
    }

    @Test
    fun theListFollowsEveryChange() = runBlocking {
        val soap = product("Jabón inventado")
        heldSales.observeAll().test {
            assertEquals(emptyList<Any>(), awaitItem())
            val id = held(
                HoldSaleRequest(listOf(SaleLineRequest(soap, QuantityMilli(1_000))), "Mesa 1"),
            )
            val listed = awaitItem().single()
            assertEquals(id, listed.id)
            assertEquals(1, listed.lines.size)
            discard(id)
            assertEquals(emptyList<Any>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
