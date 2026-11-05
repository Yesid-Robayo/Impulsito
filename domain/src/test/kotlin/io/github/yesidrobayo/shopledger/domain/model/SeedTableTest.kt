package io.github.yesidrobayo.shopledger.domain.model

import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedTableTest {
    @Test
    fun `the six tables are the ones born with initial rows`() {
        // Ni tasas de impuesto ni denominaciones de billetes: serían de un país.
        assertEquals(
            listOf(
                "expense_categories" to 9,
                "units" to 12,
                "payment_methods" to 6,
                "price_lists" to 1,
                "locations" to 1,
                "money_accounts" to 1,
            ),
            SeedTable.entries.map { it.tableName to it.systemKeys.size },
        )
    }

    @Test
    fun `the stored keys never change`() {
        // Se guardan en la base y nombran un recurso: cambiar una dejaría filas sin texto.
        assertEquals(
            listOf(
                "RENT",
                "UTILITIES",
                "INTERNET_PHONE",
                "TRANSPORT",
                "PACKAGING_SUPPLIES",
                "ADVERTISING",
                "TAXES_FEES",
                "WAGES",
                "OTHER",
            ),
            SeedTable.EXPENSE_CATEGORIES.systemKeys,
        )
        assertEquals("OTHER", OTHER_EXPENSE_CATEGORY)
        assertEquals(OTHER_EXPENSE_CATEGORY, SeedTable.EXPENSE_CATEGORIES.systemKeys.last())
        assertEquals("UNIT", SeedTable.UNITS.systemKeys.first())
        assertEquals(listOf("DEFAULT"), SeedTable.PRICE_LISTS.systemKeys)
        assertEquals(listOf("MAIN"), SeedTable.LOCATIONS.systemKeys)
        assertEquals(listOf("CASH"), SeedTable.MONEY_ACCOUNTS.systemKeys)
    }

    @Test
    fun `each payment method key is the name of its kind`() {
        assertEquals(
            PaymentMethodKind.entries.map { it.name },
            SeedTable.PAYMENT_METHODS.systemKeys,
        )
    }

    @Test
    fun `keys are short uppercase english words without repeats`() {
        // El recurso de una fila es la clave en minúsculas: solo letras, cifras y guion bajo.
        val shape = Regex("[A-Z][A-Z0-9_]*")
        for (table in SeedTable.entries) {
            assertEquals(table.name, table.systemKeys.distinct(), table.systemKeys)
            for (key in table.systemKeys) {
                assertTrue(key, shape.matches(key) && key.length <= MAX_KEY_LENGTH)
            }
        }
    }

    private companion object {
        /** El largo máximo de `system_key` en la base. */
        const val MAX_KEY_LENGTH = 40
    }
}
