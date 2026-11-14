package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewHeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveModifiersResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveModifiersUseCase
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
 * Vender, anular y poner en espera con extras sobre Room, con sus guardas y llaves: un extra repite
 * producto en la misma venta sin chocar con el índice de las líneas, guarda el id de su línea
 * principal, mueve stock como cualquier línea y la anulación lo compensa. Los extras de cada
 * producto se guardan en su orden, y la espera conserva el enlace. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ExtrasSaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val modifiers by lazy { RoomProductModifierRepository(database) }
    private val held by lazy { RoomHeldSaleRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val register by lazy {
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
        )
    }

    private val void by lazy {
        VoidSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            ledger = ledger,
            clock = Clock { AT },
        )
    }

    @Test
    fun anExtraRepeatsAProductInTheSameSaleAndVoidingCompensatesEveryLine() = runBlocking {
        val menu = arrangeMenu()
        // Dos arepas con queso, un té con queso y una porción de queso suelta.
        val request = SaleRequest(
            listOf(
                line(menu.arepa, 2_000),
                line(menu.cheese, 2_000, parentIndex = 0),
                line(menu.sauce, 2_000, parentIndex = 0),
                line(menu.tea, 1_000),
                line(menu.cheese, 1_000, parentIndex = 3),
                line(menu.cheese, 1_000),
            ),
            cash(),
        )

        val saleId = (register(request) as RegisterSaleResult.Registered).saleId

        val stored = sales.findLines(saleId)
        val ids = stored.map { it.id }
        assertEquals(
            listOf(null, ids[0], ids[0], null, ids[3], null),
            stored.map { it.content.parentLineId },
        )
        assertEquals(3, stored.count { it.content.productId == menu.cheese })
        // 10 porciones menos 2, 1 y 1.
        assertEquals(QuantityMilli(6_000), requireNotNull(products.findById(menu.cheese)).onHand)
        assertEquals(5, ledger.findBySale(saleId).size)
        assertInvariantsHold()

        assertTrue(void(saleId, VoidReason.ENTRY_ERROR, null) is VoidSaleResult.Voided)

        assertEquals(10, ledger.findBySale(saleId).size)
        assertEquals(QuantityMilli(10_000), requireNotNull(products.findById(menu.cheese)).onHand)
        assertInvariantsHold()
    }

    @Test
    fun theGuardKeepsAnExtraInItsSaleAndOneLevelDeep() = runBlocking {
        val menu = arrangeMenu()
        val first = sell(line(menu.arepa, 1_000))
        val foreignLine = sales.findLines(first).single().id
        val second = sell(line(menu.tea, 1_000), line(menu.cheese, 1_000, parentIndex = 0))
        val extraLine = sales.findLines(second).last().id
        val list = defaultPriceList()

        // La llave existe, pero la línea es de otra venta, o es a su vez un extra: la guarda, que
        // repite lo que revisa la venta, no deja colgar un extra de ella.
        for (parent in listOf(foreignLine, extraLine)) {
            val failure = storageFailureOf {
                commit {
                    val extra = newSaleLine(menu.arepa, list).copy(parentLineId = parent)
                    sales.insertLine(second, extra)
                }
            }
            assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        }
    }

    private suspend fun sell(vararg lines: SaleLineRequest): Long =
        (register(SaleRequest(lines.toList(), cash())) as RegisterSaleResult.Registered).saleId

    @Test
    fun extrasAreSavedInTheirOrderAndAProductIsNotItsOwnExtra() = runBlocking {
        val menu = arrangeMenu()
        val save = SaveModifiersUseCase(runner, products, modifiers, Clock { AT })

        assertEquals(SaveModifiersResult.Saved, save(menu.arepa, listOf(menu.sauce, menu.cheese)))
        assertEquals(SaveModifiersResult.Saved, save(menu.arepa, listOf(menu.cheese, menu.tea)))

        val stored = modifiers.findByProduct(menu.arepa)
        assertEquals(listOf(menu.cheese, menu.tea), stored.map { it.modifierProductId })
        assertEquals(listOf(0L, 1L), stored.map { it.sortOrder })
        // La guarda repite lo que revisa el caso de uso.
        val failure = storageFailureOf {
            commit { modifiers.insert(menu.arepa, menu.arepa, 5L, AT) }
        }
        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
    }

    @Test
    fun aHeldSaleKeepsItsExtrasAndLosesThemWithTheirLine() = runBlocking {
        val menu = arrangeMenu()
        val unit = requireNotNull(products.findById(menu.arepa)).details.baseUnitId
        fun heldLine(productId: Long, parentIndex: Int? = null) =
            HeldSaleLine(productId, unit, QuantityMilli(1_000), null, Money.ZERO, parentIndex)
        val id = commit {
            held.insert(
                NewHeldSale(
                    label = null,
                    lines = listOf(
                        heldLine(menu.spare),
                        heldLine(menu.cheese, parentIndex = 0),
                        heldLine(menu.tea),
                        heldLine(menu.cheese, parentIndex = 2),
                    ),
                    at = AT,
                ),
            )
        }

        val stored = requireNotNull(held.findById(id)).lines
        assertEquals(listOf(null, 0, null, 2), stored.map { it.parentIndex })

        // Eliminar el producto de una línea la saca de la espera en cascada, con su extra.
        commit { products.delete(menu.spare) }

        val left = requireNotNull(held.findById(id)).lines
        assertEquals(listOf(menu.tea, menu.cheese), left.map { it.productId })
        assertEquals(listOf(null, 0), left.map { it.parentIndex })
    }

    private class Menu(
        val arepa: Long,
        val cheese: Long,
        val sauce: Long,
        val tea: Long,
        val spare: Long,
    )

    /** Arepa, queso y té con 10 unidades cada uno, salsa sin stock y otro producto sin historia. */
    private suspend fun arrangeMenu(): Menu = commit {
        val arepa = products.insert(newProduct("Arepa inventada"))
        ledger.append(openingRow(arepa, units = 10))
        val cheese = products.insert(newProduct("Queso inventado"))
        ledger.append(openingRow(cheese, units = 10))
        val tea = products.insert(newProduct("Té inventado"))
        ledger.append(openingRow(tea, units = 10))
        val sauce = products.insert(
            newProduct(
                "Salsa inventada",
                productDetails("Salsa inventada", stockMode = ProductStockMode.UNTRACKED),
            ).copy(initialCost = Money.ZERO),
        )
        val spare = products.insert(
            newProduct(
                "Plato inventado",
                productDetails("Plato inventado", stockMode = ProductStockMode.UNTRACKED),
            ).copy(initialCost = Money.ZERO),
        )
        Menu(arepa, cheese, sauce, tea, spare)
    }

    private suspend fun cash(): Long =
        count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")

    private suspend fun defaultPriceList(): Long =
        count("SELECT id FROM price_lists WHERE system_key = 'DEFAULT'")

    private fun line(productId: Long, milli: Long, parentIndex: Int? = null) =
        SaleLineRequest(productId, QuantityMilli(milli), parentIndex = parentIndex)

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
