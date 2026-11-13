package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListInputError
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListsOverview
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.DEFAULT_PRICE_LIST_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Crear, editar, archivar, reactivar y eliminar listas de precios. */
class PriceListUseCasesTest {
    @Test
    fun `a new base already has the initial list`() = runTest {
        val fixture = PriceListFixture()

        val overview = fixture.observe().first()

        assertEquals(listOf(DEFAULT_PRICE_LIST_ID), overview.active.map { it.id })
        assertEquals(PriceListPricing.Fixed, overview.active.single().pricing)
    }

    @Test
    fun `creating a fixed and a relative list counts one change each`() = runTest {
        val fixture = PriceListFixture()

        val fixed = fixture.save(null, fixed("Mayorista")) as SavePriceListResult.Saved
        val relative = fixture.save(null, relative("Especial", fixed.id, -1_500L))

        val relativeId = (relative as SavePriceListResult.Saved).id
        assertEquals("Mayorista", fixture.list(fixed.id).name)
        assertEquals(
            PriceListPricing.Relative(fixed.id, BasisPoints(-1_500L)),
            fixture.list(relativeId).pricing,
        )
        assertEquals(2L, fixture.tables.changeSeq)
    }

    @Test
    fun `a base that would come back to the list is refused and writes nothing`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista")
        fixture.seedRelative(41L, "Especial", baseId = 40L, bp = -1_500L)
        fixture.seedRelative(42L, "Otra", baseId = 41L, bp = 500L)
        val before = fixture.tables

        // 40 pasaría a partir de 42, que parte de 41, que parte de 40.
        assertEquals(
            SavePriceListResult.CreatesCycle,
            fixture.save(40L, relative("Mayorista", 42L, 0L)),
        )
        // Y una lista no parte de sí misma.
        assertEquals(
            SavePriceListResult.CreatesCycle,
            fixture.save(41L, relative("Especial", 41L, 0L)),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `editing to another base of the chain that does not loop is saved`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista")
        fixture.seedRelative(41L, "Especial", baseId = 40L, bp = -1_500L)
        fixture.seedRelative(42L, "Otra", baseId = 41L, bp = 500L)

        assertEquals(
            SavePriceListResult.Saved(42L),
            fixture.save(42L, relative("Otra", 40L, 700L)),
        )
        assertEquals(PriceListPricing.Relative(40L, BasisPoints(700L)), fixture.list(42L).pricing)
    }

    @Test
    fun `each reason to refuse a save writes nothing`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista")
        fixture.seedFixed(43L, "Vieja", archived = true)
        fixture.seedOwnPrice(40L, 1L, Money(900L))
        fixture.seedFixed(44L, "Sin precios")
        val before = fixture.tables

        assertEquals(
            SavePriceListResult.Invalid(setOf(PriceListInputError.NAME_EMPTY)),
            fixture.save(null, fixed(" ")),
        )
        assertEquals(SavePriceListResult.DuplicateName, fixture.save(null, fixed("MAYORÍSTA")))
        assertEquals(SavePriceListResult.NotFound, fixture.save(99L, fixed("Nada")))
        assertEquals(SavePriceListResult.NotFound, fixture.save(43L, fixed("Vieja")))
        assertEquals(
            SavePriceListResult.BaseUnavailable,
            fixture.save(null, relative("Especial", 43L, 0L)),
        )
        assertEquals(
            SavePriceListResult.BaseUnavailable,
            fixture.save(null, relative("Especial", 99L, 0L)),
        )
        assertEquals(
            SavePriceListResult.HasOwnPrices,
            fixture.save(40L, relative("Mayorista", DEFAULT_PRICE_LIST_ID, 0L)),
        )
        assertEquals(
            SavePriceListResult.Protected,
            fixture.save(DEFAULT_PRICE_LIST_ID, relative("Público", 44L, 0L)),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `the same list saved again writes nothing, and a new name is a change`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista")
        val before = fixture.tables

        assertEquals(SavePriceListResult.Saved(40L), fixture.save(40L, fixed(" Mayorista ")))
        assertEquals(before, fixture.tables)

        assertEquals(SavePriceListResult.Saved(40L), fixture.save(40L, fixed("Mayorista 2")))
        assertEquals("Mayorista 2", fixture.list(40L).name)
        // Cambiar mayúsculas del propio nombre no choca consigo mismo.
        assertEquals(SavePriceListResult.Saved(40L), fixture.save(40L, fixed("MAYORISTA 2")))
    }

    @Test
    fun `the initial list keeps its translated name and stays fixed`() = runTest {
        val fixture = PriceListFixture()
        val before = fixture.tables

        assertEquals(
            SavePriceListResult.Protected,
            fixture.save(DEFAULT_PRICE_LIST_ID, fixed("Público")),
        )
        assertNull(fixture.list(DEFAULT_PRICE_LIST_ID).name)
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a relative list keeps a base archived later, but a new one cannot take it`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista", archived = true)
        fixture.seedRelative(41L, "Especial", baseId = 40L, bp = -1_500L)
        fixture.seedRelative(42L, "Otra", baseId = DEFAULT_PRICE_LIST_ID, bp = 0L)

        assertEquals(
            SavePriceListResult.Saved(41L),
            fixture.save(41L, relative("Especial", 40L, -1_000L)),
        )
        assertEquals(
            SavePriceListResult.BaseUnavailable,
            fixture.save(42L, relative("Otra", 40L, 0L)),
        )
    }

    @Test
    fun `a relative list without prices of its own can become fixed and back`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedRelative(41L, "Especial", baseId = DEFAULT_PRICE_LIST_ID, bp = -1_500L)

        assertEquals(SavePriceListResult.Saved(41L), fixture.save(41L, fixed("Especial")))
        assertEquals(PriceListPricing.Fixed, fixture.list(41L).pricing)
        assertEquals(
            SavePriceListResult.Saved(41L),
            fixture.save(41L, relative("Especial", DEFAULT_PRICE_LIST_ID, 100L)),
        )
    }

    @Test
    fun `a cycle found by the write itself is refused`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista")
        fixture.seedRelative(41L, "Especial", baseId = 40L, bp = 0L)
        // Una lectura vieja que no ve la cadena: la escritura la vuelve a mirar.
        val blind = object : PriceListRepository by fixture.priceLists {
            override suspend fun wouldCreateCycle(id: Long, baseId: Long): Boolean = false
        }
        val save = SavePriceListUseCase(
            fixture.catalog.transactions,
            blind,
            fixture.priceLists,
            fixture.catalog.clock,
        )
        val before = fixture.tables

        assertEquals(SavePriceListResult.CreatesCycle, save(40L, relative("Mayorista", 41L, 0L)))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `archive, reactivate and delete follow the list's state and use`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista")
        fixture.seedRelative(41L, "Especial", baseId = 40L, bp = 0L)
        fixture.seedFixed(42L, "Sobrante")

        assertEquals(PriceListLifecycleResult.Done, fixture.archive(40L))
        assertEquals(PriceListLifecycleResult.Unchanged, fixture.archive(40L))
        assertEquals(PriceListLifecycleResult.Done, fixture.reactivate(40L))
        assertEquals(PriceListLifecycleResult.Unchanged, fixture.reactivate(40L))
        // 40 es la base de 41: tiene uso.
        assertEquals(PriceListLifecycleResult.InUse, fixture.delete(40L))
        assertEquals(PriceListLifecycleResult.Done, fixture.delete(42L))
        assertNull(fixture.tables.priceLists[42L])
        assertEquals(
            PriceListsOverview(
                active = listOf(
                    fixture.list(DEFAULT_PRICE_LIST_ID),
                    fixture.list(40L),
                    fixture.list(41L),
                ),
                archived = emptyList(),
                inUse = setOf(40L),
            ),
            fixture.observe().first(),
        )
    }

    @Test
    fun `the initial list is never archived nor deleted, and missing lists are results`() =
        runTest {
            val fixture = PriceListFixture()
            val before = fixture.tables

            assertEquals(PriceListLifecycleResult.Protected, fixture.archive(DEFAULT_PRICE_LIST_ID))
            assertEquals(PriceListLifecycleResult.Protected, fixture.delete(DEFAULT_PRICE_LIST_ID))
            assertEquals(PriceListLifecycleResult.NotFound, fixture.archive(99L))
            assertEquals(PriceListLifecycleResult.NotFound, fixture.reactivate(99L))
            assertEquals(PriceListLifecycleResult.NotFound, fixture.delete(99L))
            assertEquals(before, fixture.tables)
        }

    @Test
    fun `an archived list whose name was taken is not reactivated`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista", archived = true)
        fixture.seedFixed(41L, "mayorista")

        assertEquals(PriceListLifecycleResult.NameTaken, fixture.reactivate(40L))
    }

    @Test
    fun `a restore in progress is a result for every write`() = runTest {
        val fixture = PriceListFixture()
        fixture.seedFixed(40L, "Mayorista")
        fixture.catalog.transactions.restoreInProgress = true
        val failed = StorageFailure.RESTORE_IN_PROGRESS

        assertEquals(SavePriceListResult.StorageFailed(failed), fixture.save(null, fixed("Otra")))
        assertEquals(PriceListLifecycleResult.StorageFailed(failed), fixture.archive(40L))
        assertEquals(PriceListLifecycleResult.StorageFailed(failed), fixture.reactivate(40L))
        assertEquals(PriceListLifecycleResult.StorageFailed(failed), fixture.delete(40L))
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        PriceListFixture.assertEachWriteFailureLeavesNothing({}) {
            save(null, fixed("Mayorista"))
        }
        PriceListFixture.assertEachWriteFailureLeavesNothing({ seedFixed(40L, "Mayorista") }) {
            save(40L, relative("Mayorista", DEFAULT_PRICE_LIST_ID, -1_500L))
        }
        PriceListFixture.assertEachWriteFailureLeavesNothing({ seedFixed(40L, "Mayorista") }) {
            archive(40L)
        }
        PriceListFixture.assertEachWriteFailureLeavesNothing({
            seedFixed(40L, "Mayorista", archived = true)
        }) { reactivate(40L) }
        PriceListFixture.assertEachWriteFailureLeavesNothing({ seedFixed(40L, "Mayorista") }) {
            delete(40L)
        }
    }

    private fun fixed(name: String) = PriceListDraft(name, PriceListKind.FIXED, null, null)

    private fun relative(name: String, base: Long, bp: Long) =
        PriceListDraft(name, PriceListKind.RELATIVE, base, BasisPoints(bp))
}
