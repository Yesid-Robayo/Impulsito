package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.customer.customerEntryRow
import io.github.yesidrobayo.shopledger.data.local.customer.customerRow
import io.github.yesidrobayo.shopledger.data.local.money.moneyMovementRow
import io.github.yesidrobayo.shopledger.data.local.payable.supplierEntryRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La moneda del negocio, sonda por sonda: el código es de tres letras mayúsculas y los decimales
 * van de 0 a 4; mientras la moneda no esté confirmada y bloqueada, ningún registro con dinero
 * entra; y una vez bloqueada, ni el código ni los decimales cambian.
 *
 * Cada base nace sin moneda, como la de un negocio nuevo: la prueba la confirma cuando lo
 * necesita. Todos los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CurrencyGuardsTest : CatalogDatabaseTest() {
    override val confirmsCurrency: Boolean = false

    private val settings get() = database.appSettingsDao()

    @Test
    fun theCurrencyIsThreeCapitalLettersWithZeroToFourDecimals() = runBlocking {
        for ((code, decimals) in listOf(
            "xts" to 2L,
            "Xts" to 2L,
            "XT" to 2L,
            "XTSS" to 2L,
            "X1S" to 2L,
            "XÉS" to 2L,
            "" to 2L,
            "XTS" to 5L,
            "XTS" to -1L,
        )) {
            guardFailure(SETTINGS) { settings.updateCurrency(code, decimals, LATER) }
        }
        for (broken in listOf(
            "currency_code" to null,
            "currency_decimals" to null,
            "currency_decimals" to 2.5,
            "currency_symbol" to "",
            "currency_symbol" to "S".repeat(9),
            "currency_locked_at" to "hoy",
        )) {
            guardFailure(SETTINGS) { execute(update("app_settings", 1, broken)) }
        }

        // Antes del bloqueo, la moneda y sus decimales se cambian las veces que haga falta, de 0 a
        // 4 decimales, y el símbolo admite hasta 8 caracteres.
        for (decimals in 0L..4L) {
            assertEquals(1, settings.updateCurrency(TEST_BUSINESS_CURRENCY, decimals, LATER))
        }
        assertEquals(1, settings.updateCurrency("XTS", 2, LATER))
        execute(update("app_settings", 1, "currency_symbol" to "S".repeat(8)))
        assertEquals(2L, settings.get()?.currencyDecimals)
        assertNull(settings.get()?.currencyLockedAt)
    }

    @Test
    fun withoutAConfirmedCurrencyNoMoneyIsRecordedAnywhere() = runBlocking {
        val records = moneyRecords()
        for ((guard, sql) in records) guardFailure(guard) { execute(sql) }
        // Un producto sin precio ni costo no lleva dinero: entra, pero ponerle precio no.
        val productId = insert("products", zeroPricedProduct())
        guardFailure(PRODUCTS) {
            execute(update("products", productId, "sale_price" to 2_500L))
        }

        // Sin moneda elegida tampoco se bloquea: `XXX` no es una moneda.
        guardFailure(SETTINGS) { settings.lockCurrency(LATER) }
        assertNull(settings.get()?.currencyLockedAt)

        database.confirmTestCurrency(LATER)

        // Con la moneda bloqueada, las mismas filas entran.
        for ((_, sql) in records) execute(sql)
        execute(update("products", productId, "sale_price" to 2_500L))
        assertEquals(2_500L, count("SELECT sale_price FROM products WHERE id = $productId"))
    }

    @Test
    fun aLockedCurrencyNeverChangesAndStaysLocked() = runBlocking {
        database.confirmTestCurrency(LATER)

        guardFailure(SETTINGS) {
            settings.updateCurrency("XTT", TEST_BUSINESS_DECIMALS, MUCH_LATER)
        }
        guardFailure(SETTINGS) { settings.updateCurrency(TEST_BUSINESS_CURRENCY, 3, MUCH_LATER) }
        for (broken in listOf(
            "currency_locked_at" to null,
            "currency_locked_at" to MUCH_LATER,
            "currency_code" to "XXX",
        )) {
            guardFailure(SETTINGS) { execute(update("app_settings", 1, broken)) }
        }
        // Bloquear otra vez no cambia nada: el primer bloqueo se conserva.
        assertEquals(0, settings.lockCurrency(MUCH_LATER))

        // El símbolo y el redondeo de efectivo no cambian el valor de lo guardado: siguen
        // editables.
        execute(update("app_settings", 1, "currency_symbol" to "S", "cash_rounding" to 50L))
        val locked = requireNotNull(settings.get())
        assertEquals(TEST_BUSINESS_CURRENCY, locked.currencyCode)
        assertEquals(TEST_BUSINESS_DECIMALS, locked.currencyDecimals)
        assertEquals(LATER, locked.currencyLockedAt)
        assertEquals(50L, locked.cashRounding)
    }

    /** Cada registro con dinero, con la guarda que lo detiene sin moneda, y sus filas padre. */
    private suspend fun moneyRecords(): List<Pair<String, String>> {
        val priceListId = count("SELECT id FROM price_lists WHERE system_key = 'DEFAULT'")
        val categoryId = insert("expense_categories", expenseCategoryRow())
        val cashId = count("SELECT id FROM money_accounts WHERE system_key = 'CASH'")
        val customerId = insert("customers", customerRow())
        val supplierId = insert("suppliers", supplierRow())
        return listOf(
            PRODUCTS to productRow().insertInto("products"),
            "guard:sales" to saleRow(priceListId).insertInto("sales"),
            "guard:purchases" to purchaseRow().insertInto("purchases"),
            "guard:expenses" to expenseRow(categoryId).insertInto("expenses"),
            "guard:money_movements" to moneyMovementRow(cashId).insertInto("money_movements"),
            "guard:customer_ledger_entries" to
                customerEntryRow(customerId).insertInto("customer_ledger_entries"),
            "guard:supplier_ledger_entries" to
                supplierEntryRow(supplierId).insertInto("supplier_ledger_entries"),
        )
    }

    private fun zeroPricedProduct(): Row = productRow("sin precio").with(
        "sale_price" to 0L,
        "average_cost" to 0L,
        "initial_cost" to 0L,
    )

    private suspend fun insert(table: String, row: Row): Long {
        execute(row.insertInto(table))
        return count("SELECT max(id) FROM $table")
    }

    private companion object {
        const val SETTINGS = "guard:app_settings"
        const val PRODUCTS = "guard:products"
    }
}
