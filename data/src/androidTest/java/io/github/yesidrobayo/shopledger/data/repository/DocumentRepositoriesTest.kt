package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.supplier
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.expense.Expense
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseForeignCurrency
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ventas, compras y gastos: se guardan activos, se leen por período, y lo único que cambia después
 * es su anulación, una sola vez.
 */
@RunWith(AndroidJUnit4::class)
internal class DocumentRepositoriesTest : RepositoryTest() {
    private val sales by lazy { RoomSaleRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val expenses by lazy { RoomExpenseRepository(database) }

    @Test
    fun aSaleWithItsExtraComesBackAndIsVoidedOnce() = runBlocking {
        val header = newSale(priceListId)
        val main = newSaleLine(productId, priceListId)
        val (saleId, lineIds) =
            commit {
                val saleId = sales.insert(header)
                val mainId = sales.insertLine(saleId, main)
                saleId to listOf(mainId, sales.insertLine(saleId, main.copy(parentLineId = mainId)))
            }
        val lines =
            listOf(
                SaleLine(lineIds[0], saleId, main),
                SaleLine(lineIds[1], saleId, main.copy(parentLineId = lineIds[0])),
            )

        assertEquals(Sale(saleId, header, voiding = null), sales.findById(saleId))
        assertEquals(lines, sales.findLines(saleId))
        assertEquals(lines, sales.observeLines(saleId).first())

        sales.observeById(saleId).test {
            assertEquals(null, awaitItem()?.voiding)
            // Con nanosegundos que la base no guarda: vuelve truncado al milisegundo.
            val voidedAt = Instant.fromEpochSeconds(LATER_AT.epochSeconds, 999_999_999)
            commit {
                sales.markVoided(
                    saleId,
                    Voiding(voidedAt, VoidReason.CUSTOMER_RETURN, "Devolvió todo"),
                )
            }
            val voiding =
                Voiding(
                    Instant.fromEpochMilliseconds(LATER_AT.toEpochMilliseconds() + 999),
                    VoidReason.CUSTOMER_RETURN,
                    "Devolvió todo",
                )
            assertEquals(voiding, awaitItem()?.voiding)
            cancelAndIgnoreRemainingEvents()
        }
        val seq = changeSeq()

        // Anular dos veces, o una venta que no existe, se deshace entero.
        for (id in listOf(saleId, MISSING_ID)) {
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit { sales.markVoided(id, Voiding(LATER_AT, VoidReason.UNDO, null)) }
                },
            )
        }
        assertEquals(seq, changeSeq())
        assertEquals(VoidReason.CUSTOMER_RETURN, sales.findById(saleId)?.voiding?.reason)
    }

    @Test
    fun salesAreReadByPeriodNewestFirst() = runBlocking {
        val january = requireNotNull(BusinessDate.parse("2026-01-31"))
        val february = requireNotNull(BusinessDate.parse("2026-02-01"))

        sales.observeIn(ReportPeriod.Month(YearMonth(2026, 1))).test {
            assertEquals(emptyList<Sale>(), awaitItem())
            val (first, second) =
                commit {
                    sales.insert(newSale(priceListId, businessDate = DATE)) to
                        sales.insert(newSale(priceListId, businessDate = january))
                }
            // La de febrero cae fuera del mes.
            commit { sales.insert(newSale(priceListId, businessDate = february)) }
            assertEquals(listOf(second, first), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, sales.observeIn(ReportPeriod.Day(february)).first().size)
        assertNull(sales.findById(MISSING_ID))
    }

    @Test
    fun aPurchaseInAnotherCurrencyComesBackAndIsVoidedOnce() = runBlocking {
        val supplierId = database.supplierDao().insert(supplier("Distribuidora Andina"))
        val dueDate = requireNotNull(BusinessDate.parse("2026-01-31"))
        // Código reservado por ISO 4217 para pruebas: ninguna moneda real.
        val header =
            newPurchase(supplierId).copy(
                amountPaid = Money(5_000),
                dueDate = dueDate,
                foreignCurrency = PurchaseForeignCurrency("XTS", 4_100_000_000),
            )
        val line = newPurchaseLine(productId).copy(referenceUnitCost = Money(5))

        purchases.observeIn(ReportPeriod.Week(DATE)).test {
            assertEquals(emptyList<Purchase>(), awaitItem())
            val (purchaseId, lineId) = commit {
                purchases.insert(header).let {
                    it to
                        purchases.insertLine(it, line)
                }
            }
            assertEquals(listOf(Purchase(purchaseId, header, voiding = null)), awaitItem())
            assertEquals(
                listOf(PurchaseLine(lineId, purchaseId, line)),
                purchases.findLines(purchaseId),
            )
            assertEquals(
                listOf(PurchaseLine(lineId, purchaseId, line)),
                purchases.observeLines(purchaseId).first(),
            )
            assertEquals(
                Purchase(purchaseId, header, voiding = null),
                purchases.observeById(purchaseId).first(),
            )

            commit {
                purchases.markVoided(
                    purchaseId,
                    Voiding(LATER_AT, VoidReason.SUPPLIER_RETURN, null),
                )
            }
            assertEquals(
                listOf(Voiding(LATER_AT, VoidReason.SUPPLIER_RETURN, null)),
                awaitItem().map {
                    it.voiding
                },
            )
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit {
                        purchases.markVoided(purchaseId, Voiding(LATER_AT, VoidReason.UNDO, null))
                    }
                },
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(purchases.findById(MISSING_ID))
    }

    @Test
    fun anExpenseComesBackAndIsVoidedOnce() = runBlocking {
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Servicios"))
        val new =
            NewExpense(
                expenseCategoryId = categoryId,
                amount = Money(45_000),
                description = "Luz de enero",
                moneyAccountId = null,
                recurringExpenseId = null,
                occurredAt = AT,
                businessDate = DATE,
                createdAt = AT,
            )

        expenses.observeIn(ReportPeriod.Day(DATE)).test {
            assertEquals(emptyList<Expense>(), awaitItem())
            val id = commit { expenses.insert(new) }
            assertEquals(listOf(Expense(id, new, voiding = null)), awaitItem())

            commit {
                expenses.markVoided(
                    id,
                    Voiding(LATER_AT, VoidReason.ENTRY_ERROR, "Lo anoté dos veces"),
                )
            }
            assertEquals(
                Voiding(LATER_AT, VoidReason.ENTRY_ERROR, "Lo anoté dos veces"),
                awaitItem().single().voiding,
            )
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit {
                        expenses.markVoided(id, Voiding(LATER_AT, VoidReason.OTHER, "Otra vez"))
                    }
                },
            )
            assertEquals(VoidReason.ENTRY_ERROR, expenses.findById(id)?.voiding?.reason)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(expenses.findById(MISSING_ID))
    }

    @Test
    fun aVoidingThatDoesNotMatchItsStatusIsABrokenRow() = runBlocking {
        val id = commit { sales.insert(newSale(priceListId)) }
        // Solo una base alterada fuera de la app puede tener un estado y una anulación que no
        // dicen lo mismo: la guarda no deja escribirlo, así que se apaga para la prueba.
        execute("DROP TRIGGER guard_sales_void_only")
        execute("UPDATE sales SET void_reason = 'UNDO' WHERE id = $id")

        assertEquals(StorageFailure.INVARIANT_VIOLATION, storageFailureOf { sales.findById(id) })
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
