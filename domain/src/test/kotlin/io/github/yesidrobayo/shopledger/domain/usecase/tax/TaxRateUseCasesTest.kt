package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDraft
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateEntry
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateInputError
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeTaxRateRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los nombres y porcentajes son inventados: ninguna prueba nombra un impuesto de un país. */
class TaxRateUseCasesTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val rates = FakeTaxRateRepository(database)
    private val taxSettings = FakeTaxSettingsRepository(database)
    private val clock = FixedClock.atEpochMillis(1_000L)
    private val create = CreateTaxRateUseCase(transactions, rates, taxSettings, clock)
    private val edit = EditTaxRateUseCase(transactions, rates, taxSettings, clock)
    private val archive = ArchiveTaxRateUseCase(transactions, rates, taxSettings, clock)
    private val reactivate = ReactivateTaxRateUseCase(transactions, rates, clock)
    private val delete = DeleteTaxRateUseCase(transactions, rates, taxSettings)
    private val change = ChangeTaxSettingsUseCase(transactions, taxSettings, clock)
    private val observe = ObserveTaxSetupUseCase(rates, taxSettings)

    @Test
    fun `a new business has no rates and does not charge tax`() = runTest {
        val setup = observe().first()
        assertEquals(
            TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
            setup.settings,
        )
        assertEquals(emptyList<TaxRateEntry>(), setup.active)
        assertEquals(emptyList<TaxRateEntry>(), setup.archived)
    }

    @Test
    fun `creating a rate stores its basis points and counts one change`() = runTest {
        val result = create(draft("Tasa A", 2_100L))

        assertEquals(SaveTaxRateResult.Saved(1L), result)
        val stored = requireNotNull(rates.findById(1L))
        assertEquals(TaxRateDetails("Tasa A", "tasa a", BasisPoints(2_100L)), stored.details)
        assertEquals(1, transactions.commits)
        assertEquals(1L, database.tables.value.changeSeq)
        assertNull(taxSettings.get().defaultTaxRateId)
    }

    @Test
    fun `a rate created as default becomes the default in the same transaction`() = runTest {
        create(draft("Tasa A", 2_100L, default = true))

        assertEquals(1L, taxSettings.get().defaultTaxRateId)
        assertEquals(1, transactions.writeCalls)
        val entry = observe().first().active.single()
        assertTrue(entry.isDefault)
        assertTrue(entry.isInUse)
    }

    @Test
    fun `a repeated name or an invalid draft writes nothing`() = runTest {
        create(draft("Tasa A", 2_100L))
        val repeated = create(draft("  TASA á  ", 500L))
        val invalid = create(draft("", 20_000L))

        assertEquals(SaveTaxRateResult.Invalid(setOf(TaxRateInputError.NAME_TAKEN)), repeated)
        assertEquals(
            SaveTaxRateResult.Invalid(
                setOf(TaxRateInputError.NAME_BLANK, TaxRateInputError.RATE_OUT_OF_RANGE),
            ),
            invalid,
        )
        assertEquals(1, database.tables.value.taxRates.size)
        assertEquals(1L, database.tables.value.changeSeq)
    }

    @Test
    fun `editing changes the rate and can take or drop the default mark`() = runTest {
        create(draft("Tasa A", 2_100L, default = true))
        create(draft("Tasa B", 500L))

        assertEquals(SaveTaxRateResult.Saved(2L), edit(2L, draft("Tasa B", 1_050L, default = true)))
        assertEquals(BasisPoints(1_050L), requireNotNull(rates.findById(2L)).details.rate)
        assertEquals(2L, taxSettings.get().defaultTaxRateId)

        // Quitar la marca deja al negocio sin tasa por defecto.
        assertEquals(SaveTaxRateResult.Saved(2L), edit(2L, draft("Tasa B", 1_050L)))
        assertNull(taxSettings.get().defaultTaxRateId)

        // Editar otra tasa sin marcarla no toca la tasa por defecto de nadie.
        edit(1L, draft("Tasa A", 2_100L, default = true))
        edit(2L, draft("Tasa B renombrada", 1_050L))
        assertEquals(1L, taxSettings.get().defaultTaxRateId)
    }

    @Test
    fun `editing with nothing changed writes nothing and counts no change`() = runTest {
        create(draft("Tasa A", 2_100L, default = true))
        val before = database.tables.value.changeSeq

        assertEquals(
            SaveTaxRateResult.Saved(1L),
            edit(1L, draft(" Tasa A ", 2_100L, default = true)),
        )
        assertEquals(before, database.tables.value.changeSeq)
        assertEquals(1, taxSettings.updates)
    }

    @Test
    fun `only the default mark changes without rewriting the rate`() = runTest {
        create(draft("Tasa A", 2_100L))
        val updatedAt = requireNotNull(rates.findById(1L)).updatedAt

        edit(1L, draft("Tasa A", 2_100L, default = true))

        assertEquals(1L, taxSettings.get().defaultTaxRateId)
        assertEquals(updatedAt, requireNotNull(rates.findById(1L)).updatedAt)
    }

    @Test
    fun `editing keeps its own name but not the name of another active rate`() = runTest {
        create(draft("Tasa A", 2_100L))
        create(draft("Tasa B", 500L))

        assertEquals(SaveTaxRateResult.Saved(1L), edit(1L, draft("tasa a", 2_000L)))
        assertEquals(
            SaveTaxRateResult.Invalid(setOf(TaxRateInputError.NAME_TAKEN)),
            edit(1L, draft("Tasa B", 2_000L)),
        )
        assertEquals(
            SaveTaxRateResult.Invalid(setOf(TaxRateInputError.RATE_OUT_OF_RANGE)),
            edit(1L, draft("Tasa A", -5L)),
        )
    }

    @Test
    fun `editing a missing or archived rate is refused`() = runTest {
        assertEquals(SaveTaxRateResult.NotFound, edit(9L, draft("Tasa", 100L)))
        create(draft("Tasa A", 2_100L))
        archive(1L)
        assertEquals(SaveTaxRateResult.Archived, edit(1L, draft("Tasa A", 100L)))
    }

    @Test
    fun `archiving the default rate leaves the business without default`() = runTest {
        create(draft("Tasa A", 2_100L, default = true))

        assertEquals(ArchiveTaxRateResult.Archived, archive(1L))

        assertTrue(requireNotNull(rates.findById(1L)).isArchived)
        assertNull(taxSettings.get().defaultTaxRateId)
        val setup = observe().first()
        assertEquals(listOf(1L), setup.archived.map { it.rate.id })
        assertEquals(emptyList<TaxRateEntry>(), setup.active)
        assertEquals(ArchiveTaxRateResult.AlreadyArchived, archive(1L))
        assertEquals(ArchiveTaxRateResult.NotFound, archive(9L))
    }

    @Test
    fun `archiving another rate keeps the default`() = runTest {
        create(draft("Tasa A", 2_100L, default = true))
        create(draft("Tasa B", 500L))

        archive(2L)

        assertEquals(1L, taxSettings.get().defaultTaxRateId)
    }

    @Test
    fun `an archived rate frees its name and comes back only if the name is free`() = runTest {
        create(draft("Tasa A", 2_100L))
        archive(1L)
        // El nombre de la archivada queda libre para una nueva.
        assertEquals(SaveTaxRateResult.Saved(2L), create(draft("Tasa A", 1_000L)))

        assertEquals(ReactivateTaxRateResult.NameTaken, reactivate(1L))

        edit(2L, draft("Tasa nueva", 1_000L))
        assertEquals(ReactivateTaxRateResult.Reactivated, reactivate(1L))
        assertEquals(ReactivateTaxRateResult.AlreadyActive, reactivate(1L))
        assertEquals(ReactivateTaxRateResult.NotFound, reactivate(9L))
        // Reactivarla no la vuelve tasa por defecto.
        assertNull(taxSettings.get().defaultTaxRateId)
    }

    @Test
    fun `a rate without use is deleted and one in use is only archived`() = runTest {
        create(draft("Tasa A", 2_100L, default = true))
        create(draft("Tasa B", 500L))
        create(draft("Tasa C", 0L))
        rates.assignToProduct(productId = 7L, rateId = 2L)

        val counts = observe().first().active.associate { it.rate.id to it.isInUse }
        assertEquals(mapOf(1L to true, 2L to true, 3L to false), counts)
        assertEquals(DeleteTaxRateResult.InUse, delete(1L))
        assertEquals(DeleteTaxRateResult.InUse, delete(2L))
        assertEquals(DeleteTaxRateResult.Deleted, delete(3L))
        assertNull(rates.findById(3L))
        assertEquals(DeleteTaxRateResult.NotFound, delete(3L))
        assertEquals(1, observe().first().active.single { it.rate.id == 2L }.productCount)
    }

    @Test
    fun `the mode and the recoverable purchase tax change one at a time`() = runTest {
        assertEquals(
            ChangeTaxSettingsResult.Changed,
            change(TaxSettingsChange.Mode(TaxMode.INCLUDED)),
        )
        assertEquals(
            ChangeTaxSettingsResult.Unchanged,
            change(TaxSettingsChange.Mode(TaxMode.INCLUDED)),
        )
        assertEquals(
            ChangeTaxSettingsResult.Changed,
            change(TaxSettingsChange.PurchaseTaxRecoverable(true)),
        )

        assertEquals(
            TaxSettings(TaxMode.INCLUDED, null, purchaseTaxRecoverable = true),
            taxSettings.get(),
        )
        assertEquals(2L, database.tables.value.changeSeq)
        assertTrue(observe().first().isMissingDefaultRate)
    }

    @Test
    fun `turning tax off keeps the rates and the default for later`() = runTest {
        change(TaxSettingsChange.Mode(TaxMode.ADDED))
        create(draft("Tasa A", 2_100L, default = true))

        change(TaxSettingsChange.Mode(TaxMode.NONE))

        assertEquals(1L, taxSettings.get().defaultTaxRateId)
        assertEquals(1, observe().first().active.size)
    }

    @Test
    fun `a write during a restore changes nothing and says why`() = runTest {
        create(draft("Tasa A", 2_100L))
        transactions.restoreInProgress = true
        val failed = StorageFailure.RESTORE_IN_PROGRESS

        assertEquals(SaveTaxRateResult.StorageFailed(failed), create(draft("Tasa B", 100L)))
        assertEquals(SaveTaxRateResult.StorageFailed(failed), edit(1L, draft("Tasa B", 100L)))
        assertEquals(ArchiveTaxRateResult.StorageFailed(failed), archive(1L))
        assertEquals(ReactivateTaxRateResult.StorageFailed(failed), reactivate(1L))
        assertEquals(DeleteTaxRateResult.StorageFailed(failed), delete(1L))
        assertEquals(
            ChangeTaxSettingsResult.StorageFailed(failed),
            change(TaxSettingsChange.Mode(TaxMode.ADDED)),
        )
        assertEquals(1, database.tables.value.taxRates.size)
    }

    @Test
    fun `a failure while marking the default undoes the new rate`() = runTest {
        // La tercera escritura de la transacción, tras dar el id y guardar la tasa, es marcarla
        // por defecto: falla, y la tasa recién creada tampoco queda.
        database.failAtWrite = 3
        try {
            create(draft("Tasa A", 2_100L, default = true))
        } catch (expected: InjectedFailure) {
            // La falla sigue su camino: la transacción ya se deshizo.
        }

        assertEquals(0, database.tables.value.taxRates.size)
        assertNull(taxSettings.get().defaultTaxRateId)
        assertEquals(0L, database.tables.value.changeSeq)
    }

    private fun draft(name: String, rateBp: Long, default: Boolean = false) =
        TaxRateDraft(name, BasisPoints(rateBp), useAsDefault = default)
}
