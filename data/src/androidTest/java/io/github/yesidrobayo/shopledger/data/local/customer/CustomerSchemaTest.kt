package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.sale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los índices y las llaves de clientes, sus libros y sus plantillas, tal como quedan en la base,
 * comparados con el modelo de datos. Lo que más importa: `reverses_entry_id` es único en los dos
 * libros, porque una fila se anula una sola vez, y el nombre del cliente no, porque dos personas
 * pueden llamarse igual. Cliente, venta y fila anulada usan `RESTRICT`: nada con historia se borra.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomerSchemaTest : LedgerDatabaseTest() {
    private val customers get() = database.customerDao()

    @Test
    fun indexesAreTheOnesOfTheDataModel() = runBlocking {
        val expected =
            mapOf(
                "customers" to
                    listOf(
                        "index_customers_normalized_name (normalized_name)",
                        "index_customers_price_list_id (price_list_id)",
                    ),
                "customer_ledger_entries" to
                    listOf(
                        "index_customer_ledger_entries_customer_id (customer_id)",
                        "index_customer_ledger_entries_due_date (due_date)",
                        "unique index_customer_ledger_entries_reverses_entry_id " +
                            "(reverses_entry_id)",
                        "index_customer_ledger_entries_sale_id (sale_id)",
                    ),
                "loyalty_ledger_entries" to
                    listOf(
                        "index_loyalty_ledger_entries_customer_id (customer_id)",
                        "unique index_loyalty_ledger_entries_reverses_entry_id (reverses_entry_id)",
                        "index_loyalty_ledger_entries_sale_id (sale_id)",
                    ),
                "message_templates" to
                    listOf(
                        "unique index_message_templates_template_key_language_tag " +
                            "(template_key, language_tag)",
                    ),
            )

        for ((table, indexes) in expected) assertEquals(table, indexes, database.indexesOf(table))
    }

    @Test
    fun foreignKeysPointToTheTablesThatExist() = runBlocking {
        val expected =
            mapOf(
                "customers" to listOf("price_list_id -> price_lists SET NULL"),
                "customer_ledger_entries" to
                    listOf(
                        "customer_id -> customers RESTRICT",
                        "reverses_entry_id -> customer_ledger_entries RESTRICT",
                        "sale_id -> sales RESTRICT",
                    ),
                "loyalty_ledger_entries" to
                    listOf(
                        "customer_id -> customers RESTRICT",
                        "reverses_entry_id -> loyalty_ledger_entries RESTRICT",
                        "sale_id -> sales RESTRICT",
                    ),
                "message_templates" to emptyList(),
            )

        for ((table, keys) in expected) assertEquals(table, keys, database.foreignKeysOf(table))
    }

    @Test
    fun aCustomerWithHistoryIsNotDeletedAndOneWithoutIs() = runBlocking {
        val withSale = customers.insert(customer("Ana"))
        val withEntry = customers.insert(customer("Beatriz"))
        val withPoints = customers.insert(customer("Carla"))
        val without = customers.insert(customer("Diana"))
        database.saleDao().insert(sale(priceListId).copy(customerId = withSale))
        database.customerLedgerEntryDao()
            .insert(customerEntry(withEntry, "OPENING_BALANCE", 5_000, 5_000))
        database.loyaltyLedgerEntryDao()
            .insert(loyaltyEntry(withPoints, "ADJUSTMENT", 150, 150))

        for (id in listOf(withSale, withEntry, withPoints)) {
            constraintFailure(FOREIGN_KEY) { customers.delete(id) }
        }
        assertEquals(1, customers.delete(without))
        assertNull(customers.findById(without))
        assertEquals(3L, count("SELECT COUNT(*) FROM customers"))
    }

    @Test
    fun noRowPointsToACustomerOrASaleThatDoesNotExist() = runBlocking {
        val customerId = customers.insert(customer("Ana"))

        constraintFailure(FOREIGN_KEY) {
            database.saleDao().insert(sale(priceListId).copy(customerId = MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            database.customerLedgerEntryDao()
                .insert(customerEntry(MISSING_ID, "OPENING_BALANCE", 5_000, 5_000))
        }
        constraintFailure(FOREIGN_KEY) {
            database.customerLedgerEntryDao()
                .insert(customerEntry(customerId, "CREDIT_SALE", 5_000, 5_000, saleId = MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            database.loyaltyLedgerEntryDao()
                .insert(loyaltyEntry(customerId, "EARN", 12, 12, saleId = MISSING_ID))
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM customer_ledger_entries"))
        assertEquals(0L, count("SELECT COUNT(*) FROM loyalty_ledger_entries"))
    }

    @Test
    fun deletingAPriceListLeavesItsCustomersWithTheUsualOne() = runBlocking {
        val wholesale = insertPriceList("mayorista")
        val customerId = customers.insert(customer("Ana").copy(priceListId = wholesale))

        // La llave pone el nulo y pasa por la guarda de actualización del cliente.
        execute("DELETE FROM price_lists WHERE id = $wholesale")

        assertNull(customers.findById(customerId)?.priceListId)
    }

    @Test
    fun aTemplateIsUniqueByKeyAndLanguage() = runBlocking {
        val templates = database.messageTemplateDao()
        templates.insert(template("es"))
        templates.insert(template("pt-BR"))

        constraintFailure(UNIQUE) { templates.insert(template("es")) }
        assertEquals(2L, count("SELECT COUNT(*) FROM message_templates"))
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
