package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.SaleFrequencySource
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductComponentRepository
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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La venta no ofrece insumos: ni en la búsqueda, ni en los más vendidos, ni entre los archivados
 * que se ofrece reactivar. Lo que se vende y también va en recetas sí se ofrece, y un combo sigue
 * contando el stock de sus insumos. Datos inventados.
 */
class SaleCatalogUsageTest {
    private val database = InMemoryDatabase()
    private val products = FakeProductRepository(database)
    private val methods = FakePaymentMethodRepository(database)

    /** 2026-11-02 15:00 UTC. */
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val counts = MutableStateFlow(mapOf(FLOUR to 9, BREAD to 4))

    private fun useCase() = ObserveSaleCatalogUseCase(
        products,
        FakeUnitRepository(database),
        SaleFrequencySource { counts },
        methods,
        BusinessCalendarSource(
            FakeAppSettingsRepository(appSettings(regionalSettings("UTC", DayOfWeek.MONDAY))),
        ),
        FakeTaxSettingsRepository(database),
        FakeTaxRateRepository(database),
        FixedClock(now),
        recipes = FakeProductComponentRepository(database),
    )

    private fun seed() {
        methods.seedInitialMethods(now)
        database.seed { tables ->
            tables.copy(
                products = mapOf(
                    FLOUR to product(
                        FLOUR,
                        name = "Harina",
                        onHandMilli = 5_000,
                        usage = ProductUsage.INPUT,
                    ),
                    SUGAR to product(SUGAR, name = "Azucar", usage = ProductUsage.BOTH),
                    BREAD to product(
                        BREAD,
                        name = "Pan",
                        salePrice = 500,
                        stockMode = ProductStockMode.COMPONENTS,
                    ),
                    OLD_YEAST to product(
                        OLD_YEAST,
                        name = "Levadura",
                        archived = true,
                        usage = ProductUsage.INPUT,
                    ),
                    OLD_SALT to product(OLD_SALT, name = "Sal", archived = true),
                ),
                productComponents = mapOf(
                    1L to RecipeComponent(1L, BREAD, FLOUR, QuantityMilli(1_000)),
                ),
                units = mapOf(1L to measureUnit(1L, "UNIT", UnitKind.COUNT, false)),
            )
        }
    }

    @Test
    fun `an input is not offered, but what is also sold is`() = runTest {
        seed()

        val catalog = useCase()().first()

        assertEquals(listOf(SUGAR, BREAD), catalog.products.map { it.id }.sorted())
        // Harina es la más vendida como hoja de combo, pero no se ofrece: Pan va primero.
        assertEquals(BREAD, catalog.frequent.first().id)
        assertEquals(false, catalog.frequent.any { it.id == FLOUR })
        assertEquals(listOf(OLD_SALT), catalog.archived.map { it.id })
    }

    @Test
    fun `a combo still counts the stock of its inputs`() = runTest {
        seed()

        val catalog = useCase()().first()

        // Cinco de harina, una por pan: alcanzan cinco panes.
        assertEquals(5L, catalog.comboAvailability[BREAD])
    }

    @Test
    fun `for sale keeps everything else as it was`() {
        val input = product(FLOUR, usage = ProductUsage.INPUT)
        val both = product(SUGAR, usage = ProductUsage.BOTH)
        val sale = product(BREAD)
        val archivedInput = product(OLD_YEAST, archived = true, usage = ProductUsage.INPUT)
        val catalog = SaleCatalog(
            products = listOf(input, both, sale),
            frequent = listOf(input, sale),
            archived = listOf(archivedInput),
            units = emptyMap(),
            methods = emptyList(),
            taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
            taxRates = emptyMap(),
            today = null,
            weekStartDay = null,
            comboAvailability = mapOf(BREAD to 5L),
        )

        val offered = catalog.forSale()

        assertEquals(listOf(both, sale), offered.products)
        assertEquals(listOf(sale), offered.frequent)
        assertEquals(emptyList<Any>(), offered.archived)
        assertEquals(catalog.comboAvailability, offered.comboAvailability)
        assertEquals(catalog.taxes, offered.taxes)
    }

    private companion object {
        const val FLOUR = 1L
        const val SUGAR = 2L
        const val BREAD = 3L
        const val OLD_YEAST = 4L
        const val OLD_SALT = 5L
    }
}
