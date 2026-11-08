package io.github.yesidrobayo.shopledger.domain.rule.payment

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodInputError
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo que la persona escribe de un método de pago: los mismos rangos que las guardas de la base.
 * Las comisiones son inventadas: la app no trae ninguna tarifa.
 */
class PaymentMethodRulesTest {
    private val draft = PaymentMethodDraft(
        name = "  Billetera del barrio  ",
        kind = PaymentMethodKind.WALLET,
        feeRate = BasisPoints(299),
        feeFixed = Money(15),
        settlementDays = 2,
        priceAdjustment = BasisPoints(-500),
        moneyAccountId = 3L,
        instructionsText = "  Enviar al número que aparece en el mostrador  ",
    )

    @Test
    fun `a valid draft is trimmed and normalized, without an instructions image`() {
        assertEquals(
            PaymentMethodCheck.Valid(
                PaymentMethodDetails(
                    name = "Billetera del barrio",
                    normalizedName = "billetera del barrio",
                    kind = PaymentMethodKind.WALLET,
                    feeRate = BasisPoints(299),
                    feeFixed = Money(15),
                    settlementDays = 2,
                    priceAdjustment = BasisPoints(-500),
                    moneyAccountId = 3L,
                    instructionsText = "Enviar al número que aparece en el mostrador",
                    instructionsMediaId = null,
                ),
            ),
            PaymentMethodRules.check(draft, isInitial = false),
        )
    }

    @Test
    fun `an initial method keeps its translated name, and one of the person needs a name`() {
        val keeps = PaymentMethodRules.check(draft.copy(name = null), isInitial = true)
        assertEquals(null, (keeps as PaymentMethodCheck.Valid).details.name)
        assertEquals(null, keeps.details.normalizedName)

        assertEquals(
            invalid(PaymentMethodInputError.NAME_BLANK),
            PaymentMethodRules.check(draft.copy(name = null), isInitial = false),
        )
        // Un método inicial también se renombra.
        val renamed = PaymentMethodRules.check(draft.copy(name = "Caja"), isInitial = true)
        assertEquals("caja", (renamed as PaymentMethodCheck.Valid).details.normalizedName)
    }

    @Test
    fun `a blank name is refused, also one made only of combining marks`() {
        for (name in listOf("", "   ", " \t", "́́")) {
            assertEquals(
                name,
                invalid(PaymentMethodInputError.NAME_BLANK),
                PaymentMethodRules.check(draft.copy(name = name), isInitial = true),
            )
        }
    }

    @Test
    fun `a name has up to 40 characters, counted like the database counts them`() {
        val forty = "a".repeat(40)
        assertValid(draft.copy(name = forty))
        assertEquals(
            invalid(PaymentMethodInputError.NAME_TOO_LONG),
            PaymentMethodRules.check(draft.copy(name = forty + "b"), isInitial = false),
        )
        // Cuarenta emojis son cuarenta caracteres, aunque ocupen ochenta unidades de String.
        val emojis = "💳".repeat(40)
        assertValid(draft.copy(name = emojis))
        assertEquals(
            invalid(PaymentMethodInputError.NAME_TOO_LONG),
            PaymentMethodRules.check(draft.copy(name = emojis + "💳"), false),
        )
        // Una mitad suelta de un par cuenta como uno, al final o seguida de otra letra.
        assertValid(draft.copy(name = "a".repeat(39) + "\uD83D"))
        assertValid(draft.copy(name = "a".repeat(38) + "\uD83Db"))
    }

    @Test
    fun `the fee goes from 0 to 100 percent, and the fixed fee up to the unit money limit`() {
        assertValid(draft.copy(feeRate = BasisPoints.ZERO, feeFixed = Money.ZERO))
        assertValid(
            draft.copy(feeRate = BasisPoints(10_000), feeFixed = Money(Limit.UNIT_MONEY.max)),
        )
        for (rate in listOf(-1L, 10_001L)) {
            assertEquals(
                invalid(PaymentMethodInputError.FEE_RATE_OUT_OF_RANGE),
                PaymentMethodRules.check(draft.copy(feeRate = BasisPoints(rate)), false),
            )
        }
        for (fixed in listOf(-1L, Limit.UNIT_MONEY.max + 1)) {
            assertEquals(
                invalid(PaymentMethodInputError.FEE_FIXED_OUT_OF_RANGE),
                PaymentMethodRules.check(draft.copy(feeFixed = Money(fixed)), false),
            )
        }
    }

    @Test
    fun `settlement takes from 0 to 3650 days`() {
        assertValid(draft.copy(settlementDays = 0))
        assertValid(draft.copy(settlementDays = 3_650))
        for (days in listOf(-1, 3_651)) {
            assertEquals(
                invalid(PaymentMethodInputError.SETTLEMENT_DAYS_OUT_OF_RANGE),
                PaymentMethodRules.check(draft.copy(settlementDays = days), false),
            )
        }
    }

    @Test
    fun `the price adjustment carries a sign, from minus 100 to plus 100 percent`() {
        assertValid(draft.copy(priceAdjustment = BasisPoints(-10_000)))
        assertValid(draft.copy(priceAdjustment = BasisPoints(10_000)))
        for (adjustment in listOf(-10_001L, 10_001L)) {
            assertEquals(
                invalid(PaymentMethodInputError.PRICE_ADJUSTMENT_OUT_OF_RANGE),
                PaymentMethodRules.check(
                    draft.copy(priceAdjustment = BasisPoints(adjustment)),
                    false,
                ),
            )
        }
    }

    @Test
    fun `instructions have up to 500 characters, and blank ones are no instructions`() {
        assertValid(draft.copy(instructionsText = "x".repeat(500)))
        assertEquals(
            invalid(PaymentMethodInputError.INSTRUCTIONS_TOO_LONG),
            PaymentMethodRules.check(draft.copy(instructionsText = "x".repeat(501)), false),
        )
        for (blank in listOf(null, "", "  \n ")) {
            val checked = PaymentMethodRules.check(draft.copy(instructionsText = blank), false)
            assertNull((checked as PaymentMethodCheck.Valid).details.instructionsText)
        }
    }

    @Test
    fun `on account has no fee, settlement or money account, whatever was written before`() {
        // Lo escrito antes de cambiar el tipo, aunque esté fuera de rango, no se guarda ni da error.
        val onAccount = draft.copy(
            kind = PaymentMethodKind.ON_ACCOUNT,
            feeRate = BasisPoints(20_000),
            feeFixed = Money(-5),
            settlementDays = 9_999,
        )

        val details = (
            PaymentMethodRules.check(
                onAccount,
                false,
            ) as PaymentMethodCheck.Valid
            ).details

        assertEquals(BasisPoints.ZERO, details.feeRate)
        assertEquals(Money.ZERO, details.feeFixed)
        assertEquals(0, details.settlementDays)
        assertNull(details.moneyAccountId)
        // El recargo sí se conserva: la pantalla lo sigue mostrando.
        assertEquals(BasisPoints(-500), details.priceAdjustment)
    }

    @Test
    fun `every field error comes at once`() {
        val wrong = draft.copy(
            name = " ",
            feeRate = BasisPoints(-1),
            feeFixed = Money(-1),
            settlementDays = -1,
            priceAdjustment = BasisPoints(10_001),
            instructionsText = "x".repeat(501),
        )

        assertEquals(
            PaymentMethodCheck.Invalid(
                setOf(
                    PaymentMethodInputError.NAME_BLANK,
                    PaymentMethodInputError.FEE_RATE_OUT_OF_RANGE,
                    PaymentMethodInputError.FEE_FIXED_OUT_OF_RANGE,
                    PaymentMethodInputError.SETTLEMENT_DAYS_OUT_OF_RANGE,
                    PaymentMethodInputError.PRICE_ADJUSTMENT_OUT_OF_RANGE,
                    PaymentMethodInputError.INSTRUCTIONS_TOO_LONG,
                ),
            ),
            PaymentMethodRules.check(wrong, isInitial = false),
        )
    }

    @Test
    fun `the compared name ignores case, accents and outer spaces`() {
        assertEquals("cafe en linea", PaymentMethodRules.normalizedNameOf("  Café en LÍNEA "))
        assertNull(PaymentMethodRules.normalizedNameOf(null))
        assertNull(PaymentMethodRules.normalizedNameOf("   "))
    }

    private fun assertValid(draft: PaymentMethodDraft) {
        val checked = PaymentMethodRules.check(draft, isInitial = false)
        assertEquals(PaymentMethodCheck.Valid::class, checked::class)
    }

    private fun invalid(error: PaymentMethodInputError) = PaymentMethodCheck.Invalid(setOf(error))
}
