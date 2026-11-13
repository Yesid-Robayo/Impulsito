package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceListRepository
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.testing.Tables
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.DEFAULT_PRICE_LIST_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.EARLIER
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * El catálogo en memoria de [CatalogFixture], con la lista inicial guardada como la deja la base y
 * los casos de uso de listas de precios conectados a él.
 */
internal class PriceListFixture(zoneId: String = "UTC", currencyConfirmed: Boolean = true) {
    val catalog = CatalogFixture(zoneId, currencyConfirmed)
    val database = catalog.database
    val priceLists = FakePriceListRepository(database)

    val save = SavePriceListUseCase(catalog.transactions, priceLists, priceLists, catalog.clock)
    val archive = ArchivePriceListUseCase(catalog.transactions, priceLists, catalog.clock)
    val reactivate = ReactivatePriceListUseCase(catalog.transactions, priceLists, catalog.clock)
    val delete = DeletePriceListUseCase(catalog.transactions, priceLists)
    val setPrice = SetListPriceUseCase(
        catalog.transactions,
        priceLists,
        priceLists,
        catalog.products,
        catalog.priceHistory,
        BusinessCalendarSource(catalog.settings),
        CurrencyLock(catalog.settings),
        catalog.clock,
    )
    val resolver = ListPriceResolver(priceLists, priceLists)
    val observe = ObservePriceListsUseCase(priceLists, priceLists)

    init {
        seedList(PriceList(DEFAULT_PRICE_LIST_ID, "DEFAULT", null, PriceListPricing.Fixed, null))
    }

    val tables: Tables get() = database.tables.value

    fun list(id: Long): PriceList = tables.priceLists.getValue(id)

    /** Siembra una lista ya guardada. */
    fun seedList(list: PriceList) {
        database.seed { tables ->
            tables.copy(
                priceLists = tables.priceLists + (list.id to list),
                lastIds = tables.lastIds +
                    ("price_lists" to maxOf(list.id, tables.lastIds["price_lists"] ?: 0L)),
            )
        }
    }

    fun seedFixed(id: Long, name: String, archived: Boolean = false) =
        seedList(PriceList(id, null, name, PriceListPricing.Fixed, if (archived) EARLIER else null))

    fun seedRelative(id: Long, name: String, baseId: Long, bp: Long, archived: Boolean = false) =
        seedList(
            PriceList(
                id,
                null,
                name,
                PriceListPricing.Relative(baseId, BasisPoints(bp)),
                if (archived) EARLIER else null,
            ),
        )

    /** Siembra un precio propio de [productId] en [listId]. */
    fun seedOwnPrice(listId: Long, productId: Long, price: Money) {
        database.seed {
            it.copy(productPrices = it.productPrices + ((listId to productId) to price))
        }
    }

    companion object {
        /**
         * Corre [run] una vez para contar sus escrituras, y después una vez por cada una, en una base
         * nueva preparada por [prepare], con una falla en esa escritura. Exige que no quede nada.
         */
        suspend fun assertEachWriteFailureLeavesNothing(
            prepare: PriceListFixture.() -> Unit,
            run: suspend PriceListFixture.() -> Unit,
        ) {
            val counting = PriceListFixture().apply(prepare)
            counting.run()
            val writes = counting.database.lastTransactionWrites
            assertTrue("La operación debe escribir algo", writes > 0)
            for (failAt in 1..writes) {
                val fixture = PriceListFixture().apply(prepare)
                val before = fixture.tables
                fixture.database.failAtWrite = failAt
                val failed = try {
                    fixture.run()
                    false
                } catch (_: InjectedFailure) {
                    true
                }
                assertTrue("La escritura $failAt debió fallar", failed)
                assertEquals("La escritura $failAt dejó algo", before, fixture.tables)
            }
        }
    }
}
