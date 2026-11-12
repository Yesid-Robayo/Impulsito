package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeSupplierRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTaxRateRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.measureUnit
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.taxRate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El catálogo de la compra: solo los productos con stock propio y sin lotes, los archivados que se
 * podrían reactivar, los proveedores vigentes, las tasas también archivadas y la fecha de hoy.
 * Datos inventados.
 */
class ObservePurchaseCatalogUseCaseTest {
    private val database = InMemoryDatabase()

    private fun useCase(zone: String) = ObservePurchaseCatalogUseCase(
        products = FakeProductRepository(database),
        units = FakeUnitRepository(database),
        suppliers = FakeSupplierRepository(database),
        calendar = BusinessCalendarSource(
            FakeAppSettingsRepository(appSettings(regionalSettings(zone, DayOfWeek.MONDAY))),
        ),
        taxSettings = FakeTaxSettingsRepository(database),
        taxRates = FakeTaxRateRepository(database),
        clock = FixedClock(NOW),
    )

    private fun supplier(id: Long, archived: Boolean) = Supplier(
        id = id,
        details = SupplierDetails("Supplier $id", "supplier $id", null, null, null, null),
        balance = Money.ZERO,
        archivedAt = if (archived) SaleWorld.EPOCH else null,
        createdAt = SaleWorld.EPOCH,
        updatedAt = SaleWorld.EPOCH,
    )

    private fun seed() = database.seed { tables ->
        tables.copy(
            products = mapOf(
                1L to product(1L),
                2L to product(2L, stockMode = ProductStockMode.UNTRACKED),
                3L to product(3L, stockMode = ProductStockMode.COMPONENTS),
                4L to product(4L, tracksLots = true),
                5L to product(5L, archived = true),
                6L to product(6L, archived = true, stockMode = ProductStockMode.UNTRACKED),
            ),
            units = mapOf(1L to measureUnit(1L, "UNIT", UnitKind.COUNT, false)),
            suppliers = mapOf(
                7L to supplier(7L, archived = false),
                8L to supplier(8L, archived = true),
            ),
            taxRates = mapOf(
                20L to taxRate(20, "Active tax", 1_900),
                21L to taxRate(21, "Archived tax", 500, archived = true),
            ),
        )
    }

    @Test
    fun `offers only what can be bought and every tax rate`() = runTest {
        seed()

        val catalog = useCase("Etc/GMT+5")().first()

        assertEquals(listOf(1L), catalog.products.map { it.id })
        assertEquals(listOf(5L), catalog.archived.map { it.id })
        assertEquals(listOf(7L), catalog.suppliers.map { it.id })
        assertEquals(setOf(1L), catalog.units.keys)
        assertEquals(BasisPoints(500), catalog.taxRates.getValue(21L).rate)
        assertEquals(setOf(20L, 21L), catalog.taxRates.keys)
        assertEquals(TaxMode.NONE, catalog.taxes.mode)
        assertEquals(TODAY, catalog.today)
        assertEquals(DayOfWeek.MONDAY, catalog.weekStartDay)
    }

    @Test
    fun `without a known business zone there is no today`() = runTest {
        seed()

        val catalog = useCase("Mars/Olympus_Mons")().first()

        assertNull(catalog.today)
        assertNull(catalog.weekStartDay)
        assertEquals(BusinessDate(LocalDate(2026, 11, 2)), TODAY)
    }
}
