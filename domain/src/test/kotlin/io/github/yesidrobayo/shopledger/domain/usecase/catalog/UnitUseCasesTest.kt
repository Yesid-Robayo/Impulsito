package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeUnitCatalogRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ARCHIVED_UNIT_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las unidades y las conversiones de su pantalla, sobre el catálogo en memoria con las unidades
 * iniciales. Los nombres y los códigos son inventados.
 */
class UnitUseCasesTest {
    private val fixture = CatalogFixture()
    private val catalog = FakeUnitCatalogRepository(fixture.database)
    private val conversions = FakeProductUnitRepository(fixture.database)
    private val tx = fixture.transactions
    private val clock = fixture.clock
    private val saveUnit = SaveUnitUseCase(tx, catalog, clock)
    private val archive = ArchiveUnitUseCase(tx, catalog, clock)
    private val reactivate = ReactivateUnitUseCase(tx, catalog, clock)
    private val deleteUnit = DeleteUnitUseCase(tx, catalog)
    private val observeUnits = ObserveUnitsUseCase(fixture.units, catalog)
    private val saveConversion =
        SaveConversionUseCase(tx, fixture.products, fixture.units, conversions, clock)
    private val deleteConversion = DeleteConversionUseCase(tx, conversions)
    private val observeConversions = ObserveConversionsUseCase(conversions, fixture.products)

    private val changes: Long get() = fixture.tables.changeSeq

    // ---------------------------------------------------------------- unidades

    @Test
    fun `a unit of the person is created with its name, symbol, kind and fractions`() = runTest {
        val result = saveUnit(null, UnitDraft("  Bulto ", "blt", UnitKind.MASS, true))

        val id = (result as SaveUnitResult.Saved).id
        assertEquals(
            MeasureUnit(id, null, "Bulto", "blt", UnitKind.MASS, true, null),
            fixture.tables.units[id],
        )
        assertEquals(1L, changes)
    }

    @Test
    fun `a name already used by an active unit is rejected, without tildes or case`() = runTest {
        saveUnit(null, UnitDraft("Bandeja", "", UnitKind.COUNT, false))

        val repeated = saveUnit(null, UnitDraft("  BANDEJÁ ", "", UnitKind.COUNT, false))

        assertEquals(SaveUnitResult.DuplicateName, repeated)
        assertEquals(1L, changes)
    }

    @Test
    fun `an archived unit does not keep its name`() = runTest {
        // La unidad archivada de la base de prueba se llama Bulto.
        assertEquals("Bulto", fixture.tables.units.getValue(ARCHIVED_UNIT_ID).name)

        val result = saveUnit(null, UnitDraft("bulto", "", UnitKind.OTHER, false))

        assertEquals(SaveUnitResult.Saved(fixture.tables.lastIds.getValue("units")), result)
    }

    @Test
    fun `an initial unit renamed stops following the language, and blank keeps following it`() =
        runTest {
            saveUnit(UNIT_ID, UnitDraft("Pieza", "pz", UnitKind.COUNT, false))
            assertEquals("Pieza", fixture.tables.units.getValue(UNIT_ID).name)

            saveUnit(UNIT_ID, UnitDraft("", "", UnitKind.COUNT, false))

            val unit = fixture.tables.units.getValue(UNIT_ID)
            assertNull(unit.name)
            assertNull(unit.symbol)
            assertEquals(2L, changes)
        }

    @Test
    fun `an edit that changes nothing writes nothing`() = runTest {
        val result = saveUnit(KILOGRAM_ID, UnitDraft("", "", UnitKind.MASS, true))

        assertEquals(SaveUnitResult.Saved(KILOGRAM_ID), result)
        assertEquals(0L, changes)
        assertEquals(0, tx.commits)
    }

    @Test
    fun `invalid, missing or archived units are not saved`() = runTest {
        assertEquals(
            SaveUnitResult.Invalid(setOf(UnitInputError.NAME_EMPTY)),
            saveUnit(null, UnitDraft("", "", UnitKind.COUNT, false)),
        )
        assertEquals(
            SaveUnitResult.NotFound,
            saveUnit(999L, UnitDraft("Caja", "", UnitKind.COUNT, false)),
        )
        assertEquals(
            SaveUnitResult.NotFound,
            saveUnit(ARCHIVED_UNIT_ID, UnitDraft("Caja", "", UnitKind.COUNT, false)),
        )
        assertEquals(0L, changes)
    }

    @Test
    fun `a full disk or a restore in progress saves nothing and says why`() = runTest {
        fixture.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        fixture.database.failAtWrite = 1
        val full = saveUnit(null, UnitDraft("Bulto", "", UnitKind.MASS, true))
        fixture.database.failAtWrite = null
        tx.restoreInProgress = true
        val restoring = archive(KILOGRAM_ID)

        assertEquals(SaveUnitResult.StorageFailed(StorageFailure.DISK_FULL), full)
        assertEquals(
            UnitLifecycleResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            restoring,
        )
        assertEquals(0L, changes)
    }

    @Test
    fun `the counting unit is never archived nor deleted`() = runTest {
        assertEquals(UnitLifecycleResult.Protected, archive(UNIT_ID))
        assertEquals(UnitLifecycleResult.Protected, deleteUnit(UNIT_ID))
        assertEquals(0L, changes)
    }

    @Test
    fun `archiving and reactivating a unit changes only its archive mark`() = runTest {
        assertEquals(UnitLifecycleResult.Done, archive(KILOGRAM_ID))
        assertEquals(clock.now(), fixture.tables.units.getValue(KILOGRAM_ID).archivedAt)
        assertEquals(UnitLifecycleResult.Unchanged, archive(KILOGRAM_ID))

        assertEquals(UnitLifecycleResult.Done, reactivate(KILOGRAM_ID))
        assertNull(fixture.tables.units.getValue(KILOGRAM_ID).archivedAt)
        assertEquals(UnitLifecycleResult.Unchanged, reactivate(KILOGRAM_ID))
        assertEquals(UnitLifecycleResult.NotFound, archive(999L))
        assertEquals(UnitLifecycleResult.NotFound, reactivate(999L))
        assertEquals(2L, changes)
    }

    @Test
    fun `an archived unit whose name was taken is not reactivated`() = runTest {
        saveUnit(null, UnitDraft("bulto", "", UnitKind.MASS, true))
        // Una inicial sin nombre propio no choca con nadie.
        archive(KILOGRAM_ID)

        assertEquals(UnitLifecycleResult.NameTaken, reactivate(ARCHIVED_UNIT_ID))
        assertEquals(UnitLifecycleResult.Done, reactivate(KILOGRAM_ID))
    }

    @Test
    fun `a unit in use is archived, and one without use is deleted`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg", baseUnitId = KILOGRAM_ID)
        val loose = (
            saveUnit(
                null,
                UnitDraft("Bulto", "", UnitKind.MASS, true),
            ) as SaveUnitResult.Saved
            ).id
        val inLines = (
            saveUnit(
                null,
                UnitDraft("Saco", "", UnitKind.MASS, true),
            ) as SaveUnitResult.Saved
            ).id
        fixture.database.seed { it.copy(unitsUsedByLines = setOf(inLines)) }

        assertEquals(UnitLifecycleResult.InUse, deleteUnit(KILOGRAM_ID))
        assertEquals(UnitLifecycleResult.InUse, deleteUnit(inLines))
        assertEquals(UnitLifecycleResult.Done, deleteUnit(loose))
        assertNull(fixture.tables.units[loose])
        assertEquals(UnitLifecycleResult.NotFound, deleteUnit(loose))
    }

    @Test
    fun `the overview splits active and archived units and says which are in use`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg", baseUnitId = KILOGRAM_ID)

        val overview = observeUnits().first()

        assertEquals(listOf(UNIT_ID, KILOGRAM_ID), overview.active.map { it.id })
        assertEquals(listOf(ARCHIVED_UNIT_ID), overview.archived.map { it.id })
        assertEquals(setOf(KILOGRAM_ID), overview.inUse)
    }

    // ---------------------------------------------------------------- conversiones

    @Test
    fun `a box of 24 of a product counted by unit is saved and listed with its product`() =
        runTest {
            fixture.seedProduct(1L, "Arroz 1 kg")

            val result = saveConversion(null, box(1L, factor = 24_000L))

            val id = (result as SaveConversionResult.Saved).id
            assertEquals(
                ProductConversion(
                    id,
                    1L,
                    ConversionDetails(KILOGRAM_ID, 24_000L, null, null, true, false),
                ),
                fixture.tables.productUnits[id],
            )
            val listed = observeConversions().first().single()
            assertEquals(id, listed.conversion.id)
            assertEquals("Arroz 1 kg", listed.product.details.name)
            assertEquals(1L, changes)
        }

    @Test
    fun `a conversion to the base unit or with a zero factor writes nothing`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg", baseUnitId = KILOGRAM_ID)

        val result = saveConversion(null, box(1L, factor = 0L))

        assertEquals(
            SaveConversionResult.Invalid(
                setOf(ConversionInputError.FACTOR_NOT_POSITIVE, ConversionInputError.SAME_AS_BASE),
            ),
            result,
        )
        assertEquals(0L, changes)
    }

    @Test
    fun `a product has one conversion per unit`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg")
        saveConversion(null, box(1L))

        assertEquals(
            SaveConversionResult.UnitTaken,
            saveConversion(null, box(1L, factor = 12_000L)),
        )
        assertEquals(1L, changes)
    }

    @Test
    fun `an archived or missing product, or an archived unit, gets no new conversion`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg", archived = true)
        fixture.seedProduct(2L, "Aceite 900 ml")

        assertEquals(SaveConversionResult.ProductUnavailable, saveConversion(null, box(1L)))
        assertEquals(SaveConversionResult.ProductUnavailable, saveConversion(null, box(99L)))
        assertEquals(
            SaveConversionResult.UnitUnavailable,
            saveConversion(null, box(2L).copy(unitId = ARCHIVED_UNIT_ID)),
        )
        assertEquals(
            SaveConversionResult.UnitUnavailable,
            saveConversion(null, box(2L).copy(unitId = 999L)),
        )
        assertEquals(0L, changes)
    }

    @Test
    fun `a code used by another conversion or another product names its product`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg", barcode = "111")
        fixture.seedProduct(2L, "Aceite 900 ml", barcode = "222")
        saveConversion(null, box(2L, barcode = "333"))

        assertEquals(
            SaveConversionResult.DuplicateBarcode("Aceite 900 ml"),
            saveConversion(null, box(1L, barcode = "333")),
        )
        assertEquals(
            SaveConversionResult.DuplicateBarcode("Aceite 900 ml"),
            saveConversion(null, box(1L, barcode = "222")),
        )
        // El código del propio producto no choca, como en la guarda de la tabla.
        assertEquals(
            SaveConversionResult.Saved(2L),
            saveConversion(null, box(1L, barcode = "111")),
        )
    }

    @Test
    fun `editing keeps the product and the unit, and an unchanged edit writes nothing`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg")
        fixture.seedProduct(2L, "Aceite 900 ml")
        val id = (
            saveConversion(
                null,
                box(1L, barcode = "333"),
            ) as SaveConversionResult.Saved
            ).id

        val same = saveConversion(id, box(1L, barcode = "333"))
        val changed =
            saveConversion(
                id,
                box(2L, factor = 12_000L, barcode = "333").copy(unitId = UNIT_ID),
            )

        assertEquals(SaveConversionResult.Saved(id), same)
        assertEquals(SaveConversionResult.Saved(id), changed)
        val stored = fixture.tables.productUnits.getValue(id)
        assertEquals(1L, stored.productId)
        assertEquals(KILOGRAM_ID, stored.details.unitId)
        assertEquals(12_000L, stored.details.factorMilli)
        assertEquals(2L, changes)
    }

    @Test
    fun `a conversion removed elsewhere is not found, and removing it twice says so`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg")
        val id = (saveConversion(null, box(1L)) as SaveConversionResult.Saved).id

        assertEquals(DeleteConversionResult.Deleted, deleteConversion(id))
        assertEquals(DeleteConversionResult.NotFound, deleteConversion(id))
        assertEquals(SaveConversionResult.NotFound, saveConversion(id, box(1L)))
        assertEquals(2L, changes)
    }

    @Test
    fun `the list shows conversions of active products, by product name`() = runTest {
        fixture.seedProduct(1L, "Zanahoria")
        fixture.seedProduct(2L, "Arroz 1 kg")
        fixture.seedProduct(3L, "Café molido")
        saveConversion(null, box(1L))
        saveConversion(null, box(2L))
        saveConversion(null, box(3L))
        fixture.database.seed { tables ->
            val archived = tables.products.getValue(3L).copy(archivedAt = CatalogFixture.EARLIER)
            tables.copy(products = tables.products + (3L to archived))
        }

        val names = observeConversions().first().map { it.product.details.name }

        assertEquals(listOf("Arroz 1 kg", "Zanahoria"), names)
    }

    @Test
    fun `a failure in any write of a conversion leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = { seedProduct(1L, "Arroz 1 kg") },
        ) {
            val repository = FakeProductUnitRepository(database)
            SaveConversionUseCase(transactions, products, units, repository, clock)(null, box(1L))
        }
    }

    private fun box(productId: Long, factor: Long = 24_000L, barcode: String? = null) =
        ConversionDraft(
            productId = productId,
            unitId = KILOGRAM_ID,
            factorMilli = factor,
            salePrice = null,
            barcode = barcode,
            forSale = true,
            forPurchase = false,
        )
}
