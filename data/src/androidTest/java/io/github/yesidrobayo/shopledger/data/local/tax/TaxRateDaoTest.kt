package io.github.yesidrobayo.shopledger.data.local.tax

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las tasas de impuesto: la base nace sin ninguna, cada columna vuelve como se guardó, la tasa va
 * de 0 a 10.000 puntos básicos, el nombre es único también frente a una archivada, las listas van
 * por creación, y borrar una tasa deja sin tasa a sus productos.
 */
@RunWith(AndroidJUnit4::class)
internal class TaxRateDaoTest : CatalogDatabaseTest() {
    private val rates get() = database.taxRateDao()
    private val products get() = database.productDao()

    @Test
    fun aNewDatabaseHasNoTaxRates() = runBlocking {
        // Ninguna tasa de ningún país: las escribe la persona.
        assertEquals(0L, count("SELECT COUNT(*) FROM tax_rates"))
    }

    @Test
    fun aRateReadsBackWithEveryColumn() = runBlocking {
        val rate = taxRate("Tasa general", rateBp = 2_100).copy(updatedAt = LATER)

        val id = rates.insert(rate)

        assertEquals(rate.copy(id = id), rates.findById(id))
        assertEquals(id, rates.findByNormalizedName("tasa general")?.id)
        assertNull(rates.findById(MISSING_ID))
        assertNull(rates.findByNormalizedName("otra"))
    }

    @Test
    fun theRateGoesFromZeroToTenThousandBasisPoints() = runBlocking {
        val none = rates.insert(taxRate("Exenta", rateBp = 0))
        val whole = rates.insert(taxRate("Completa", rateBp = 10_000))

        guardFailure(GUARD) { rates.insert(taxRate("Negativa", rateBp = -1)) }
        guardFailure(GUARD) { rates.insert(taxRate("Excesiva", rateBp = 10_001)) }
        guardFailure(GUARD) { rates.update(whole, "Completa", "completa", 10_001, LATER) }
        guardFailure(GUARD) { rates.update(none, "Exenta", "exenta", -1, LATER) }

        assertEquals(0L, rates.findById(none)?.rateBp)
        assertEquals(10_000L, rates.findById(whole)?.rateBp)
        assertEquals(2L, count("SELECT COUNT(*) FROM tax_rates"))
    }

    @Test
    fun theNameIsUniqueEvenAgainstAnArchivedRate() = runBlocking {
        val general = rates.insert(taxRate("Tasa Básica", rateBp = 1_900))
        // Tildes, mayúsculas y espacios de más no hacen otro nombre.
        constraintFailure(UNIQUE) { rates.insert(taxRate("  TASA   BASICA ", rateBp = 500)) }

        // A diferencia de productos y proveedores, una archivada sigue reservando su nombre.
        rates.setArchivedAt(general, archivedAt = LATER, updatedAt = LATER)
        constraintFailure(UNIQUE) { rates.insert(taxRate("Tasa básica", rateBp = 500)) }
        assertEquals(general, rates.findByNormalizedName("tasa basica")?.id)

        // Otra no se renombra a ese nombre, y la tasa con ese nombre queda como estaba.
        val reduced = rates.insert(taxRate("Reducida", rateBp = 500))
        constraintFailure(UNIQUE) {
            rates.update(reduced, "Tasa básica", "tasa basica", 500, MUCH_LATER)
        }
        assertEquals("reducida", rates.findById(reduced)?.normalizedName)
        assertEquals(LATER, rates.findById(general)?.archivedAt)
    }

    @Test
    fun listsFollowCreationOrderAndEmitAgainOnArchiveAndReactivation() = runBlocking {
        // El orden es el de creación, no el alfabético.
        val zeta = rates.insert(taxRate("Zona franca", rateBp = 0))
        val alfa = rates.insert(taxRate("Alimentos", rateBp = 500))
        val beta = rates.insert(taxRate("Bebidas", rateBp = 1_900))

        val active = Emissions(this, rates.observeActive().ids { it.id })
        val archived = Emissions(this, rates.observeArchived().ids { it.id })
        try {
            active.awaitValue(listOf(zeta, alfa, beta))
            archived.awaitValue(emptyList())

            rates.setArchivedAt(zeta, archivedAt = LATER, updatedAt = LATER)
            rates.setArchivedAt(beta, archivedAt = LATER, updatedAt = LATER)
            active.awaitValue(listOf(alfa))
            archived.awaitValue(listOf(zeta, beta))

            // Reactivada, vuelve a su lugar de creación.
            rates.setArchivedAt(zeta, archivedAt = null, updatedAt = MUCH_LATER)
            active.awaitValue(listOf(zeta, alfa))
            archived.awaitValue(listOf(beta))
        } finally {
            active.stop()
            archived.stop()
        }
        assertEquals(MUCH_LATER, rates.findById(zeta)?.updatedAt)
        assertEquals(0, rates.setArchivedAt(MISSING_ID, archivedAt = LATER, updatedAt = LATER))
    }

    @Test
    fun updateChangesWhatThePersonWritesAndKeepsTheRest() = runBlocking {
        val rate = taxRate("General", rateBp = 1_900)
        val id = rates.insert(rate)
        rates.setArchivedAt(id, archivedAt = LATER, updatedAt = LATER)

        val changed = rates.update(id, "General nueva", "general nueva", 2_100, MUCH_LATER)

        assertEquals(1, changed)
        val expected =
            rate.copy(
                id = id,
                name = "General nueva",
                normalizedName = "general nueva",
                rateBp = 2_100,
                archivedAt = LATER,
                createdAt = CREATED_AT,
                updatedAt = MUCH_LATER,
            )
        assertEquals(expected, rates.findById(id))
        assertEquals(0, rates.update(MISSING_ID, "Nada", "nada", 0, LATER))
    }

    @Test
    fun deletingARateLeavesItsProductsWithoutRate() = runBlocking {
        val general = rates.insert(taxRate("General", rateBp = 1_900))
        val reduced = rates.insert(taxRate("Reducida", rateBp = 500))
        val water = products.insert(product("Agua").copy(taxRateId = general))
        val juice = products.insert(product("Jugo", archivedAt = LATER).copy(taxRateId = general))
        val bread = products.insert(product("Pan").copy(taxRateId = reduced))

        // Cuenta también los archivados: es lo que se avisa antes de eliminarla.
        assertEquals(2, rates.countProducts(general))

        assertEquals(1, rates.delete(general))

        assertNull(rates.findById(general))
        assertNull(products.findById(water)?.taxRateId)
        assertNull(products.findById(juice)?.taxRateId)
        assertEquals(reduced, products.findById(bread)?.taxRateId)
        assertEquals(0, rates.countProducts(general))
        assertEquals(0, rates.delete(general))
    }

    @Test
    fun aProductCannotPointToAMissingRate() = runBlocking {
        constraintFailure(FOREIGN_KEY) {
            products.insert(product("Huérfano").copy(taxRateId = MISSING_ID))
        }
        val id = products.insert(product("Sin tasa"))
        constraintFailure(FOREIGN_KEY) {
            execute("UPDATE products SET tax_rate_id = $MISSING_ID WHERE id = $id")
        }

        assertNull(products.findById(id)?.taxRateId)
        assertEquals(1L, count("SELECT COUNT(*) FROM products"))
    }

    @Test
    fun indexesAndKeysAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf("unique index_tax_rates_normalized_name (normalized_name)"),
            database.indexesOf("tax_rates"),
        )
        assertEquals(emptyList<String>(), database.foreignKeysOf("tax_rates"))
        assertEquals(
            listOf(
                "index_products_tax_rate_id (tax_rate_id)",
            ),
            database.indexesOf("products").filter { "tax_rate_id" in it },
        )
        assertEquals(
            listOf("tax_rate_id -> tax_rates SET NULL"),
            database.foreignKeysOf("products").filter { it.startsWith("tax_rate_id ") },
        )
    }

    private companion object {
        const val GUARD = "guard:tax_rates"
        const val MISSING_ID = 999L
    }
}
