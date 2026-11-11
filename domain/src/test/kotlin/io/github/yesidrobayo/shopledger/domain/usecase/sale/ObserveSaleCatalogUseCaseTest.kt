package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.port.SaleFrequencySource
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTaxRateRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El catálogo de la venta: los productos vigentes, los más vendidos de los últimos treinta días según
 * la fecha de negocio, y los métodos con que se cobra, sin el de crédito. Datos inventados.
 */
class ObserveSaleCatalogUseCaseTest {
    private val database = InMemoryDatabase()
    private val products = FakeProductRepository(database)
    private val units = FakeUnitRepository(database)
    private val methods = FakePaymentMethodRepository(database)

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en `Etc/GMT+5`. */
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val askedSince = MutableStateFlow<BusinessDate?>(null)
    private val counts = MutableStateFlow(mapOf(2L to 4))
    private val frequency = SaleFrequencySource { since ->
        askedSince.value = since
        counts
    }

    private fun useCase(zone: String): ObserveSaleCatalogUseCase {
        val settings = FakeAppSettingsRepository(
            appSettings(regionalSettings(zone, DayOfWeek.MONDAY)),
        )
        return ObserveSaleCatalogUseCase(
            products,
            units,
            frequency,
            methods,
            BusinessCalendarSource(settings),
            FakeTaxSettingsRepository(database),
            FakeTaxRateRepository(database),
            FixedClock(now),
        )
    }

    private fun seed() {
        methods.seedInitialMethods(now)
        database.seed { tables ->
            tables.copy(
                products = mapOf(
                    1L to product(1L, name = "Arroz"),
                    2L to product(2L, name = "Cafe"),
                    3L to product(3L, name = "Jabon", archived = true),
                ),
                units = mapOf(1L to measureUnit(1L, "UNIT", UnitKind.COUNT, false)),
            )
        }
    }

    @Test
    fun `offers active products, the best sellers first, and the methods without credit`() =
        runTest {
            seed()

            val catalog = useCase("Etc/GMT+5")().first()

            assertEquals(listOf(1L, 2L), catalog.products.map { it.id })
            assertEquals(listOf(2L, 1L), catalog.frequent.map { it.id })
            assertEquals(listOf(3L), catalog.archived.map { it.id })
            assertEquals(setOf(1L), catalog.units.keys)
            assertEquals(
                listOf("CASH", "CARD", "TRANSFER", "WALLET", "OTHER"),
                catalog.methods.map { it.systemKey },
            )
            assertEquals(BusinessDate(LocalDate(2026, 11, 2)), catalog.today)
            assertEquals(DayOfWeek.MONDAY, catalog.weekStartDay)
            assertEquals(TaxMode.NONE, catalog.taxes.mode)
            assertEquals(emptyMap<Long, BasisPoints>(), catalog.taxRates)
            assertEquals(BusinessDate(LocalDate(2026, 10, 4)), askedSince.value)
        }

    @Test
    fun `without a known business zone there is no today and the order is alphabetical`() =
        runTest {
            seed()

            val catalog = useCase("Mars/Olympus_Mons")().first()

            assertNull(catalog.today)
            assertNull(catalog.weekStartDay)
            assertNull(askedSince.value)
            assertEquals(listOf(1L, 2L), catalog.frequent.map { it.id })
        }
}
