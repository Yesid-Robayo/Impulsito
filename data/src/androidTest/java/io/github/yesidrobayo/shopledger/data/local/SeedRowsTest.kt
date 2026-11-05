package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las filas iniciales de los catálogos, tal como nace la base: las seis tablas de [SeedTable], con
 * sus claves en orden, sin nombre, sin marcas y sin tasas, y una base que con ellas sigue vacía para
 * el respaldo.
 */
@RunWith(AndroidJUnit4::class)
internal class SeedRowsTest : CatalogDatabaseTest() {
    override val confirmsCurrency: Boolean = false

    @Test
    fun theSixTablesAreBornWithTheirKeysInOrder() = runBlocking {
        for (table in SeedTable.entries) {
            assertEquals(
                table.tableName,
                table.systemKeys,
                texts("SELECT system_key FROM ${table.tableName} ORDER BY id"),
            )
        }
    }

    @Test
    fun noInitialRowCarriesANameABrandOrARate() = runBlocking {
        // El texto visible sale del recurso de la clave: en la base no queda ninguna palabra.
        for (table in SeedTable.entries) {
            assertEquals(
                table.tableName,
                0L,
                count(
                    "SELECT COUNT(*) FROM ${table.tableName} WHERE system_key IS NULL " +
                        "OR name IS NOT NULL OR normalized_name IS NOT NULL " +
                        "OR archived_at IS NOT NULL " +
                        "OR created_at <> $CREATED_AT OR updated_at <> $CREATED_AT",
                ),
            )
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM units WHERE symbol IS NOT NULL"))
        // Ni comisiones, ni instrucciones, ni una cuenta: los métodos son genéricos.
        assertEquals(
            0L,
            count(
                "SELECT COUNT(*) FROM payment_methods WHERE fee_bp <> 0 OR fee_fixed <> 0 " +
                    "OR settlement_days <> 0 OR price_adjustment_bp <> 0 " +
                    "OR money_account_id IS NOT NULL OR instructions_text IS NOT NULL " +
                    "OR instructions_media_id IS NOT NULL OR kind <> system_key",
            ),
        )
        // Las tasas de impuesto y los billetes son de cada país: no hay ninguno al nacer.
        assertEquals(0L, count("SELECT COUNT(*) FROM tax_rates"))
        assertEquals(0L, count("SELECT COUNT(*) FROM cash_denominations"))
    }

    @Test
    fun onlyTheOtherExpensesCategoryIsSystemAndNoneIsFixed() = runBlocking {
        assertEquals(
            listOf("OTHER"),
            texts("SELECT system_key FROM expense_categories WHERE is_system = 1 ORDER BY id"),
        )
        assertEquals(0L, count("SELECT COUNT(*) FROM expense_categories WHERE is_fixed <> 0"))
    }

    @Test
    fun aNewDatabaseWithItsInitialRowsIsEmptyForTheBackup() = runBlocking {
        // Las consultas del modelo de datos: sin un solo dato del negocio, nunca se sube una copia.
        assertEquals(1L, count(IS_EMPTY_SQL))
        assertEquals(0L, count(RECORD_COUNT_SQL))
        assertEquals(0L, count(CATALOG_COUNT_SQL))
        assertTrue(count("SELECT COUNT(*) FROM expense_categories") > 0)
    }

    private suspend fun texts(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private companion object {
        /** Si la base no tiene datos del negocio: los datos iniciales y los ajustes no cuentan. */
        const val IS_EMPTY_SQL =
            "SELECT NOT EXISTS (SELECT 1 FROM products) " +
                "AND NOT EXISTS (SELECT 1 FROM suppliers) " +
                "AND NOT EXISTS (SELECT 1 FROM customers) " +
                "AND NOT EXISTS (SELECT 1 FROM sales) " +
                "AND NOT EXISTS (SELECT 1 FROM purchases) " +
                "AND NOT EXISTS (SELECT 1 FROM expenses) " +
                "AND NOT EXISTS (SELECT 1 FROM quotes) " +
                "AND NOT EXISTS (SELECT 1 FROM orders) " +
                "AND NOT EXISTS (SELECT 1 FROM money_movements)"

        /** Los registros que solo crecen. */
        const val RECORD_COUNT_SQL =
            "SELECT (SELECT COUNT(*) FROM sales) + (SELECT COUNT(*) FROM purchases) " +
                "+ (SELECT COUNT(*) FROM expenses) + (SELECT COUNT(*) FROM stock_movements) " +
                "+ (SELECT COUNT(*) FROM sale_returns) + (SELECT COUNT(*) FROM purchase_returns) " +
                "+ (SELECT COUNT(*) FROM customer_ledger_entries) " +
                "+ (SELECT COUNT(*) FROM supplier_ledger_entries) " +
                "+ (SELECT COUNT(*) FROM money_movements) " +
                "+ (SELECT COUNT(*) FROM loyalty_ledger_entries)"

        /** El tamaño del catálogo que la copia declara. */
        const val CATALOG_COUNT_SQL =
            "SELECT (SELECT COUNT(*) FROM products) + (SELECT COUNT(*) FROM suppliers) " +
                "+ (SELECT COUNT(*) FROM customers)"
    }
}
