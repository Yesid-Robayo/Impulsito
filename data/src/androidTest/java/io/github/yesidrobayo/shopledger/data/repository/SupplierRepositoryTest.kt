package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.payable.appendSupplierEntry
import io.github.yesidrobayo.shopledger.data.local.payable.supplierEntry
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.purchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Los proveedores: lo que se guarda, cuándo emiten y qué cuenta como su historia. */
@RunWith(AndroidJUnit4::class)
internal class SupplierRepositoryTest : RepositoryTest() {
    private val suppliers by lazy { RoomSupplierRepository(database) }

    @Test
    fun aSupplierComesBackWithZeroBalanceAndEmitsEachChange() = runBlocking {
        suppliers.observe(ArchiveFilter.ACTIVE).test {
            assertEquals(emptyList<Supplier>(), awaitItem())

            val id = commit { suppliers.insert(details("Distribuidora Andina", days = 30), AT) }
            val created =
                Supplier(id, details("Distribuidora Andina", days = 30), Money.ZERO, null, AT, AT)
            assertEquals(listOf(created), awaitItem())
            assertEquals(created, suppliers.findById(id))
            assertEquals(created, suppliers.findActiveByNormalizedName("distribuidora andina"))

            commit { suppliers.update(id, details("Distribuidora del Sur", days = null), LATER_AT) }
            assertEquals(listOf("Distribuidora del Sur"), awaitItem().map { it.details.name })

            commit { suppliers.setArchivedAt(id, LATER_AT, LATER_AT) }
            assertEquals(emptyList<Supplier>(), awaitItem())
            assertEquals(
                listOf(LATER_AT),
                suppliers.observe(ArchiveFilter.ARCHIVED).first().map {
                    it.archivedAt
                },
            )
            assertNull(suppliers.findActiveByNormalizedName("distribuidora del sur"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun purchasesReturnsAndItsLedgerCountAsHistory() = runBlocking {
        val cases: Map<String, suspend (Long) -> Unit> =
            mapOf(
                "compra" to { id -> database.purchaseDao().insert(purchase(supplierId = id)) },
                "devolución" to
                    { id ->
                        database.purchaseReturnDao().insert(purchaseReturn().copy(supplierId = id))
                    },
                "libro" to
                    { id ->
                        database.appendSupplierEntry(
                            supplierEntry(id, "OPENING_BALANCE", 30_000, 30_000),
                        )
                    },
            )
        for ((case, addHistory) in cases) {
            val id = commit { suppliers.insert(details("Proveedor de $case"), AT) }
            assertFalse(case, suppliers.hasHistory(id))

            addHistory(id)

            assertTrue(case, suppliers.hasHistory(id))
            assertEquals(
                case,
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit { suppliers.delete(id) }
                },
            )
        }
    }

    @Test
    fun deletingWithoutHistoryLeavesItsProductsWithoutSupplier() = runBlocking {
        val id = commit { suppliers.insert(details("Granja El Alto"), AT) }
        val milk = database.productDao().insert(product("Leche", defaultSupplierId = id))

        commit { suppliers.delete(id) }

        assertNull(suppliers.findById(id))
        assertNull(database.productDao().findById(milk)?.defaultSupplierId)
        for (write in listOf<suspend () -> Unit>({
            suppliers.delete(id)
        }, { suppliers.update(id, details("X"), AT) }, { suppliers.setArchivedAt(id, AT, AT) })) {
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit { write() }
                },
            )
        }
    }

    private fun details(name: String, days: Int? = null) = SupplierDetails(
        name = name,
        normalizedName = TextNormalizer.normalize(name),
        phone = null,
        email = "compras@example.com",
        notes = "Entrega los martes",
        paymentTermsDays = days,
    )
}
