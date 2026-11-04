package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los proveedores: un archivado no reserva su nombre, las listas separan vigentes de archivados,
 * editar nunca toca el saldo, y eliminar deja sin proveedor habitual a sus productos.
 */
@RunWith(AndroidJUnit4::class)
internal class SupplierDaoTest : CatalogDatabaseTest() {
    private val suppliers get() = database.supplierDao()
    private val products get() = database.productDao()

    @Test
    fun insertedSupplierReadsBackWithEveryColumn() = runBlocking {
        val complete =
            SupplierEntity(
                name = "Distribuidora Andina",
                normalizedName = "distribuidora andina",
                phone = "+00 555 0100",
                email = "pedidos@example.com",
                notes = "Entrega los martes",
                balance = 0,
                paymentTermsDays = 30,
                archivedAt = null,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            )
        val minimal = supplier("Mercado Central")

        val completeId = suppliers.insert(complete)
        val minimalId = suppliers.insert(minimal)

        assertEquals(complete.copy(id = completeId), suppliers.findById(completeId))
        assertEquals(minimal.copy(id = minimalId), suppliers.findById(minimalId))
        assertNull(suppliers.findById(MISSING_ID))
    }

    @Test
    fun archivedSupplierDoesNotReserveItsName() = runBlocking {
        val archivedId = suppliers.insert(supplier("Lácteos del Valle", archivedAt = LATER))
        // El índice no es único: la unicidad entre vigentes es de la guarda, y un archivado no
        // la cuenta.
        val activeId = suppliers.insert(supplier("LACTEOS DEL VALLE"))

        assertEquals(activeId, suppliers.findActiveByNormalizedName("lacteos del valle")?.id)
        assertEquals("lacteos del valle", suppliers.findById(archivedId)?.normalizedName)

        suppliers.setArchivedAt(activeId, archivedAt = MUCH_LATER, updatedAt = MUCH_LATER)
        assertNull(suppliers.findActiveByNormalizedName("lacteos del valle"))
    }

    @Test
    fun activeAndArchivedListsAreSeparateAndOrdered() = runBlocking {
        val zeta = suppliers.insert(supplier("Zeta Granos"))
        val alpha = suppliers.insert(supplier("Álamo Frutas"))
        val oldAlpha = suppliers.insert(supplier("Alamo Frutas", archivedAt = LATER))
        val beta = suppliers.insert(supplier("Beta Aseo", archivedAt = LATER))
        val olderAlpha = suppliers.insert(supplier("ALAMO frutas", archivedAt = LATER))

        val active = suppliers.observeActive().first()
        val archived = suppliers.observeArchived().first()

        assertEquals(listOf(alpha, zeta), active.map { it.id })
        // Mismo nombre normalizado: desempata el orden de registro.
        assertEquals(listOf(oldAlpha, olderAlpha, beta), archived.map { it.id })
    }

    @Test
    fun archivingMovesTheSupplierBetweenListsAndReactivatingBringsItBack() = runBlocking {
        val active = Emissions(this, suppliers.observeActive())
        val archived = Emissions(this, suppliers.observeArchived())
        try {
            active.awaitValue(emptyList())
            archived.awaitValue(emptyList())
            val id = suppliers.insert(supplier("Papelería Sol"))
            val created = supplier("Papelería Sol").copy(id = id)
            active.awaitValue(listOf(created))

            assertEquals(1, suppliers.setArchivedAt(id, archivedAt = LATER, updatedAt = LATER))
            val archivedRow = created.copy(archivedAt = LATER, updatedAt = LATER)
            active.awaitValue(emptyList())
            archived.awaitValue(listOf(archivedRow))

            suppliers.setArchivedAt(id, archivedAt = null, updatedAt = MUCH_LATER)
            active.awaitValue(listOf(created.copy(updatedAt = MUCH_LATER)))
            archived.awaitValue(emptyList())
        } finally {
            active.stop()
            archived.stop()
        }
    }

    @Test
    fun editingTheSupplierNeverTouchesItsBalance() = runBlocking {
        val id = suppliers.insert(supplier("Ferretería Norte"))
        // El saldo lo mueve su libro de cuenta, fuera de este DAO.
        execute("UPDATE suppliers SET balance = 154000 WHERE id = $id")

        val changed =
            suppliers.update(
                id = id,
                name = "Ferretería del Norte",
                normalizedName = "ferreteria del norte",
                phone = "+00 555 0199",
                email = "ventas@example.com",
                notes = "Pide con dos días",
                paymentTermsDays = 15,
                updatedAt = LATER,
            )

        assertEquals(1, changed)
        val expected =
            SupplierEntity(
                id = id,
                name = "Ferretería del Norte",
                normalizedName = "ferreteria del norte",
                phone = "+00 555 0199",
                email = "ventas@example.com",
                notes = "Pide con dos días",
                balance = 154_000,
                paymentTermsDays = 15,
                archivedAt = null,
                createdAt = CREATED_AT,
                updatedAt = LATER,
            )
        assertEquals(expected, suppliers.findById(id))
    }

    @Test
    fun deletingASupplierLeavesItsProductsWithoutDefaultSupplier() = runBlocking {
        val gone = suppliers.insert(supplier("Granos Sur"))
        val kept = suppliers.insert(supplier("Granos Este"))
        val rice = products.insert(product("Arroz", defaultSupplierId = gone))
        val beans = products.insert(product("Fríjol", defaultSupplierId = kept))

        assertEquals(1, suppliers.delete(gone))

        assertNull(suppliers.findById(gone))
        assertNull(products.findById(rice)?.defaultSupplierId)
        assertEquals(kept, products.findById(beans)?.defaultSupplierId)
        assertEquals(0, suppliers.delete(gone))
    }

    @Test
    fun productCannotPointToAMissingSupplier() = runBlocking {
        constraintFailure(FOREIGN_KEY) {
            products.insert(product("Huérfano", defaultSupplierId = MISSING_ID))
        }

        assertEquals(0L, count("SELECT COUNT(*) FROM products"))
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
