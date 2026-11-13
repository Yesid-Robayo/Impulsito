package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.DeletePriceListUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ListPriceResolver
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.PriceListLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las listas de precios sobre la base real: los ciclos se rechazan con su resultado, la cadena de
 * relativas da el precio de las reglas, y cambiar un precio de lista deja su fila de historial con
 * esa lista. Los nombres y precios son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PriceListOnDatabaseTest : RepositoryTest() {
    private val lists by lazy { RoomPriceListRepository(database) }
    private val products by lazy { RoomProductRepository(database) }
    private val history by lazy { RoomPriceHistoryRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val clock = Clock { LATER_AT }

    private val save by lazy { SavePriceListUseCase(runner, lists, lists, clock) }
    private val delete by lazy { DeletePriceListUseCase(runner, lists) }
    private val setPrice by lazy {
        SetListPriceUseCase(
            runner,
            lists,
            lists,
            products,
            history,
            BusinessCalendarSource(settings),
            CurrencyLock(settings),
            clock,
        )
    }

    @Test
    fun aNewBaseHasTheInitialListAndItIsTheSalePrice() = runBlocking {
        val productId = database.withWriteTransaction {
            database.productDao().insert(product("Producto inventado"))
        }
        val defaultId =
            requireNotNull(RoomSystemRowRepository(database).findId(SystemRow.DEFAULT_PRICE_LIST))

        val active = lists.observeActive().first()

        // La base de prueba ya trae otra lista además de la inicial; la inicial va primero.
        assertEquals(defaultId, active.first().id)
        assertTrue(active.first().isDefault)
        assertEquals(listOf(defaultId), active.filter { it.isDefault }.map { it.id })
        assertEquals(
            ListPriceResult.Priced(Money(2_500L), ListPriceOrigin.SALE_PRICE),
            ListPriceResolver(lists, lists).priceOf(defaultId, productId, Money(2_500L)),
        )
    }

    @Test
    fun relativeChainsGiveTheRulePricesAndCyclesAreRefused() = runBlocking {
        val defaultId = defaultList()
        val minus15 = saved(save(null, relative("Especial", defaultId, -1_500L)))
        val plus5 = saved(save(null, relative("Otra", minus15, 500L)))
        val resolver = ListPriceResolver(lists, lists)

        assertEquals(
            ListPriceResult.Priced(Money(8_500L), ListPriceOrigin.CALCULATED),
            resolver.priceOf(minus15, 1L, Money(10_000L)),
        )
        assertEquals(
            ListPriceResult.Priced(Money(8_925L), ListPriceOrigin.CALCULATED),
            resolver.priceOf(plus5, 1L, Money(10_000L)),
        )
        val before = changeSeq()
        assertTrue(lists.wouldCreateCycle(minus15, plus5))
        assertEquals(
            SavePriceListResult.CreatesCycle,
            save(minus15, relative("Especial", plus5, 0L)),
        )
        assertEquals(before, changeSeq())
        assertEquals(
            PriceListPricing.Relative(defaultId, BasisPoints(-1_500L)),
            requireNotNull(lists.findById(minus15)).pricing,
        )
    }

    @Test
    fun aListPriceChangeLeavesHistoryWithItsListAndOneChange() = runBlocking {
        val productId = database.withWriteTransaction {
            database.productDao().insert(product("Producto inventado"))
        }
        val wholesale =
            saved(save(null, PriceListDraft("Mayorista", PriceListKind.FIXED, null, null)))
        val before = changeSeq()

        val result = setPrice(wholesale, productId, Money(2_200L)) as SetListPriceResult.Changed

        assertEquals(before + 1, changeSeq())
        val row = history.findByProduct(productId).single { it.id == result.historyId }.content
        assertEquals(wholesale, row.priceListId)
        assertEquals(Money(2_500L), row.oldPrice)
        assertEquals(Money(2_200L), row.newPrice)
        assertEquals(PriceChangeSource.MANUAL, row.source)
        assertEquals(mapOf(wholesale to Money(2_200L)), lists.findByProduct(productId))
        assertEquals(mapOf(productId to Money(2_200L)), lists.observeByList(wholesale).first())
        // Con precios propios, la lista tiene uso, no se elimina ni pasa a relativa.
        assertTrue(lists.isInUse(wholesale))
        assertTrue(wholesale in lists.observeInUse().first())
        assertEquals(PriceListLifecycleResult.InUse, delete(wholesale))
        assertEquals(
            SavePriceListResult.HasOwnPrices,
            save(wholesale, relative("Mayorista", defaultList(), 0L)),
        )
        // Quitar el precio propio deja otra fila, hacia el precio de venta.
        val removed = setPrice(wholesale, productId, null) as SetListPriceResult.Changed
        val removeRow = history.findByProduct(productId).single {
            it.id == removed.historyId
        }.content
        assertEquals(Money(2_200L) to Money(2_500L), removeRow.oldPrice to removeRow.newPrice)
        assertFalse(lists.hasAny(wholesale))
    }

    @Test
    fun aListWithoutUseIsDeletedAndTheInitialOneIsProtected() = runBlocking {
        val spare = saved(save(null, PriceListDraft("Sobrante", PriceListKind.FIXED, null, null)))

        assertFalse(lists.isInUse(spare))
        assertEquals(PriceListLifecycleResult.Done, delete(spare))
        assertEquals(null, lists.findById(spare))
        assertEquals(PriceListLifecycleResult.Protected, delete(defaultList()))
    }

    private suspend fun defaultList(): Long =
        requireNotNull(RoomSystemRowRepository(database).findId(SystemRow.DEFAULT_PRICE_LIST))

    private fun saved(result: SavePriceListResult): Long = (result as SavePriceListResult.Saved).id

    private fun relative(name: String, base: Long, bp: Long) =
        PriceListDraft(name, PriceListKind.RELATIVE, base, BasisPoints(bp))
}
