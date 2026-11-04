package io.github.yesidrobayo.shopledger.data.local.pricing

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los precios por lista: solo en una lista fija distinta de la inicial, uno por lista y producto,
 * cambiar el precio no toca lo demás, quitarlo deja el de la inicial, y con el producto se borran.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductPriceDaoTest : CatalogDatabaseTest() {
    private val prices get() = database.productPriceDao()
    private var wholesale = 0L
    private var productId = 0L

    @Before
    fun createListAndProduct() = runBlocking {
        wholesale = database.priceListDao().insert(fixedList("Mayorista"))
        productId = database.productDao().insert(product("Café"))
    }

    @Test
    fun aPriceReadsBackWithEveryColumn() = runBlocking {
        val price = productPrice(wholesale, productId, price = 2_300).copy(updatedAt = LATER)

        val id = prices.insert(price)

        assertEquals(price.copy(id = id), prices.find(wholesale, productId))
        assertEquals(listOf(price.copy(id = id)), prices.findByProduct(productId))
        assertNull(prices.find(wholesale, MISSING_ID))
    }

    @Test
    fun aProductHasOnePricePerListAndOnlyInAFixedListOtherThanTheInitial() = runBlocking {
        prices.insert(productPrice(wholesale, productId, price = 2_300))
        constraintFailure(UNIQUE) {
            prices.insert(productPrice(wholesale, productId, price = 2_200))
        }

        // La inicial usa el precio de venta, y una relativa se calcula: ninguna guarda filas.
        val initial = requireNotNull(database.priceListDao().findBySystemKey("DEFAULT")).id
        val relative =
            database.priceListDao().insert(relativeList("Relativa", wholesale, adjustmentBp = -500))
        guardFailure(GUARD) { prices.insert(productPrice(initial, productId, price = 2_300)) }
        guardFailure(GUARD) { prices.insert(productPrice(relative, productId, price = 2_300)) }
        // Una lista que no existe ya no es una lista fija: la guarda la rechaza antes que su llave.
        guardFailure(GUARD) { prices.insert(productPrice(MISSING_ID, productId, 1)) }
        constraintFailure(FOREIGN_KEY) { prices.insert(productPrice(wholesale, MISSING_ID, 1)) }

        // Los topes del precio por unidad.
        val other = database.productDao().insert(product("Té"))
        prices.insert(productPrice(wholesale, other, price = Limit.UNIT_MONEY.max))
        guardFailure(GUARD) {
            prices.updatePrice(wholesale, other, Limit.UNIT_MONEY.max + 1, updatedAt = LATER)
        }
        guardFailure(GUARD) { prices.updatePrice(wholesale, other, price = -1, updatedAt = LATER) }
        assertEquals(Limit.UNIT_MONEY.max, prices.find(wholesale, other)?.price)
        assertEquals(2L, count("SELECT COUNT(*) FROM product_prices"))
    }

    @Test
    fun updatePriceChangesOnlyThePrice() = runBlocking {
        val price = productPrice(wholesale, productId, price = 2_300)
        val id = prices.insert(price)

        assertEquals(1, prices.updatePrice(wholesale, productId, price = 2_450, updatedAt = LATER))

        assertEquals(
            price.copy(id = id, price = 2_450, updatedAt = LATER),
            prices.find(wholesale, productId),
        )
        assertEquals(0, prices.updatePrice(wholesale, MISSING_ID, price = 1, updatedAt = LATER))
    }

    @Test
    fun aListEmitsItsPricesAgainWhenOneIsAddedOrRemoved() = runBlocking {
        val tea = database.productDao().insert(product("Té"))
        val emissions = Emissions(this, prices.observeByPriceList(wholesale).ids { it.id })
        try {
            emissions.awaitValue(emptyList())
            val coffeePrice = prices.insert(productPrice(wholesale, productId, price = 2_300))
            val teaPrice = prices.insert(productPrice(wholesale, tea, price = 1_900))
            emissions.awaitValue(listOf(coffeePrice, teaPrice))

            // Quitado de la lista, el café vuelve a tomar el precio de la inicial.
            assertEquals(1, prices.delete(wholesale, productId))
            emissions.awaitValue(listOf(teaPrice))
        } finally {
            emissions.stop()
        }
        assertNull(prices.find(wholesale, productId))
        assertEquals(0, prices.delete(wholesale, productId))
    }

    @Test
    fun deletingAProductDeletesItsPrices() = runBlocking {
        val tea = database.productDao().insert(product("Té"))
        prices.insert(productPrice(wholesale, productId, price = 2_300))
        prices.insert(productPrice(wholesale, tea, price = 1_900))

        assertEquals(1, database.productDao().delete(productId))

        assertEquals(emptyList<ProductPriceEntity>(), prices.findByProduct(productId))
        assertEquals(1, prices.findByProduct(tea).size)
    }

    @Test
    fun indexesAndKeysAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "unique index_product_prices_price_list_id_product_id (price_list_id, product_id)",
                "index_product_prices_product_id (product_id)",
            ),
            database.indexesOf("product_prices"),
        )
        assertEquals(
            listOf("price_list_id -> price_lists RESTRICT", "product_id -> products CASCADE"),
            database.foreignKeysOf("product_prices"),
        )
    }

    private companion object {
        const val GUARD = "guard:product_prices"
        const val MISSING_ID = 999L
    }
}
