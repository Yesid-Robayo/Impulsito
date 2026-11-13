package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountInputError
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El nombre y el saldo inicial de una cuenta, y el dinero sumado sin mezclar monedas. */
class MoneyAccountRulesTest {
    private val max = 1_000_000_000_000_000L

    @Test
    fun `a name is trimmed and normalized, and the opening balance kept`() {
        val check = MoneyAccountRules.check(
            MoneyAccountDraft("  Banco Único ", MoneyAccountKind.BANK, null, Money(-50_000)),
            isInitial = false,
        )

        assertEquals(
            MoneyAccountCheck.Valid(
                MoneyAccountDetails("Banco Único", "banco unico", MoneyAccountKind.BANK),
                Money(-50_000),
            ),
            check,
        )
    }

    @Test
    fun `a zero opening balance writes no movement, and an initial account may stay unnamed`() {
        val check = MoneyAccountRules.check(
            MoneyAccountDraft(" ", MoneyAccountKind.CASH, null, Money.ZERO),
            isInitial = true,
        ) as MoneyAccountCheck.Valid

        assertNull(check.details.name)
        assertNull(check.details.normalizedName)
        assertNull(check.openingBalance)
    }

    @Test
    fun `every error goes to its field at once`() {
        assertEquals(
            MoneyAccountCheck.Invalid(
                setOf(
                    MoneyAccountInputError.NAME_EMPTY,
                    MoneyAccountInputError.OPENING_BALANCE_TOO_LARGE,
                ),
            ),
            MoneyAccountRules.check(
                MoneyAccountDraft("", MoneyAccountKind.BANK, null, Money(max + 1)),
                isInitial = false,
            ),
        )
        assertEquals(
            MoneyAccountCheck.Invalid(setOf(MoneyAccountInputError.NAME_TOO_LONG)),
            MoneyAccountRules.check(
                MoneyAccountDraft("x".repeat(41), MoneyAccountKind.BANK),
                false,
            ),
        )
    }

    @Test
    fun `the document limit holds both ways, also for the most negative long`() {
        assertTrue(MoneyAccountRules.withinDocumentTotal(Money(max)))
        assertTrue(MoneyAccountRules.withinDocumentTotal(Money(-max)))
        assertFalse(MoneyAccountRules.withinDocumentTotal(Money(max + 1)))
        assertFalse(MoneyAccountRules.withinDocumentTotal(Money(-max - 1)))
        assertFalse(MoneyAccountRules.withinDocumentTotal(Money(Long.MIN_VALUE)))
    }

    @Test
    fun `only accounts with the same code and decimals add to the business money`() {
        val book = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 2)
        val sameCodeOtherDecimals = AccountCurrency(book.code, 0)
        val other = AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 2)
        val accounts = listOf(
            account(1, book, 50_000),
            account(2, sameCodeOtherDecimals, 7),
            account(3, other, 9_999),
            account(4, book, -1_000),
        )

        val overview = MoneyTotals.overview(accounts, book)

        assertEquals(Money(49_000), overview.available)
        assertEquals(listOf(1L, 4L), overview.inBookCurrency.map { it.id })
        assertEquals(listOf(2L, 3L), overview.inOtherCurrencies.map { it.id })
        assertNull(
            MoneyTotals.overview(
                listOf(account(1, book, Long.MAX_VALUE), account(2, book, 1)),
                book,
            )
                .available,
        )
    }

    private fun account(id: Long, currency: AccountCurrency, balance: Long) = MoneyAccount(
        id = id,
        systemKey = null,
        details = MoneyAccountDetails("Cuenta $id", "cuenta $id", MoneyAccountKind.BANK),
        currency = currency,
        balance = Money(balance),
        archivedAt = null,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
