package io.github.yesidrobayo.shopledger.data.local.payment

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los pagos de las ventas: cada columna vuelve como se guardó, los de una venta salen en orden de
 * guardado, venta y método deben existir y no se borran con pagos, y un pago nunca se edita ni se
 * borra, ni siquiera al anular su venta.
 */
@RunWith(AndroidJUnit4::class)
internal class SalePaymentDaoTest : LedgerDatabaseTest() {
    private val payments get() = database.salePaymentDao()
    private val methods get() = database.paymentMethodDao()

    @Test
    fun aPaymentReadsBackWithEveryColumn() = runBlocking {
        val saleId = database.saleDao().insert(sale(priceListId))
        val cash = methodId("CASH")
        val box = database.moneyAccountDao().insert(moneyAccount("Caja", kind = "CASH"))
        // Recibido en otra moneda: 50 enteras a 260 por cada una, con cambio en la del negocio.
        val payment =
            cashPayment(saleId, cash).copy(
                amount = 12_800,
                tenderedAmount = 13_000,
                changeAmount = 200,
                moneyAccountId = box,
                tenderCurrencyCode = "XTS",
                tenderCurrencyDecimals = 2,
                tenderAmount = 5_000,
                fxRateMicro = 260_000_000,
                settlementDate = "2026-01-02",
                createdAt = LATER,
            )

        val id = payments.insert(payment)

        assertEquals(listOf(payment.copy(id = id)), payments.findBySale(saleId))
    }

    @Test
    fun amountsAreStoredAsExactIntegersAtTheirLimits() = runBlocking {
        val saleId = database.saleDao().insert(sale(priceListId))
        val atLimits =
            cardPayment(saleId, methodId("CARD")).copy(
                amount = Limit.DOCUMENT_TOTAL.max,
                feeAmount = Limit.DOCUMENT_TOTAL.max,
            )

        val id = payments.insert(atLimits)

        assertEquals(listOf(atLimits.copy(id = id)), payments.findBySale(saleId))
        guardFailure(GUARD) {
            payments.insert(atLimits.copy(amount = Limit.DOCUMENT_TOTAL.max + 1))
        }
    }

    @Test
    fun aSaleListsItsPaymentsInTheOrderTheyWereSaved() = runBlocking {
        val saleId = database.saleDao().insert(sale(priceListId))
        val otherSale = database.saleDao().insert(sale(priceListId))
        val cashId = methodId("CASH")

        val emissions = Emissions(this, payments.observeBySale(saleId).ids { it.id })
        try {
            emissions.awaitValue(emptyList())
            val card = payments.insert(cardPayment(saleId, methodId("CARD")))
            payments.insert(
                cashPayment(otherSale, cashId).copy(
                    amount = 12_800,
                    tenderedAmount = 12_800,
                    changeAmount = 0,
                ),
            )
            val cash = payments.insert(cashPayment(saleId, cashId))
            // Dos pagos que suman lo que la venta debía cobrar, sin los de la otra venta.
            emissions.awaitValue(listOf(card, cash))
            assertEquals(12_800L, payments.findBySale(saleId).sumOf { it.amount })
        } finally {
            emissions.stop()
        }
        assertEquals(emptyList<SalePaymentEntity>(), payments.findBySale(MISSING_ID))
    }

    @Test
    fun saleMethodAndAccountMustExistAndStayWhileThereArePayments() = runBlocking {
        val saleId = database.saleDao().insert(sale(priceListId))
        val card = methodId("CARD")
        val bank = database.moneyAccountDao().insert(moneyAccount("Banco"))
        constraintFailure(FOREIGN_KEY) { payments.insert(cardPayment(MISSING_ID, card)) }
        constraintFailure(FOREIGN_KEY) { payments.insert(cardPayment(saleId, MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            payments.insert(cardPayment(saleId, card).copy(moneyAccountId = MISSING_ID))
        }

        payments.insert(cardPayment(saleId, card).copy(moneyAccountId = bank))

        constraintFailure(FOREIGN_KEY) { methods.delete(card) }
        constraintFailure(FOREIGN_KEY) { execute("DELETE FROM money_accounts WHERE id = $bank") }
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_payments"))
    }

    @Test
    fun aPaymentIsNeverEditedNorDeletedEvenAfterItsSaleIsVoided() = runBlocking {
        val saleId = database.saleDao().insert(sale(priceListId))
        val id = payments.insert(cardPayment(saleId, methodId("CARD")))
        database.saleDao().markVoided(saleId, LATER, "ENTRY_ERROR", null)

        guardFailure(APPEND_ONLY) { execute("UPDATE sale_payments SET amount = 1 WHERE id = $id") }
        guardFailure(APPEND_ONLY) { execute("DELETE FROM sale_payments WHERE id = $id") }

        assertEquals(3_001L, payments.findBySale(saleId).single().amount)
        // A propósito, el DAO no tiene con qué editar ni borrar un pago.
        assertEquals(
            setOf("observeBySale", "findBySale", "insert"),
            functionsOf(SalePaymentDao::class.java),
        )
    }

    @Test
    fun indexesAndKeysAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "index_sale_payments_payment_method_id_sale_id (payment_method_id, sale_id)",
                "index_sale_payments_sale_id (sale_id)",
            ),
            database.indexesOf("sale_payments"),
        )
        assertEquals(
            listOf(
                "money_account_id -> money_accounts RESTRICT",
                "payment_method_id -> payment_methods RESTRICT",
                "sale_id -> sales RESTRICT",
            ),
            database.foreignKeysOf("sale_payments"),
        )
        assertEquals(
            listOf(
                "instructions_media_id -> media_assets SET NULL",
                "money_account_id -> money_accounts RESTRICT",
            ),
            database.foreignKeysOf("payment_methods"),
        )
    }

    private suspend fun methodId(systemKey: String): Long =
        requireNotNull(methods.findBySystemKey(systemKey)) { "Falta el método $systemKey" }.id

    private companion object {
        const val GUARD = "guard:sale_payments"
        const val APPEND_ONLY = "guard:sale_payments_append_only"
        const val MISSING_ID = 999L
    }
}
