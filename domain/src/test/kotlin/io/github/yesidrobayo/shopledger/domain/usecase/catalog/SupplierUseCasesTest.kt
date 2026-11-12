package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierPurchase
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierTotals
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SupplierActivitySource
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ACTIVE_SUPPLIER_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ARCHIVED_SUPPLIER_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.EARLIER
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.MAIN_LOCATION_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Crear, editar, archivar, reactivar y eliminar proveedores, y leer su lista y su detalle. */
class SupplierUseCasesTest {
    private val fixture = CatalogFixture()
    private val tx = fixture.transactions
    private val suppliers = fixture.suppliers
    private val activity = TablesSupplierActivity(fixture.database)
    private val create = CreateSupplierUseCase(tx, suppliers, fixture.clock)
    private val edit = EditSupplierUseCase(tx, suppliers, fixture.clock)
    private val archive = ArchiveSupplierUseCase(tx, suppliers, fixture.clock)
    private val reactivate = ReactivateSupplierUseCase(tx, suppliers, fixture.clock)
    private val delete = DeleteSupplierUseCase(tx, suppliers)

    @Test
    fun `only a name creates a supplier, with zero balance`() = runTest {
        val result = create(SupplierForm(" Granja El Alto "))

        val id = (result as SaveSupplierResult.Saved).supplierId
        val stored = fixture.tables.suppliers.getValue(id)
        assertEquals(
            SupplierDetails("Granja El Alto", "granja el alto", null, null, null, null),
            stored.details,
        )
        assertEquals(Money.ZERO, stored.balance)
        assertEquals(NOW, stored.createdAt)
        assertEquals(1L, fixture.tables.changeSeq)
    }

    @Test
    fun `an active supplier with that name rejects another, but an archived one does not`() =
        runTest {
            val before = fixture.tables

            assertEquals(
                SaveSupplierResult.DuplicateName("Proveedor Uno"),
                create(SupplierForm("PROVEEDOR UNO")),
            )
            assertEquals(before, fixture.tables)
            // "Proveedor Dos" está archivado: no reserva su nombre.
            assertEquals(SaveSupplierResult.Saved(1L), create(SupplierForm("Proveedor Dós")))
        }

    @Test
    fun `an invalid supplier is rejected with every reason and nothing is written`() = runTest {
        val before = fixture.tables

        assertEquals(
            SaveSupplierResult.Invalid(
                setOf(SupplierFormIssue.NAME_EMPTY, SupplierFormIssue.EMAIL_FORMAT),
            ),
            create(SupplierForm("", email = "compras")),
        )
        assertEquals(
            SaveSupplierResult.Invalid(setOf(SupplierFormIssue.PHONE_FORMAT)),
            edit(ACTIVE_SUPPLIER_ID, SupplierForm("Proveedor Uno", phone = "uno")),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `editing keeps the id and the balance, and saving unchanged writes nothing`() = runTest {
        assertEquals(
            SaveSupplierResult.Saved(ACTIVE_SUPPLIER_ID),
            edit(ACTIVE_SUPPLIER_ID, SupplierForm("Proveedor Uno", phone = "555 0101")),
        )
        val stored = fixture.tables.suppliers.getValue(ACTIVE_SUPPLIER_ID)
        assertEquals("555 0101", stored.details.phone)
        assertEquals(NOW, stored.updatedAt)
        val before = fixture.tables

        assertEquals(
            SaveSupplierResult.Saved(ACTIVE_SUPPLIER_ID),
            edit(ACTIVE_SUPPLIER_ID, SupplierForm("Proveedor Uno", phone = " 555 0101 ")),
        )
        assertEquals(before, fixture.tables)
        assertEquals(SaveSupplierResult.NotFound, edit(99L, SupplierForm("Otro")))
    }

    @Test
    fun `an active supplier cannot take the name of another active one`() = runTest {
        val other = (create(SupplierForm("Lácteos del Valle")) as SaveSupplierResult.Saved)
        val before = fixture.tables

        assertEquals(
            SaveSupplierResult.DuplicateName("Lácteos del Valle"),
            edit(ACTIVE_SUPPLIER_ID, SupplierForm("lacteos del valle")),
        )
        assertEquals(before, fixture.tables)
        // Cambiar solo las tildes de su propio nombre no choca consigo mismo.
        assertEquals(
            SaveSupplierResult.Saved(other.supplierId),
            edit(other.supplierId, SupplierForm("Lacteos del Valle")),
        )
    }

    @Test
    fun `an archived supplier with purchases can be renamed and emptied, keeping its purchases`() =
        runTest {
            seedPurchase(ARCHIVED_SUPPLIER_ID, total = 20_000L)
            edit(ARCHIVED_SUPPLIER_ID, SupplierForm("Proveedor Dos", phone = "555", notes = "x"))

            assertEquals(
                SaveSupplierResult.Saved(ARCHIVED_SUPPLIER_ID),
                edit(ARCHIVED_SUPPLIER_ID, SupplierForm("Proveedor Uno")),
            )

            val stored = fixture.tables.suppliers.getValue(ARCHIVED_SUPPLIER_ID)
            assertEquals("Proveedor Uno", stored.details.name)
            assertNull(stored.details.phone)
            assertNull(stored.details.notes)
            assertEquals(
                ARCHIVED_SUPPLIER_ID,
                fixture.tables.purchases.values.single().content.supplierId,
            )
        }

    @Test
    fun `archiving hides it from new purchases and its history stays`() = runTest {
        seedPurchase(ACTIVE_SUPPLIER_ID, total = 20_000L)

        assertEquals(SupplierLifecycleResult.Done, archive(ACTIVE_SUPPLIER_ID))

        assertEquals(NOW, fixture.tables.suppliers.getValue(ACTIVE_SUPPLIER_ID).archivedAt)
        assertNull(suppliers.findActiveByNormalizedName("proveedor uno"))
        assertEquals(1, activity.observeTotals(ACTIVE_SUPPLIER_ID).first().purchasesCount)
        val before = fixture.tables
        assertEquals(SupplierLifecycleResult.Unchanged, archive(ACTIVE_SUPPLIER_ID))
        assertEquals(SupplierLifecycleResult.NotFound, archive(99L))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `reactivating is rejected while another active supplier uses its name`() = runTest {
        create(SupplierForm("Proveedor Dos"))
        val before = fixture.tables

        assertEquals(
            SupplierLifecycleResult.NameTaken("Proveedor Dos"),
            reactivate(ARCHIVED_SUPPLIER_ID),
        )
        assertEquals(before, fixture.tables)

        edit(ARCHIVED_SUPPLIER_ID, SupplierForm("Proveedor Dos Antiguo"))
        assertEquals(SupplierLifecycleResult.Done, reactivate(ARCHIVED_SUPPLIER_ID))
        assertNull(fixture.tables.suppliers.getValue(ARCHIVED_SUPPLIER_ID).archivedAt)
        assertEquals(SupplierLifecycleResult.Unchanged, reactivate(ACTIVE_SUPPLIER_ID))
        assertEquals(SupplierLifecycleResult.NotFound, reactivate(99L))
    }

    @Test
    fun `deleting a supplier without purchases leaves its 2 products without supplier`() = runTest {
        fixture.seedProduct(1L, "Leche", defaultSupplierId = ACTIVE_SUPPLIER_ID)
        fixture.seedProduct(2L, "Queso", defaultSupplierId = ACTIVE_SUPPLIER_ID)
        fixture.seedProduct(3L, "Pan", defaultSupplierId = ARCHIVED_SUPPLIER_ID)

        assertEquals(SupplierLifecycleResult.Done, delete(ACTIVE_SUPPLIER_ID))

        assertNull(fixture.tables.suppliers[ACTIVE_SUPPLIER_ID])
        assertNull(fixture.product(1L).details.defaultSupplierId)
        assertNull(fixture.product(2L).details.defaultSupplierId)
        assertEquals(ARCHIVED_SUPPLIER_ID, fixture.product(3L).details.defaultSupplierId)
        assertEquals(SupplierLifecycleResult.NotFound, delete(ACTIVE_SUPPLIER_ID))
    }

    @Test
    fun `a supplier with purchases, even voided, is only archived`() = runTest {
        seedPurchase(ACTIVE_SUPPLIER_ID, total = 20_000L, voided = true)
        val before = fixture.tables

        assertEquals(SupplierLifecycleResult.HasHistory, delete(ACTIVE_SUPPLIER_ID))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a storage failure is a result, and nothing is kept`() = runTest {
        tx.restoreInProgress = true

        val failed = SaveSupplierResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS)
        assertEquals(failed, create(SupplierForm("Nuevo")))
        assertEquals(failed, edit(ACTIVE_SUPPLIER_ID, SupplierForm("Nuevo")))
        val lifecycleFailed =
            SupplierLifecycleResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS)
        assertEquals(lifecycleFailed, archive(ACTIVE_SUPPLIER_ID))
        assertEquals(lifecycleFailed, reactivate(ARCHIVED_SUPPLIER_ID))
        assertEquals(lifecycleFailed, delete(ACTIVE_SUPPLIER_ID))
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing({}) {
            CreateSupplierUseCase(transactions, suppliers, clock)(SupplierForm("Nuevo"))
        }
        CatalogFixture.assertEachWriteFailureLeavesNothing({
            seedProduct(1L, "Leche", defaultSupplierId = ACTIVE_SUPPLIER_ID)
        }) {
            DeleteSupplierUseCase(transactions, suppliers)(ACTIVE_SUPPLIER_ID)
        }
    }

    @Test
    fun `the list counts the active products of each supplier, active and archived apart`() =
        runTest {
            fixture.seedProduct(1L, "Leche", defaultSupplierId = ACTIVE_SUPPLIER_ID)
            fixture.seedProduct(2L, "Queso", defaultSupplierId = ACTIVE_SUPPLIER_ID)
            fixture.seedProduct(
                3L,
                "Yogur",
                archived = true,
                defaultSupplierId = ACTIVE_SUPPLIER_ID,
            )

            val overview = ObserveSuppliersUseCase(suppliers, activity)().first()

            assertEquals(
                listOf(ACTIVE_SUPPLIER_ID to 2),
                overview.active.map { it.supplier.id to it.usualProducts },
            )
            assertEquals(
                listOf(ARCHIVED_SUPPLIER_ID to 0),
                overview.archived.map { it.supplier.id to it.usualProducts },
            )
        }

    @Test
    fun `5 purchases with 1 voided show the 5, and the total leaves the voided one out`() =
        runTest {
            fixture.seedProduct(1L, "Leche", defaultSupplierId = ACTIVE_SUPPLIER_ID)
            for (index in 1..5) {
                seedPurchase(ACTIVE_SUPPLIER_ID, total = 10_000L * index, voided = index == 3)
            }
            seedPurchase(ARCHIVED_SUPPLIER_ID, total = 99_000L)

            val overview =
                requireNotNull(
                    ObserveSupplierOverviewUseCase(suppliers, activity)(ACTIVE_SUPPLIER_ID).first(),
                )

            assertEquals(SupplierTotals(5, Money(120_000L), hasHistory = true), overview.totals)
            assertEquals(listOf(5L, 4L, 3L, 2L, 1L), overview.purchases.map { it.id })
            assertEquals(DocumentStatus.VOIDED, overview.purchases.single { it.id == 3L }.status)
            assertEquals(listOf(SupplierProduct(1L, "Leche")), overview.usualProducts)
        }

    @Test
    fun `a deleted supplier's detail has nothing to show`() = runTest {
        val detail = ObserveSupplierOverviewUseCase(suppliers, activity)(ACTIVE_SUPPLIER_ID)
        assertEquals(SupplierTotals.NONE, detail.first()?.totals)

        delete(ACTIVE_SUPPLIER_ID)

        assertNull(detail.first())
    }

    private var purchaseIds = 0L

    private fun seedPurchase(supplierId: Long, total: Long, voided: Boolean = false) {
        val id = ++purchaseIds
        val at = EARLIER + id.hours
        val content =
            NewPurchase(
                supplierId = supplierId,
                invoiceReference = null,
                netTotal = Money(total),
                taxTotal = Money.ZERO,
                total = Money(total),
                costTotal = Money(total),
                amountPaid = Money(total),
                moneyAccountId = null,
                dueDate = null,
                locationId = MAIN_LOCATION_ID,
                foreignCurrency = null,
                occurredAt = at,
                businessDate = requireNotNull(BusinessDate.parse("2023-11-14")),
                createdAt = at,
            )
        val voiding = if (voided) Voiding(NOW, VoidReason.ENTRY_ERROR, null) else null
        fixture.database.seed {
            it.copy(
                purchases =
                    it.purchases + (id to Purchase(id, content, voiding)),
            )
        }
    }
}

/** Lo que rodea a cada proveedor, leído de las tablas en memoria como lo leería la base. */
internal class TablesSupplierActivity(private val database: InMemoryDatabase) :
    SupplierActivitySource {
    override fun observeTotals(supplierId: Long): Flow<SupplierTotals> =
        database.tables.map { tables ->
            val purchases = tables.purchases.values.filter { it.content.supplierId == supplierId }
            SupplierTotals(
                purchasesCount = purchases.size,
                activeTotal = Money(
                    purchases.filter { it.status == DocumentStatus.ACTIVE }
                        .sumOf { it.content.total.minor },
                ),
                hasHistory = purchases.isNotEmpty(),
            )
        }

    override fun observePurchases(supplierId: Long): Flow<List<SupplierPurchase>> =
        database.tables.map { tables ->
            tables.purchases.values
                .filter { it.content.supplierId == supplierId }
                .sortedWith(
                    compareByDescending<Purchase> {
                        it.content.occurredAt
                    }.thenByDescending { it.id },
                )
                .map {
                    SupplierPurchase(
                        id = it.id,
                        status = it.status,
                        total = it.content.total,
                        invoiceReference = it.content.invoiceReference,
                        occurredAt = it.content.occurredAt,
                        businessDate = it.content.businessDate,
                    )
                }
        }

    override fun observeUsualProducts(supplierId: Long): Flow<List<SupplierProduct>> =
        database.tables.map { tables ->
            tables.products.values
                .filter { it.details.defaultSupplierId == supplierId && !it.isArchived }
                .sortedBy { it.details.normalizedName }
                .map { SupplierProduct(it.id, it.details.name) }
        }

    override fun observeUsualProductCounts(): Flow<Map<Long, Int>> = database.tables.map { tables ->
        tables.products.values
            .filter { !it.isArchived }
            .mapNotNull { it.details.defaultSupplierId }
            .groupingBy { it }
            .eachCount()
    }
}
