package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.payable.appendSupplierEntry
import io.github.yesidrobayo.shopledger.data.local.payable.supplierEntry
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.purchaseReturn
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierTotals
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Lo que rodea a cada proveedor: lo comprado, sus compras y sus productos habituales. */
@RunWith(AndroidJUnit4::class)
internal class SupplierActivitySourceTest : RepositoryTest() {
    private val suppliers by lazy { RoomSupplierRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val activity by lazy { RoomSupplierActivitySource(database) }

    @Test
    fun fivePurchasesWithOneVoidedCountFiveAndTotalWithoutTheVoided() = runBlocking {
        val id = commit { suppliers.insert(details("Granja El Alto"), AT) }
        val other = commit { suppliers.insert(details("Lácteos del Valle"), AT) }
        val ids =
            (1..5).map { index ->
                database.purchaseDao().insert(
                    purchase(supplierId = id, occurredAt = CREATED_AT_BASE + index)
                        .copy(
                            netTotal = 10_000L * index,
                            total = 10_000L * index,
                            costTotal = 10_000L * index,
                            amountPaid = 10_000L * index,
                        ),
                )
            }
        database.purchaseDao().insert(purchase(supplierId = other))
        commit { purchases.markVoided(ids[2], Voiding(LATER_AT, VoidReason.ENTRY_ERROR, null)) }

        assertEquals(
            SupplierTotals(5, Money(120_000L), hasHistory = true),
            activity.observeTotals(id).first(),
        )
        val listed = activity.observePurchases(id).first()
        assertEquals(ids.reversed(), listed.map { it.id })
        assertEquals(DocumentStatus.VOIDED, listed.single { it.id == ids[2] }.status)
        assertEquals("F-0001", listed.first().invoiceReference)
    }

    @Test
    fun aSupplierWithoutPurchasesHasNoHistoryUntilAReturnOrALedgerRow() = runBlocking {
        val id = commit { suppliers.insert(details("Granja El Alto"), AT) }
        assertEquals(SupplierTotals.NONE, activity.observeTotals(id).first())

        database.purchaseReturnDao().insert(purchaseReturn().copy(supplierId = id))
        assertEquals(SupplierTotals(0, Money.ZERO, true), activity.observeTotals(id).first())

        val ledgerOnly = commit { suppliers.insert(details("Panadería La Esquina"), AT) }
        database.appendSupplierEntry(supplierEntry(ledgerOnly, "OPENING_BALANCE", 30_000, 30_000))
        assertEquals(true, activity.observeTotals(ledgerOnly).first().hasHistory)
    }

    @Test
    fun usualProductsAreTheActiveOnesByNameAndTheirCountFollowsChanges() = runBlocking {
        val id = commit { suppliers.insert(details("Granja El Alto"), AT) }
        val products = database.productDao()
        val milk = products.insert(product("Leche", defaultSupplierId = id))
        val cheese = products.insert(product("Queso", defaultSupplierId = id))
        products.insert(product("Yogur", defaultSupplierId = id, archivedAt = CREATED_AT_BASE))
        products.insert(product("Pan"))

        assertEquals(
            listOf(SupplierProduct(milk, "Leche"), SupplierProduct(cheese, "Queso")),
            activity.observeUsualProducts(id).first(),
        )
        activity.observeUsualProductCounts().test {
            assertEquals(mapOf(id to 2), awaitItem())

            commit { suppliers.delete(id) }

            awaitValue(emptyMap())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(products.findById(milk)?.defaultSupplierId)
    }

    @Test
    fun theSupplierIsObservedUntilItIsDeleted() = runBlocking {
        val id = commit { suppliers.insert(details("Granja El Alto"), AT) }

        suppliers.observeById(id).test {
            assertEquals("Granja El Alto", awaitItem()?.details?.name)

            commit { suppliers.update(id, details("Granja Alta"), LATER_AT) }
            assertEquals("Granja Alta", awaitItem()?.details?.name)

            commit { suppliers.delete(id) }
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun details(name: String) = SupplierDetails(
        name = name,
        normalizedName = TextNormalizer.normalize(name),
        phone = "555 0101",
        email = null,
        notes = null,
        paymentTermsDays = null,
    )

    private companion object {
        /** Un instante fijo y redondo para las compras de prueba. */
        const val CREATED_AT_BASE = 1_700_000_000_000L
    }
}
