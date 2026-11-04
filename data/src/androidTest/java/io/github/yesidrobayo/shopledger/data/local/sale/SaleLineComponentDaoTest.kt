package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLineComponent
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los componentes vendidos de un combo: cada columna en su lugar y entera en sus topes, el orden en
 * que se guardaron, llaves que impiden huérfanos y borrar un producto vendido como componente, y un
 * DAO que solo agrega y lee.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleLineComponentDaoTest : LedgerDatabaseTest() {
    private val components get() = database.saleLineComponentDao()

    @Test
    fun componentsReadBackByLineAndBySaleInTheOrderTheyWereSaved() = runBlocking {
        val saleId = database.saleDao().insert(sale(priceListId))
        val first = insertComboLineWithComponent(saleId)
        val second = insertComboLineWithComponent(saleId)
        val sugar = database.productDao().insert(product("Azúcar").copy(stockMode = "UNTRACKED"))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val untracked =
            saleLineComponent(first.saleLineId, sugar).copy(
                quantityMilli = Limit.LINE_QUANTITY_MILLI.max,
                tracked = 0,
                unitCost = Limit.UNIT_MONEY.max,
                lineCost = Limit.DOCUMENT_TOTAL.max,
            )
        val untrackedId = components.insert(untracked)
        val otherSale = database.saleDao().insert(sale(priceListId))
        insertComboLineWithComponent(otherSale)

        assertEquals(
            listOf(first, untracked.copy(id = untrackedId)),
            components.findBySaleLine(first.saleLineId),
        )
        assertEquals(
            listOf(first.id, second.id, untrackedId),
            components.findBySale(saleId).map { it.id },
        )
        assertEquals(emptyList<SaleLineComponentEntity>(), components.findBySale(MISSING_ID))
    }

    @Test
    fun keysRejectOrphansAndProtectAProductSoldAsAComponent() = runBlocking {
        val saleId = database.saleDao().insert(sale(priceListId))
        val component = insertComboLineWithComponent(saleId)

        // La guarda pide la línea de combo y el producto: sin ellos, rechaza antes que la llave.
        guardFailure(GUARD) { components.insert(saleLineComponent(MISSING_ID, productId)) }
        guardFailure(GUARD) {
            components.insert(saleLineComponent(component.saleLineId, MISSING_ID))
        }
        // El producto de la base no tiene libro ni otra línea: lo protege solo su componente.
        constraintFailure(FOREIGN_KEY) { database.productDao().delete(productId) }

        assertEquals(1L, count("SELECT COUNT(*) FROM sale_line_components"))
    }

    @Test
    fun theDaoOnlyAppendsAndReads() {
        // Sin funciones de editar ni de borrar, a propósito: el componente congela lo que salió del
        // stock y a qué costo.
        assertEquals(
            setOf("findBySaleLine", "findBySale", "insert"),
            functionsOf(SaleLineComponentDao::class.java),
        )
    }

    private companion object {
        const val GUARD = "guard:sale_line_components"
        const val MISSING_ID = 999L
    }
}
