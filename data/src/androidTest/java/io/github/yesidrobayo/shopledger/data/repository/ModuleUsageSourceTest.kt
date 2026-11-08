package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.unit
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Qué herramientas están en uso según los datos: un negocio recién creado no usa ninguna, aunque
 * nazca con su cuenta de efectivo, su lista de precios, su ubicación y sus unidades iniciales; cada
 * herramienta pasa a estar en uso con su primer registro y vuelve sola si deja de tener datos
 * activos. Los nombres son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ModuleUsageSourceTest : CatalogDatabaseTest() {
    private val usage by lazy { RoomModuleUsageSource(database) }

    @Test
    fun aNewBusinessUsesNoToolDespiteItsInitialRows() = runBlocking {
        assertEquals(emptySet<BusinessTool>(), usage.observe().first())
    }

    @Test
    fun aToolIsInUseWithItsFirstRecordAndLeavesWhenArchived() = runBlocking {
        usage.observe().test {
            assertEquals(emptySet<BusinessTool>(), awaitItem())

            val customerId = database.customerDao().insert(customer("Clienta de prueba"))
            awaitValue(setOf(BusinessTool.CUSTOMERS))

            database.unitDao().insert(unit("Bulto"))
            awaitValue(setOf(BusinessTool.CUSTOMERS, BusinessTool.UNITS))

            // Archivado lo último, la herramienta vuelve a Más herramientas; sus datos siguen.
            database.customerDao().setArchivedAt(customerId, LATER, LATER)
            awaitValue(setOf(BusinessTool.UNITS))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun settingsDecideTheToolsWithoutTheirOwnTable() = runBlocking {
        execute(
            "UPDATE app_settings SET reference_currency_code = 'XTT', " +
                "reference_currency_decimals = 2 WHERE id = 1",
        )
        execute(
            "UPDATE app_settings SET revenue_limit_amount = 1000, revenue_limit_months = 12 " +
                "WHERE id = 1",
        )

        assertEquals(
            setOf(BusinessTool.REFERENCE_CURRENCY, BusinessTool.GOALS_AND_LIMITS),
            usage.observe().first(),
        )
    }

    @Test
    fun aSecondActivePriceListPutsPriceListsInUse() = runBlocking {
        // El negocio nace con su lista general: con ella sola, la herramienta no está en uso.
        assertEquals(1L, count("SELECT COUNT(*) FROM price_lists WHERE archived_at IS NULL"))
        assertEquals(false, BusinessTool.PRICE_LISTS in usage.observe().first())

        insertPriceList("mayoreo")

        assertEquals(setOf(BusinessTool.PRICE_LISTS), usage.observe().first())
    }
}
