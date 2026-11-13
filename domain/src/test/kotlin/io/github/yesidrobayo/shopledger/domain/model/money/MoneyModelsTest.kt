package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que los modelos del dinero rechazan al armarse, igual que la guarda de la base. */
class MoneyModelsTest {
    private val code = requireNotNull(CurrencyCode.of("XTS"))
    private val at = Instant.fromEpochMilliseconds(0)

    @Test
    fun `an account currency has 0 to 4 decimals and copies the business one`() {
        assertEquals(4, AccountCurrency(code, 4).decimals)
        assertThrows(IllegalArgumentException::class.java) { AccountCurrency(code, -1) }
        assertThrows(IllegalArgumentException::class.java) { AccountCurrency(code, 5) }
        assertEquals(
            AccountCurrency(code, 2),
            AccountCurrency.of(BusinessCurrency(code, 2, symbol = null, lockedAt = null)),
        )
    }

    @Test
    fun `an account needs a name or its initial key, and knows if it is archived`() {
        val details = MoneyAccountDetails(null, null, MoneyAccountKind.CASH)
        val cash =
            MoneyAccount(1, "CASH", details, AccountCurrency(code, 2), Money.ZERO, null, at, at)

        assertFalse(cash.isArchived)
        assertTrue(cash.copy(archivedAt = at).isArchived)
        assertThrows(IllegalArgumentException::class.java) { cash.copy(systemKey = null) }
        assertEquals(
            "Banco",
            cash.copy(systemKey = null, details = details.copy(name = "Banco")).details.name,
        )
    }

    @Test
    fun `each direction allows only its sign, and zero never`() {
        assertTrue(MoneyMovementType.OTHER_INCOME.allows(1))
        assertFalse(MoneyMovementType.OTHER_INCOME.allows(-1))
        assertTrue(MoneyMovementType.OWNER_DRAW.allows(-1))
        assertFalse(MoneyMovementType.OWNER_DRAW.allows(1))
        assertTrue(MoneyMovementType.OPENING_BALANCE.allows(-1))
        assertTrue(MoneyMovementType.OPENING_BALANCE.allows(1))
        for (type in MoneyMovementType.entries) assertFalse(type.allows(0))
    }

    @Test
    fun `the types follow the signs and origins of the ledger rule`() {
        val inbound = MoneyMovementType.entries.filter { it.direction == MoneyDirection.IN }
        val outbound = MoneyMovementType.entries.filter { it.direction == MoneyDirection.OUT }
        assertEquals(
            listOf(
                "SALE_RECEIPT",
                "CUSTOMER_PAYMENT",
                "SUPPLIER_REFUND",
                "OWNER_CONTRIBUTION",
                "OTHER_INCOME",
                "TRANSFER_IN",
            ),
            inbound.map { it.name },
        )
        assertEquals(
            listOf(
                "REFUND",
                "FEE",
                "EXPENSE_PAYMENT",
                "PURCHASE_PAYMENT",
                "SUPPLIER_PAYMENT",
                "OWNER_DRAW",
                "TRANSFER_OUT",
            ),
            outbound.map { it.name },
        )
        assertEquals(
            listOf(
                "OPENING_BALANCE",
                "OWNER_DRAW",
                "OWNER_CONTRIBUTION",
                "OTHER_INCOME",
                "TRANSFER_OUT",
                "TRANSFER_IN",
            ),
            MoneyMovementType.entries.filter { it.isManual }.map { it.name },
        )
    }

    @Test
    fun `every origin reports its kind`() {
        val origins = listOf(
            MoneyOrigin.None,
            MoneyOrigin.SalePayment(1),
            MoneyOrigin.CustomerPayment(1),
            MoneyOrigin.SupplierPayment(1),
            MoneyOrigin.Expense(1),
            MoneyOrigin.Purchase(1),
            MoneyOrigin.SaleReturn(1),
            MoneyOrigin.PurchaseReturn(1),
            MoneyOrigin.Transfer("g", 1),
            MoneyOrigin.Void(1),
        )

        assertEquals(MoneyOriginKind.entries, origins.map { it.kind })
    }

    @Test
    fun `a draft rejects a wrong sign, origin, session or note`() {
        fun draft(
            type: MoneyMovementType = MoneyMovementType.OTHER_INCOME,
            amount: Long = 10,
            origin: MoneyOrigin = MoneyOrigin.None,
            session: Long? = null,
            note: String? = null,
        ) = MoneyMovementDraft(1, type, Money(amount), origin, session, null, note)

        assertEquals("Reintegro", draft(note = "Reintegro").note)
        assertEquals(5L, draft(MoneyMovementType.CASH_DIFFERENCE, -3, session = 5).cashSessionId)
        assertThrows(IllegalArgumentException::class.java) { draft(amount = -10) }
        assertThrows(IllegalArgumentException::class.java) { draft(amount = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            draft(origin = MoneyOrigin.Expense(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft(MoneyMovementType.CASH_DIFFERENCE)
        }
        assertThrows(IllegalArgumentException::class.java) { draft(note = "") }
        assertThrows(IllegalArgumentException::class.java) { draft(note = "x".repeat(201)) }
        assertEquals(200, draft(note = "x".repeat(200)).note?.length)
    }
}
