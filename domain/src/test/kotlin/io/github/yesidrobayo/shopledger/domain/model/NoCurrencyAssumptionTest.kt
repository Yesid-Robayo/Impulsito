package io.github.yesidrobayo.shopledger.domain.model

import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.PriceSuggestion
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprueba por reflexión que los tipos de dinero no guardan una moneda ni usan decimales binarios.
 * La aritmética sirve igual para cualquier moneda porque los tipos no tienen dónde atarse a una.
 */
class NoCurrencyAssumptionTest {
    @Test
    fun `money keeps only an integer amount in minor units`() {
        assertEquals(listOf("minor: long"), instanceFields(Money::class.java))
    }

    @Test
    fun `quantities and percentages keep only an integer`() {
        assertEquals(listOf("milli: long"), instanceFields(QuantityMilli::class.java))
        assertEquals(listOf("bp: long"), instanceFields(BasisPoints::class.java))
    }

    @Test
    fun `no arithmetic type uses floating point in a field a parameter or a result`() {
        val floating =
            setOf("double", "float", "java.lang.Double", "java.lang.Float", "java.math.BigDecimal")
        val offending =
            ARITHMETIC_TYPES.map { Class.forName(it) }.flatMap { type ->
                val fields = type.declaredFields.map { it.type.name }
                val methods =
                    type.declaredMethods.flatMap { method ->
                        method.parameterTypes.map { it.name } + method.returnType.name
                    }
                (fields + methods).filter { it in floating }.map { "${type.name}: $it" }
            }

        assertTrue(offending.toString(), offending.isEmpty())
    }

    private fun instanceFields(type: Class<*>): List<String> = type.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) }
        .map { "${it.name}: ${it.type.name}" }

    private companion object {
        private const val MODEL = "io.github.yesidrobayo.shopledger.domain.model"
        private const val RULE = "io.github.yesidrobayo.shopledger.domain.rule"

        val ARITHMETIC_TYPES =
            listOf(
                Money::class.java.name,
                QuantityMilli::class.java.name,
                BasisPoints::class.java.name,
                Limit::class.java.name,
                Scale::class.java.name,
                Checked.Value::class.java.name,
                Checked.AboveLimit::class.java.name,
                UnitQuantityRule::class.java.name,
                UnitQuantityCheck::class.java.name,
                Margin.Defined::class.java.name,
                ProfitTotals::class.java.name,
                ProfitSummary::class.java.name,
                MarginCalculator::class.java.name,
                PriceSuggestion.Suggested::class.java.name,
                "$MODEL.MoneyKt",
                "$MODEL.CheckedKt",
                "$RULE.IntegerRoundingKt",
                "$RULE.MulDivKt",
                "$RULE.MoneyArithmeticKt",
            )
    }
}
