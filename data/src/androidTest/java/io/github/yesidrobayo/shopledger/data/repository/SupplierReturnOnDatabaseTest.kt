package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewPurchaseReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseReturnRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSupplierReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSupplierReturnUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSupplierReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSupplierReturnUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La devolución a proveedor sobre la base real y sus guardas: cabecera, líneas, la salida del
 * libro de stock con el costo recalculado y el reembolso o el crédito entran en una sola
 * transacción, que es un solo cambio para el respaldo. Una falla inyectada en cada escritura no
 * deja nada a medias, y las consultas de invariantes quedan en cero filas.
 *
 * Los montos son el ejemplo de la regla: `Q = 12000`, `V = 14400` y `C = 1200`, y 2 unidades de una
 * línea de 1400. Los nombres son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SupplierReturnOnDatabaseTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val products by lazy { RoomProductRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val returns by lazy { RoomPurchaseReturnRepository(database) }
    private val suppliers by lazy { RoomSupplierRepository(database) }
    private val supplierLedger by lazy { RoomSupplierLedgerRepository(database) }
    private val stock by lazy { RoomStockLedgerRepository(database) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val money by lazy { RoomMoneyLedgerRepository(database) }

    /** La escritura número N falla; `null` no falla nunca. */
    private var failAt: Int? = null
    private var writes = 0

    private var bankId = 0L
    private var supplierId = 0L
    private var flourId = 0L
    private var purchaseId = 0L
    private var lineId = 0L
    private var stockRows = 0L

    private fun tick() {
        writes++
        if (writes == failAt) throw InjectedWriteFailure()
    }

    private val countedReturns by lazy {
        object : PurchaseReturnRepository by returns {
            override suspend fun insert(purchaseReturn: NewPurchaseReturn): Long {
                tick()
                return returns.insert(purchaseReturn)
            }

            override suspend fun insertLine(
                purchaseReturnId: Long,
                line: NewPurchaseReturnLine,
            ): Long {
                tick()
                return returns.insertLine(purchaseReturnId, line)
            }

            override suspend fun markVoided(id: Long, voiding: Voiding) {
                tick()
                returns.markVoided(id, voiding)
            }
        }
    }

    private val voidReturn by lazy {
        VoidSupplierReturnUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchaseReturns = countedReturns,
            supplierLedger = countedSupplierLedger,
            ledger = countedStock,
            clock = Clock { LATER_AT },
            money = DocumentMoney(accounts, countedMoney),
        )
    }

    private val countedStock by lazy {
        object : StockLedgerRepository by stock {
            override suspend fun append(movement: NewStockMovement): Long {
                tick()
                return stock.append(movement)
            }
        }
    }

    private val countedMoney by lazy {
        object : MoneyLedgerRepository by money {
            override suspend fun append(movement: NewMoneyMovement): Long {
                tick()
                return money.append(movement)
            }
        }
    }

    private val countedSupplierLedger by lazy {
        object : SupplierLedgerRepository by supplierLedger {
            override suspend fun append(entry: NewSupplierLedgerEntry): Long {
                tick()
                return supplierLedger.append(entry)
            }
        }
    }

    private val registerReturn by lazy {
        RegisterSupplierReturnUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            units = RoomUnitRepository(database),
            purchases = purchases,
            purchaseReturns = countedReturns,
            suppliers = suppliers,
            supplierLedger = countedSupplierLedger,
            systemRows = RoomSystemRowRepository(database),
            ledger = countedStock,
            clock = Clock { LATER_AT },
            money = DocumentMoney(accounts, countedMoney),
        )
    }

    private val registerPurchase by lazy {
        RegisterPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = purchases,
            suppliers = suppliers,
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = stock,
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
            supplierLedger = supplierLedger,
        )
    }

    @Before
    fun createTheExample() = runBlocking {
        bankId = commit {
            accounts.insert(
                NewMoneyAccount(
                    MoneyAccountDetails(
                        "Banco inventado",
                        "banco inventado",
                        MoneyAccountKind.BANK,
                    ),
                    AccountCurrency.of(settings.get().regional.currency),
                    AT,
                ),
            )
        }
        supplierId = commit {
            suppliers.insert(
                SupplierDetails("Molino inventado", "molino inventado", null, null, null, null),
                AT,
            )
        }
        flourId = commit { products.insert(newProduct("Harina inventada")) }
        // 10 unidades por 11600 y 2 a 1400: Q = 12000, V = 14400 y C = 1200.
        buy(10_000, total = 11_600, supplier = null)
        purchaseId = buy(2_000, total = 2_800, supplier = supplierId)
        lineId = purchases.findLines(purchaseId).single().id
        stockRows = count("SELECT COUNT(*) FROM stock_movements")
        val product = requireNotNull(products.findById(flourId))
        assertEquals(Money(14_400), product.stockValue)
        assertEquals(Money(1_200), product.averageCost)
    }

    private suspend fun buy(milli: Long, total: Long, supplier: Long?): Long {
        val line = PurchaseLineRequest(
            flourId,
            QuantityMilli(milli),
            PurchaseLineAmount.LineTotal(Money(total)),
        )
        val result = registerPurchase(PurchaseRequest(listOf(line), supplier))
        return (result as RegisterPurchaseResult.Registered).purchaseId
    }

    private fun request(resolution: PurchaseReturnResolution, account: Long? = null) =
        SupplierReturnRequest(
            lines = listOf(
                SupplierReturnLineRequest.FromPurchaseLine(lineId, QuantityMilli(2_000)),
            ),
            resolution = resolution,
            purchaseId = purchaseId,
            moneyAccountId = account,
        )

    /** Corre [block] con una falla en cada escritura hasta que pasa, y devuelve cuántas hubo. */
    private suspend fun failEachWrite(check: suspend () -> Unit, block: suspend () -> Unit): Int {
        var at = 1
        while (true) {
            failAt = at
            writes = 0
            val failed = runCatching { block() }.exceptionOrNull() ?: break
            assertTrue(
                "Escritura $at: ${failed.javaClass.simpleName}",
                failed is InjectedWriteFailure,
            )
            check()
            at++
        }
        failAt = null
        return at - 1
    }

    private suspend fun assertNothingReturned() {
        assertEquals(0L, count("SELECT COUNT(*) FROM purchase_returns"))
        assertEquals(0L, count("SELECT COUNT(*) FROM purchase_return_lines"))
        assertEquals(0L, count("SELECT COUNT(*) FROM supplier_ledger_entries"))
        assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
        assertEquals(stockRows, count("SELECT COUNT(*) FROM stock_movements"))
        val product = requireNotNull(products.findById(flourId))
        assertEquals(QuantityMilli(12_000), product.onHand)
        assertEquals(Money(14_400), product.stockValue)
    }

    @Test
    fun aCreditReturnRecalculatesTheCostAndLowersWhatIsOwedAsOneChange() = runBlocking {
        val before = changeSeq()

        val result = registerReturn(request(PurchaseReturnResolution.SUPPLIER_CREDIT))

        val returnId = (result as RegisterSupplierReturnResult.Registered).purchaseReturnId
        assertEquals(before + 1, changeSeq())
        val product = requireNotNull(products.findById(flourId))
        assertEquals(QuantityMilli(10_000), product.onHand)
        assertEquals(Money(11_600), product.stockValue)
        assertEquals(Money(1_160), product.averageCost)
        val row = stock.findByProduct(flourId).last()
        assertEquals(StockMovementType.PURCHASE_RETURN, row.movement.type)
        assertEquals(Money(-2_800), row.totalCost)
        assertEquals(Money(1_400), row.unitCost)
        val line = returns.findLines(returnId).single()
        assertEquals(line.id, row.source.purchaseReturnLineId)
        val credit = supplierLedger.observeBySupplier(supplierId).first().single().content
        assertEquals(SupplierLedgerEntryType.RETURN_CREDIT, credit.type)
        assertEquals(Money(-2_800), credit.amount)
        assertEquals(returnId, credit.purchaseReturnId)
        assertEquals(Money(-2_800), requireNotNull(suppliers.findById(supplierId)).balance)
        assertEquals(
            mapOf(
                lineId to
                    LineAmounts(QuantityMilli(2_000), Money(2_800), Money.ZERO, Money(2_800)),
            ),
            returns.takenByPurchase(purchaseId),
        )
        assertEquals(listOf(returnId), returns.observeByPurchase(purchaseId).first().map { it.id })
        assertEquals(DocumentStatus.ACTIVE, returns.observeById(returnId).first()?.status)
        assertEquals(listOf(line), returns.observeLines(returnId).first())
        assertTrue(purchases.hasActiveReturns(purchaseId))
        assertInvariantsHold()
    }

    @Test
    fun aRefundToAnAccountWritesItsSupplierRefund() = runBlocking {
        val result = registerReturn(request(PurchaseReturnResolution.CASH_REFUND, bankId))

        val returnId = (result as RegisterSupplierReturnResult.Registered).purchaseReturnId
        val refund = money.findByAccount(bankId).single()
        assertEquals(MoneyMovementType.SUPPLIER_REFUND, refund.type)
        assertEquals(Money(2_800), refund.amount)
        assertEquals(MoneyOrigin.PurchaseReturn(returnId), refund.draft.origin)
        assertEquals(Money(2_800), requireNotNull(accounts.findById(bankId)).balance)
        assertEquals(Money.ZERO, requireNotNull(suppliers.findById(supplierId)).balance)
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteOfACreditLeavesNothingHalfWay() = runBlocking {
        val writes = failEachWrite({ assertNothingReturned() }) {
            registerReturn(request(PurchaseReturnResolution.SUPPLIER_CREDIT))
        }
        // La cabecera, la línea, la fila de stock y el crédito.
        assertEquals(4, writes)
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteOfARefundLeavesNothingHalfWay() = runBlocking {
        val writes = failEachWrite({
            assertNothingReturned()
            assertEquals(Money.ZERO, requireNotNull(accounts.findById(bankId)).balance)
        }) {
            registerReturn(request(PurchaseReturnResolution.CASH_REFUND, bankId))
        }
        // La cabecera, la línea, la fila de stock y el reembolso.
        assertEquals(4, writes)
        assertInvariantsHold()
    }

    @Test
    fun aPurchaseWithAnActiveReturnIsNotVoided() = runBlocking {
        registerReturn(request(PurchaseReturnResolution.SUPPLIER_CREDIT))
        val void = VoidPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = purchases,
            ledger = stock,
            clock = Clock { LATER_AT },
            supplierLedger = supplierLedger,
        )

        assertEquals(
            VoidPurchaseResult.HasActiveReturns,
            void(purchaseId, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(DocumentStatus.ACTIVE, requireNotNull(purchases.findById(purchaseId)).status)
        assertInvariantsHold()
    }

    @Test
    fun voidingACreditReturnBringsEverythingBackAndAFailureAtEachWriteLeavesItActive() =
        runBlocking {
            val returnId = (
                registerReturn(request(PurchaseReturnResolution.SUPPLIER_CREDIT))
                    as RegisterSupplierReturnResult.Registered
                ).purchaseReturnId
            val writes = failEachWrite({
                assertEquals(
                    DocumentStatus.ACTIVE,
                    requireNotNull(returns.findById(returnId)).status,
                )
                assertEquals(Money(11_600), requireNotNull(products.findById(flourId)).stockValue)
                assertEquals(Money(-2_800), requireNotNull(suppliers.findById(supplierId)).balance)
            }) {
                assertEquals(
                    VoidSupplierReturnResult.Voided,
                    voidReturn(returnId, VoidReason.ENTRY_ERROR, null),
                )
            }
            // La marca, la fila de stock que vuelve y la compensación del crédito.
            assertEquals(3, writes)
            val product = requireNotNull(products.findById(flourId))
            assertEquals(QuantityMilli(12_000), product.onHand)
            assertEquals(Money(14_400), product.stockValue)
            assertEquals(Money(1_200), product.averageCost)
            assertEquals(
                StockMovementType.PURCHASE_RETURN_VOID,
                stock.findByPurchaseReturn(returnId).last().movement.type,
            )
            assertEquals(Money.ZERO, requireNotNull(suppliers.findById(supplierId)).balance)
            assertTrue(returns.takenByPurchase(purchaseId).isEmpty())
            assertTrue(!purchases.hasActiveReturns(purchaseId))
            assertInvariantsHold()
        }

    @Test
    fun voidingARefundTakesTheMoneyBackOut() = runBlocking {
        val returnId = (
            registerReturn(request(PurchaseReturnResolution.CASH_REFUND, bankId))
                as RegisterSupplierReturnResult.Registered
            ).purchaseReturnId

        assertEquals(VoidSupplierReturnResult.Voided, voidReturn(returnId, VoidReason.UNDO, null))

        val reversal = money.findByAccount(bankId).last()
        assertEquals(MoneyMovementType.MOVEMENT_VOID, reversal.type)
        assertEquals(Money.ZERO, requireNotNull(accounts.findById(bankId)).balance)
        assertEquals(1, money.findByPurchaseReturn(returnId).size)
        assertTrue(supplierLedger.findByPurchaseReturn(returnId).isEmpty())
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }

    private class InjectedWriteFailure : RuntimeException("Falla a propósito")
}
