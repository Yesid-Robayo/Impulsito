package io.github.yesidrobayo.shopledger.data.local.pricing

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
import io.github.yesidrobayo.shopledger.data.local.sale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las listas de precios: la base nace con la inicial, que nunca se archiva, nunca deja de ser fija
 * y nunca se borra; cada columna vuelve como se guardó; clave única y nombre único entre las
 * vigentes; listas por `id`; la
 * cadena de bases nunca forma un ciclo; y una lista en uso no se borra.
 */
@RunWith(AndroidJUnit4::class)
internal class PriceListDaoTest : CatalogDatabaseTest() {
    private val lists get() = database.priceListDao()

    @Test
    fun aNewDatabaseHasOnlyTheInitialFixedList() = runBlocking {
        val initial = requireNotNull(lists.findBySystemKey("DEFAULT"))

        // Sin nombre: se muestra traducido de su clave, y su precio es el de venta del producto.
        val expected =
            PriceListEntity(
                id = initial.id,
                systemKey = "DEFAULT",
                name = null,
                normalizedName = null,
                kind = "FIXED",
                basePriceListId = null,
                adjustmentBp = null,
                archivedAt = null,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            )
        assertEquals(expected, initial)
        assertEquals(1L, count("SELECT COUNT(*) FROM price_lists"))
        assertNull(lists.findBySystemKey("WHOLESALE"))
    }

    @Test
    fun aRelativeListReadsBackWithEveryColumn() = runBlocking {
        val base = lists.insert(fixedList("Mayorista"))
        val list =
            relativeList("Mayorista menos", base, adjustmentBp = -1_500).copy(updatedAt = LATER)

        val id = lists.insert(list)

        assertEquals(list.copy(id = id), lists.findById(id))
        assertEquals(id, lists.findActiveByNormalizedName("mayorista menos")?.id)
        assertNull(lists.findById(MISSING_ID))
        assertNull(lists.findActiveByNormalizedName("otra"))
    }

    @Test
    fun keysCannotRepeatAndNamesAreUniqueOnlyAmongActiveLists() = runBlocking {
        constraintFailure(UNIQUE) {
            lists.insert(
                fixedList("Otra").copy(systemKey = "DEFAULT", name = null, normalizedName = null),
            )
        }
        val id = lists.insert(fixedList("Clientes Frecuentes"))
        // Tildes, mayúsculas y espacios de más no hacen otro nombre: la guarda lo rechaza.
        guardFailure(GUARD) { lists.insert(fixedList("  CLIENTES   FRECUENTES ")) }

        // Una archivada no reserva su nombre, y no se reactiva mientras otra vigente lo tenga.
        lists.setArchivedAt(id, archivedAt = LATER, updatedAt = LATER)
        val again = lists.insert(fixedList("Clientes frecuentes"))
        assertEquals(again, lists.findActiveByNormalizedName("clientes frecuentes")?.id)
        guardFailure(GUARD) { lists.setArchivedAt(id, archivedAt = null, updatedAt = MUCH_LATER) }
        val other = lists.insert(fixedList("Eventos"))
        guardFailure(GUARD) {
            lists.update(
                other,
                "Clientes frecuentes",
                "clientes frecuentes",
                "FIXED",
                null,
                null,
                LATER,
            )
        }
        assertEquals(LATER, lists.findById(id)?.archivedAt)
        assertEquals(4L, count("SELECT COUNT(*) FROM price_lists"))
    }

    @Test
    fun theChainOfBasesNeverFormsACycle() = runBlocking {
        // Una fija, otra relativa a ella y una tercera relativa a la segunda.
        val first = lists.insert(fixedList("Primera"))
        val second = lists.insert(relativeList("Segunda", first, adjustmentBp = -1_500))
        val third = lists.insert(relativeList("Tercera", second, adjustmentBp = 500))

        assertTrue(lists.wouldCreateCycle(first, basePriceListId = third))
        assertTrue(lists.wouldCreateCycle(second, basePriceListId = third))
        assertTrue(lists.wouldCreateCycle(second, basePriceListId = second))
        assertFalse(lists.wouldCreateCycle(third, basePriceListId = first))

        // La primera no puede pasar a depender de la tercera, ni la segunda de sí misma: no cambia
        // nada.
        assertEquals(0, lists.update(first, "Primera", "primera", "RELATIVE", third, 100, LATER))
        assertEquals(0, lists.update(second, "Segunda", "segunda", "RELATIVE", second, 100, LATER))
        assertEquals(fixedList("Primera").copy(id = first), lists.findById(first))
        assertEquals(first, lists.findById(second)?.basePriceListId)

        // La tercera sí puede pasar a depender directamente de la primera.
        assertEquals(
            1,
            lists.update(third, "Tercera", "tercera", "RELATIVE", first, 800, MUCH_LATER),
        )
        assertEquals(first, lists.findById(third)?.basePriceListId)
        assertEquals(800L, lists.findById(third)?.adjustmentBp)
    }

    @Test
    fun updateChangesWhatThePersonWritesAndTheGuardKeepsKindAndBaseTogether() = runBlocking {
        val base = lists.insert(fixedList("Base"))
        val id = lists.insert(fixedList("Evento"))
        lists.setArchivedAt(id, archivedAt = LATER, updatedAt = LATER)

        val changed =
            lists.update(
                id,
                "Evento especial",
                "evento especial",
                "RELATIVE",
                base,
                1_000,
                MUCH_LATER,
            )

        assertEquals(1, changed)
        val expected =
            fixedList("Evento especial").copy(
                id = id,
                kind = "RELATIVE",
                basePriceListId = base,
                adjustmentBp = 1_000,
                archivedAt = LATER,
                updatedAt = MUCH_LATER,
            )
        assertEquals(expected, lists.findById(id))
        // Una relativa necesita base y ajuste, y una fija no lleva ninguno de los dos.
        guardFailure(GUARD) { lists.update(id, "Evento", "evento", "RELATIVE", null, 1_000, LATER) }
        guardFailure(GUARD) { lists.update(id, "Evento", "evento", "FIXED", null, 1_000, LATER) }
        guardFailure(GUARD) {
            lists.update(id, "Evento", "evento", "RELATIVE", base, -10_001, LATER)
        }
        assertEquals(0, lists.update(MISSING_ID, "Nada", "nada", "FIXED", null, null, LATER))
    }

    @Test
    fun theInitialListIsNeverArchivedChangedToRelativeNorDeleted() = runBlocking {
        val initial = requireNotNull(lists.findBySystemKey("DEFAULT"))
        val other = lists.insert(fixedList("Otra"))

        guardFailure(GUARD) {
            lists.setArchivedAt(initial.id, archivedAt = LATER, updatedAt = LATER)
        }
        guardFailure(GUARD) { lists.update(initial.id, null, null, "RELATIVE", other, 100, LATER) }
        guardFailure(SYSTEM_GUARD) { lists.delete(initial.id) }

        // Renombrarla sí: desde ahí se muestra el nombre de la persona.
        assertEquals(1, lists.update(initial.id, "Público", "publico", "FIXED", null, null, LATER))
        assertEquals("Público", lists.findById(initial.id)?.name)
        assertEquals(1, lists.delete(other))
    }

    @Test
    fun listsFollowCreationOrderAndEmitAgainOnArchiveAndReactivation() = runBlocking {
        val initial = requireNotNull(lists.findBySystemKey("DEFAULT")).id
        val zeta = lists.insert(fixedList("Zona norte"))
        val alfa = lists.insert(fixedList("Amigos"))

        val active = Emissions(this, lists.observeActive().ids { it.id })
        val archived = Emissions(this, lists.observeArchived().ids { it.id })
        try {
            active.awaitValue(listOf(initial, zeta, alfa))
            archived.awaitValue(emptyList())

            lists.setArchivedAt(zeta, archivedAt = LATER, updatedAt = LATER)
            active.awaitValue(listOf(initial, alfa))
            archived.awaitValue(listOf(zeta))

            lists.setArchivedAt(zeta, archivedAt = null, updatedAt = MUCH_LATER)
            active.awaitValue(listOf(initial, zeta, alfa))
            archived.awaitValue(emptyList())
        } finally {
            active.stop()
            archived.stop()
        }
        assertEquals(0, lists.setArchivedAt(MISSING_ID, archivedAt = LATER, updatedAt = LATER))
    }

    @Test
    fun aListInUseIsNotDeleted() = runBlocking {
        val withSales = lists.insert(fixedList("Con ventas"))
        val withPrices = lists.insert(fixedList("Con precios"))
        val asBase = lists.insert(fixedList("Base"))
        val unused = lists.insert(fixedList("Sin uso"))
        database.saleDao().insert(sale(withSales))
        val productId = database.productDao().insert(product("Café"))
        database.productPriceDao().insert(productPrice(withPrices, productId, price = 2_300))
        lists.insert(relativeList("Relativa", asBase, adjustmentBp = 500))

        constraintFailure(FOREIGN_KEY) { lists.delete(withSales) }
        constraintFailure(FOREIGN_KEY) { lists.delete(withPrices) }
        constraintFailure(FOREIGN_KEY) { lists.delete(asBase) }

        assertEquals(1, lists.delete(unused))
        assertNull(lists.findById(unused))
        assertEquals(0, lists.delete(MISSING_ID))
        assertEquals(5L, count("SELECT COUNT(*) FROM price_lists"))
    }

    @Test
    fun indexesAndKeysAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "index_price_lists_base_price_list_id (base_price_list_id)",
                "index_price_lists_normalized_name (normalized_name)",
                "unique index_price_lists_system_key (system_key)",
            ),
            database.indexesOf("price_lists"),
        )
        assertEquals(
            listOf("base_price_list_id -> price_lists RESTRICT"),
            database.foreignKeysOf("price_lists"),
        )
    }

    private companion object {
        const val GUARD = "guard:price_lists"
        const val SYSTEM_GUARD = "guard:price_lists_system"
        const val MISSING_ID = 999L
    }
}
