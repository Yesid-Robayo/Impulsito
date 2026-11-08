package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las tasas y los ajustes de impuestos: el orden, el archivo, los productos que usan cada tasa, y
 * que nada de lo que se hace con una tasa cambia una venta ya registrada. Los nombres y porcentajes
 * son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class TaxRepositoriesTest : RepositoryTest() {
    private val rates by lazy { RoomTaxRateRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val taxSettings by lazy { RoomTaxSettingsRepository(database, settings) }
    private val sales by lazy { RoomSaleRepository(database) }

    @Test
    fun ratesComeBackInCreationOrderAndArchivingMovesThemToTheirList() = runBlocking {
        val (second, first) =
            commit {
                rates.insert(details("Tasa B", 500), AT) to
                    rates.insert(details("Tasa A", 2_100), AT)
            }

        assertEquals(
            listOf(second, first),
            rates.observe(ArchiveFilter.ACTIVE).first().map(TaxRate::id),
        )
        rates.observe(ArchiveFilter.ARCHIVED).test {
            awaitValue(emptyList())
            commit { rates.setArchivedAt(second, archivedAt = LATER_AT, updatedAt = LATER_AT) }
            val archived = awaitItem().single()
            assertEquals(second, archived.id)
            assertEquals(LATER_AT, archived.archivedAt)
            assertEquals(details("Tasa B", 500), archived.details)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(first), rates.observe(ArchiveFilter.ACTIVE).first().map(TaxRate::id))
        assertEquals(first, rates.findActiveByNormalizedName("tasa a")?.id)
        assertNull(rates.findActiveByNormalizedName("tasa b"))
        // Reactivarla la devuelve con lo que tenía.
        commit { rates.setArchivedAt(second, archivedAt = null, updatedAt = LATER_AT) }
        assertNull(rates.findById(second)?.archivedAt)
    }

    @Test
    fun theProductCountOfEachRateFollowsTheProducts() = runBlocking {
        val rate = commit { rates.insert(details("Tasa A", 2_100), AT) }
        rates.observeProductCounts().test {
            awaitValue(emptyMap())
            database.productDao().insert(product("Té").copy(taxRateId = rate))
            awaitValue(mapOf(rate to 1))
            // Un producto archivado también la usa.
            database.productDao().insert(
                product("Mate").copy(taxRateId = rate, archivedAt = AT.toEpochMilliseconds()),
            )
            awaitValue(mapOf(rate to 2))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, rates.countProducts(rate))
    }

    @Test
    fun deletingARateLeavesItsProductsAndTheBusinessWithoutIt() = runBlocking {
        val rate = commit { rates.insert(details("Tasa A", 2_100), AT) }
        val tea = database.productDao().insert(product("Té").copy(taxRateId = rate))
        commit {
            taxSettings.update(TaxSettings(TaxMode.ADDED, rate, purchaseTaxRecoverable = false), AT)
        }

        commit { rates.delete(rate) }

        assertNull(rates.findById(rate))
        assertNull(database.productDao().findById(tea)?.taxRateId)
        assertEquals(
            TaxSettings(TaxMode.ADDED, null, purchaseTaxRecoverable = false),
            taxSettings.get(),
        )
    }

    @Test
    fun editingArchivingOrDeletingARateNeverChangesAPastSale() = runBlocking {
        val rate = commit { rates.insert(details("Tasa general", 1_900), AT) }
        val line = newSaleLine(productId, priceListId)
        val saleId =
            commit { sales.insert(newSale(priceListId)).also { sales.insertLine(it, line) } }
        val before = sales.findLines(saleId)

        commit { rates.update(rate, details("Tasa renombrada", 2_500), LATER_AT) }
        assertEquals(before, sales.findLines(saleId))
        commit { rates.setArchivedAt(rate, archivedAt = LATER_AT, updatedAt = LATER_AT) }
        assertEquals(before, sales.findLines(saleId))
        commit { rates.delete(rate) }

        val after = sales.findLines(saleId).single().content
        assertEquals(BasisPoints(1_900), after.taxRate)
        assertEquals("Tasa general", after.taxNameSnapshot)
        assertEquals(before, sales.findLines(saleId))
    }

    @Test
    fun theTaxSettingsChangeTogetherAndCountAsAChange() = runBlocking {
        assertEquals(
            TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
            taxSettings.get(),
        )
        val rate = commit { rates.insert(details("Tasa A", 2_100), AT) }
        val changesBefore = changeSeq()

        taxSettings.observe().test {
            awaitValue(TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false))
            val changed = TaxSettings(TaxMode.INCLUDED, rate, purchaseTaxRecoverable = true)
            commit { taxSettings.update(changed, LATER_AT) }
            awaitValue(changed)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(changesBefore + 1, changeSeq())
        // Los demás ajustes no se tocan.
        assertEquals(LATER_AT, settings.get().updatedAt)
    }

    @Test
    fun aDefaultRateThatDoesNotExistIsRefused() = runBlocking {
        val failure =
            storageFailureOf {
                commit {
                    taxSettings.update(
                        TaxSettings(TaxMode.ADDED, MISSING_RATE, purchaseTaxRecoverable = false),
                        AT,
                    )
                }
            }
        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertNull(taxSettings.get().defaultTaxRateId)
    }

    private fun details(name: String, rateBp: Long) =
        TaxRateDetails(name, TextNormalizer.normalize(name), BasisPoints(rateBp))

    private companion object {
        const val MISSING_RATE = 999L
    }
}
