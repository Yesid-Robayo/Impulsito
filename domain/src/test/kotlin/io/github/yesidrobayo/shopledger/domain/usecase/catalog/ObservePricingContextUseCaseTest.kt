package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeCategoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTaxRateRepository
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.taxRate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que el negocio fija para sugerir precios: su margen objetivo, el de las categorías que fijan
 * uno, las tasas también archivadas y el paso de redondeo. Datos inventados.
 */
class ObservePricingContextUseCaseTest {
    private val database = InMemoryDatabase()
    private val settings = FakeAppSettingsRepository(
        appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)).let { base ->
            base.copy(
                taxes = TaxSettings(
                    TaxMode.INCLUDED,
                    defaultTaxRateId = 20L,
                    purchaseTaxRecoverable = false,
                ),
                sales = base.sales.copy(defaultRoundingStep = Money(50L)),
            )
        },
    )
    private val useCase = ObservePricingContextUseCase(
        settings = settings,
        categories = FakeCategoryRepository(database),
        taxRates = FakeTaxRateRepository(database),
    )

    private fun category(id: Long, target: Long?) = Category(
        id = id,
        details = CategoryDetails("Category $id", "category $id", target?.let(::BasisPoints)),
        createdAt = SaleWorld.EPOCH,
        updatedAt = SaleWorld.EPOCH,
    )

    @Test
    fun `brings the business target, the category targets and every tax rate`() = runTest {
        database.seed { tables ->
            tables.copy(
                categories = mapOf(1L to category(1L, 4_000L), 2L to category(2L, null)),
                taxRates = mapOf(
                    20L to taxRate(20L, "Active tax", 1_900L),
                    21L to taxRate(21L, "Archived tax", 500L, archived = true),
                ),
            )
        }

        assertEquals(
            PricingContext(
                defaultTargetMargin = BasisPoints(3_000L),
                categoryTargets = mapOf(1L to BasisPoints(4_000L)),
                taxMode = TaxMode.INCLUDED,
                defaultTaxRateId = 20L,
                taxRates = mapOf(20L to BasisPoints(1_900L), 21L to BasisPoints(500L)),
                roundingStep = Money(50L),
            ),
            useCase().first(),
        )
    }

    @Test
    fun `a new business target reaches the open suggestion`() = runTest {
        val seen = mutableListOf<BasisPoints>()
        val job = launch { useCase().take(2).toList().mapTo(seen) { it.defaultTargetMargin } }
        testScheduler.runCurrent()

        val current = settings.settings.value
        settings.settings.value =
            current.copy(profile = current.profile.copy(defaultTargetMargin = BasisPoints(4_000L)))
        job.join()

        assertEquals(listOf(BasisPoints(3_000L), BasisPoints(4_000L)), seen)
    }
}
