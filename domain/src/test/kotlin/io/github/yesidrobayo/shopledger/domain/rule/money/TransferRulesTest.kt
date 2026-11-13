package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.TransferInputError
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que entra en la cuenta de destino de una transferencia, con los ejemplos de las reglas de
 * transferencias y de cobro en otra moneda, escritos a mano.
 */
class TransferRulesTest {
    private val two = AccountCurrency(requireNotNull(CurrencyCode.of("XTS")), 2)
    private val zero = AccountCurrency(requireNotNull(CurrencyCode.of("XBA")), 0)
    private val three = AccountCurrency(requireNotNull(CurrencyCode.of("XBB")), 3)
    private val max = Limit.DOCUMENT_TOTAL.max

    private fun check(
        from: AccountCurrency = two,
        to: AccountCurrency = two,
        same: Boolean = false,
        amount: Long = 30_000,
        rate: Long? = null,
        note: String? = null,
    ) = TransferRules.check(from, to, same, Money(amount), rate, note)

    @Test
    fun `the same currency moves the same amount and ignores a rate`() {
        assertEquals(TransferCheck.Valid(Money(30_000), Money(30_000), null, null), check())
        assertEquals(
            TransferCheck.Valid(Money(30_000), Money(30_000), null, "Caja"),
            check(rate = 999, note = " Caja "),
        )
    }

    @Test
    fun `10000 from two decimals at 4150 enter as 415000 in zero decimals`() {
        assertEquals(
            TransferCheck.Valid(Money(10_000), Money(415_000), 4_150_000_000, null),
            check(from = two, to = zero, amount = 10_000, rate = 4_150_000_000),
        )
    }

    @Test
    fun `the conversion follows the examples of the payment in another currency`() {
        // De dos decimales a cero, de dos a tres y de cero a dos, redondeado.
        assertEquals(
            Checked.Value(Money(83_010)),
            TransferRules.convert(Money(2_000), 2, 0, 4_150_500_000),
        )
        assertEquals(
            Checked.Value(Money(3_813)),
            TransferRules.convert(Money(1_250), 2, 3, 305_000),
        )
        assertEquals(Checked.Value(Money(3_100)), TransferRules.convert(Money(5_000), 0, 2, 6_200))
        assertEquals(Checked.Overflow, TransferRules.convert(Money(Long.MAX_VALUE), 0, 4, 1))
        assertEquals(
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL),
            TransferRules.convert(Money(max), 0, 0, 2_000_000),
        )
    }

    @Test
    fun `every input error goes to its field at once`() {
        assertEquals(
            TransferCheck.Invalid(
                setOf(
                    TransferInputError.SAME_ACCOUNT,
                    TransferInputError.AMOUNT_NOT_POSITIVE,
                    TransferInputError.NOTE_TOO_LONG,
                    TransferInputError.RATE_REQUIRED,
                ),
            ),
            check(to = zero, same = true, amount = 0, note = "x".repeat(201)),
        )
        assertEquals(
            TransferCheck.Invalid(
                setOf(TransferInputError.AMOUNT_TOO_LARGE, TransferInputError.RATE_OUT_OF_RANGE),
            ),
            check(to = zero, amount = max + 1, rate = 0),
        )
        assertEquals(
            TransferCheck.Invalid(setOf(TransferInputError.RATE_OUT_OF_RANGE)),
            check(to = zero, rate = max + 1),
        )
    }

    @Test
    fun `a conversion above the limit or rounded to zero is rejected`() {
        assertEquals(
            TransferCheck.Invalid(setOf(TransferInputError.RECEIVED_TOO_LARGE)),
            check(from = zero, to = three, amount = max, rate = 1_000_000),
        )
        // Pasar a cuatro decimales ya no cabe en `Long` antes de dividir.
        assertEquals(
            TransferCheck.Invalid(setOf(TransferInputError.RECEIVED_TOO_LARGE)),
            check(from = zero, to = AccountCurrency(three.code, 4), amount = max, rate = 1),
        )
        assertEquals(
            TransferCheck.Invalid(setOf(TransferInputError.RECEIVED_ZERO)),
            check(from = two, to = zero, amount = 1, rate = 1),
        )
        assertEquals(
            TransferCheck.Valid(Money(1), Money(1), null, null),
            check(amount = 1, note = "  "),
        )
    }
}
