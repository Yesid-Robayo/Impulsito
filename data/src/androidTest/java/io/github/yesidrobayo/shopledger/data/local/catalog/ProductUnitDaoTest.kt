package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.ids
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las conversiones de cada producto: cada columna en su lugar, una sola por producto y unidad,
 * llaves que exigen producto y unidad, cascada con el producto, orden por creación, y una edición
 * que toca solo sus columnas.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductUnitDaoTest : CatalogDatabaseTest() {
    private val conversions get() = database.productUnitDao()
    private val products get() = database.productDao()

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val productId = products.insert(product("Gaseosa"))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val box =
            ProductUnitEntity(
                productId = productId,
                unitId = BOX_ID,
                factorMilli = 1_000_000_000,
                salePrice = 1_000_000_000_000,
                barcode = "7702004003",
                forSale = 0,
                forPurchase = 1,
                createdAt = CREATED_AT,
                updatedAt = LATER,
            )

        val id = conversions.insert(box)

        assertEquals(box.copy(id = id), conversions.findById(id))
        assertEquals(id, conversions.findByProductAndUnit(productId, BOX_ID)?.id)
        assertEquals(id, conversions.findByBarcode("7702004003")?.id)
        assertNull(conversions.findById(MISSING_ID))
        assertNull(conversions.findByProductAndUnit(productId, GRAM_ID))
        assertNull(conversions.findByBarcode("7702004004"))
    }

    @Test
    fun aProductHasOneConversionPerUnit() = runBlocking {
        val soda = products.insert(product("Gaseosa"))
        val water = products.insert(product("Agua"))
        conversions.insert(conversion(soda, BOX_ID))

        constraintFailure(UNIQUE) {
            conversions.insert(conversion(soda, BOX_ID).copy(factorMilli = 12_000))
        }
        // Otra unidad del mismo producto, o la misma unidad de otro producto, sí.
        conversions.insert(conversion(soda, PACK_ID).copy(factorMilli = 6_000))
        conversions.insert(conversion(water, BOX_ID))
        assertEquals(
            listOf(24_000L, 6_000L),
            conversions.findByProduct(soda).map { it.factorMilli },
        )
    }

    @Test
    fun aConversionNeedsItsProductAndUnitAndGoesWithItsProduct() = runBlocking {
        val soda = products.insert(product("Gaseosa"))
        constraintFailure(FOREIGN_KEY) { conversions.insert(conversion(MISSING_ID, BOX_ID)) }
        constraintFailure(FOREIGN_KEY) { conversions.insert(conversion(soda, MISSING_ID)) }
        conversions.insert(conversion(soda, BOX_ID))
        conversions.insert(conversion(soda, PACK_ID))

        // La unidad no se borra mientras una conversión la use. El producto sin historia sí, y sus
        // conversiones se van con él en la misma sentencia.
        constraintFailure(FOREIGN_KEY) { database.unitDao().delete(BOX_ID) }
        assertEquals(1, products.delete(soda))
        assertEquals(emptyList<ProductUnitEntity>(), conversions.findByProduct(soda))
        assertEquals(1, database.unitDao().delete(BOX_ID))
    }

    @Test
    fun conversionsFollowCreationOrderAndEmitAgainOnEachChange() = runBlocking {
        val soda = products.insert(product("Gaseosa"))
        val other = products.insert(product("Agua"))
        conversions.insert(conversion(other, BOX_ID))

        val emissions = Emissions(this, conversions.observeByProduct(soda).ids { it.unitId })
        try {
            emissions.awaitValue(emptyList())
            val dozen = conversions.insert(conversion(soda, DOZEN_ID))
            val box = conversions.insert(conversion(soda, BOX_ID))
            // El orden es el de creación, no el de la unidad.
            emissions.awaitValue(listOf(DOZEN_ID, BOX_ID))

            assertEquals(1, conversions.delete(dozen))
            emissions.awaitValue(listOf(BOX_ID))
            assertEquals(1, conversions.delete(box))
            emissions.awaitValue(emptyList())
        } finally {
            emissions.stop()
        }
        assertEquals(0, conversions.delete(MISSING_ID))
        assertEquals(1, conversions.findByProduct(other).size)
    }

    @Test
    fun updateChangesTheConversionAndKeepsItsProductUnitAndCreation() = runBlocking {
        val soda = products.insert(product("Gaseosa"))
        val id = conversions.insert(conversion(soda, BOX_ID))
        val before = conversions.findById(id)

        val changed =
            conversions.update(
                id,
                factorMilli = 12_000,
                salePrice = 30_000,
                barcode = "7702",
                forSale = 1,
                forPurchase = 0,
                updatedAt = MUCH_LATER,
            )

        assertEquals(1, changed)
        assertEquals(
            before?.copy(
                factorMilli = 12_000,
                salePrice = 30_000,
                barcode = "7702",
                forPurchase = 0,
                updatedAt = MUCH_LATER,
            ),
            conversions.findById(id),
        )
        assertEquals(0, conversions.update(MISSING_ID, 1, null, null, 1, 1, LATER))
        // El factor sigue la guarda: nunca menos de una milésima de la base.
        guardFailure("guard:product_units") {
            conversions.update(id, 0, null, null, 1, 1, LATER)
        }
        assertEquals(12_000L, conversions.findById(id)?.factorMilli)
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
