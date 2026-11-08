package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ArchiveTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ChangeTaxSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.CreateTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.DeleteTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.EditTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxSetupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ReactivateTaxRateUseCase
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La pantalla de impuestos con los casos de uso reales sobre una base en memoria: el modo se
 * confirma antes de cambiar, la hoja muestra cada error en su campo y nunca guarda un porcentaje
 * que no se pudo leer, y una falla de la base conserva lo escrito. Nombres y tasas inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaxSettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = MemoryTaxStore()
    private val comma = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aNewBusinessShowsNoTaxAndNoRates() = runScreen { viewModel ->
        val state = ready(viewModel)
        assertEquals(TaxMode.NONE, state.mode)
        assertEquals(emptyList<TaxRateRow>(), state.active)
        assertTrue(!state.missingDefaultRate)
    }

    @Test
    fun changingTheModeAsksFirstAndOnlyThenWrites() = runScreen { viewModel ->
        viewModel.onAction(TaxSettingsAction.SelectMode(TaxMode.NONE))
        assertNull(ready(viewModel).dialog)

        viewModel.onAction(TaxSettingsAction.SelectMode(TaxMode.INCLUDED))
        assertEquals(TaxDialog.ChangeMode(TaxMode.INCLUDED), ready(viewModel).dialog)
        assertEquals(TaxMode.NONE, store.settings.value.mode)

        viewModel.onAction(TaxSettingsAction.ConfirmDialog)
        assertEquals(TaxMode.INCLUDED, ready(viewModel).mode)
        assertNull(ready(viewModel).dialog)
        assertTrue(ready(viewModel).missingDefaultRate)

        viewModel.onAction(TaxSettingsAction.SelectMode(TaxMode.ADDED))
        viewModel.onAction(TaxSettingsAction.DismissDialog)
        assertEquals(TaxMode.INCLUDED, store.settings.value.mode)
    }

    @Test
    fun aRateWithTwoDecimalsIsSavedAsDefaultAndReadsBackTheSame() = runScreen { viewModel ->
        viewModel.onAction(TaxSettingsAction.NewRate)
        viewModel.onAction(TaxSettingsAction.EditorName("Tasa reducida"))
        viewModel.onAction(TaxSettingsAction.EditorPercent("8,25"))
        viewModel.onAction(TaxSettingsAction.EditorDefault(true))
        viewModel.onAction(TaxSettingsAction.SaveRate(comma))

        val state = ready(viewModel)
        assertNull(state.editor)
        assertEquals(TaxMessage.RateSaved, state.message)
        val row = state.active.single()
        assertEquals(BasisPoints(825), row.rate)
        assertTrue(row.isDefault)
        assertTrue(row.inUse)

        viewModel.onAction(TaxSettingsAction.MessageShown)
        viewModel.onAction(TaxSettingsAction.EditRate(row.id, comma))
        assertEquals(
            TaxRateEditor(row.id, "Tasa reducida", "8,25", useAsDefault = true),
            ready(viewModel).editor,
        )
    }

    @Test
    fun anUnreadablePercentNeverSavesAndEveryErrorShowsAtOnce() = runScreen { viewModel ->
        viewModel.onAction(TaxSettingsAction.NewRate)
        viewModel.onAction(TaxSettingsAction.EditorPercent("8,125"))
        viewModel.onAction(TaxSettingsAction.SaveRate(comma))

        var editor = requireNotNull(ready(viewModel).editor)
        assertEquals(TaxNameError.EMPTY, editor.nameError)
        assertEquals(TaxPercentError.DECIMALS, editor.percentError)

        viewModel.onAction(TaxSettingsAction.EditorName("Tasa"))
        viewModel.onAction(TaxSettingsAction.EditorPercent("120"))
        viewModel.onAction(TaxSettingsAction.SaveRate(comma))
        editor = requireNotNull(ready(viewModel).editor)
        assertNull(editor.nameError)
        assertEquals(TaxPercentError.RANGE, editor.percentError)

        viewModel.onAction(TaxSettingsAction.EditorPercent(""))
        assertNull(requireNotNull(ready(viewModel).editor).percentError)
        viewModel.onAction(TaxSettingsAction.SaveRate(comma))
        assertEquals(TaxPercentError.RANGE, requireNotNull(ready(viewModel).editor).percentError)
        assertEquals(0, store.rates.value.size)
    }

    @Test
    fun aRepeatedNameIsAFieldErrorAndKeepsWhatWasWritten() = runScreen { viewModel ->
        store.add("Tasa A", 2_100)
        viewModel.onAction(TaxSettingsAction.NewRate)
        viewModel.onAction(TaxSettingsAction.EditorName("tasa a"))
        viewModel.onAction(TaxSettingsAction.EditorPercent("5"))
        viewModel.onAction(TaxSettingsAction.SaveRate(comma))

        val editor = requireNotNull(ready(viewModel).editor)
        assertEquals(TaxNameError.DUPLICATE, editor.nameError)
        assertEquals("tasa a", editor.name)
        assertEquals("5", editor.percent)
    }

    @Test
    fun aFullDiskKeepsTheSheetOpenAndSaysToFreeSpace() = runScreen { viewModel ->
        viewModel.onAction(TaxSettingsAction.NewRate)
        viewModel.onAction(TaxSettingsAction.EditorName("Tasa"))
        viewModel.onAction(TaxSettingsAction.EditorPercent("10"))
        store.failure = StorageFailure.DISK_FULL
        viewModel.onAction(TaxSettingsAction.SaveRate(comma))

        val editor = requireNotNull(ready(viewModel).editor)
        assertEquals(SaveFailure(outOfSpace = true), editor.saveFailure)
        assertEquals("Tasa", editor.name)

        // Cambiar lo escrito borra el aviso, y otra falla no pide liberar espacio.
        viewModel.onAction(TaxSettingsAction.EditorPercent("11"))
        assertNull(requireNotNull(ready(viewModel).editor).saveFailure)
        store.failure = StorageFailure.IO_ERROR
        viewModel.onAction(TaxSettingsAction.SaveRate(comma))
        assertEquals(
            SaveFailure(outOfSpace = false),
            requireNotNull(ready(viewModel).editor).saveFailure,
        )
    }

    @Test
    fun aRateInUseIsArchivedAndAnUnusedOneDeletedAfterConfirming() = runScreen { viewModel ->
        val used = store.add("Tasa A", 2_100)
        val unused = store.add("Tasa B", 500)
        store.productCounts.value = mapOf(used to 1)

        assertEquals(listOf(true, false), ready(viewModel).active.map { it.inUse })
        viewModel.onAction(TaxSettingsAction.AskArchive(used))
        assertEquals(TaxDialog.Archive(used, "Tasa A"), ready(viewModel).dialog)
        viewModel.onAction(TaxSettingsAction.ConfirmDialog)
        viewModel.onAction(TaxSettingsAction.AskDelete(unused))
        assertEquals(TaxDialog.Delete(unused, "Tasa B"), ready(viewModel).dialog)
        viewModel.onAction(TaxSettingsAction.ConfirmDialog)

        val state = ready(viewModel)
        assertEquals(emptyList<TaxRateRow>(), state.active)
        assertEquals(listOf(used), state.archived.map { it.id })

        viewModel.onAction(TaxSettingsAction.Reactivate(used))
        assertEquals(listOf(used), ready(viewModel).active.map { it.id })
    }

    @Test
    fun reactivatingOverAnActiveNameSaysSo() = runScreen { viewModel ->
        val old = store.add("Tasa A", 2_100, archived = true)
        store.add("Tasa A", 1_000)

        viewModel.onAction(TaxSettingsAction.Reactivate(old))

        assertEquals(TaxMessage.NameTaken, ready(viewModel).message)
    }

    @Test
    fun theRecoverablePurchaseTaxIsSavedRightAway() = runScreen { viewModel ->
        viewModel.onAction(TaxSettingsAction.SetPurchaseTaxRecoverable(true))
        assertTrue(ready(viewModel).purchaseTaxRecoverable)
        store.failure = StorageFailure.IO_ERROR
        viewModel.onAction(TaxSettingsAction.SetPurchaseTaxRecoverable(false))
        assertEquals(TaxMessage.Failed(SaveFailure(outOfSpace = false)), ready(viewModel).message)
    }

    @Test
    fun aPercentIsWrittenBackWithoutExtraZeros() {
        val point = NumberSymbols(decimalSeparator = '.', minusSign = '-', grouping = null)
        assertEquals("21", percentInputText(BasisPoints(2_100), comma))
        assertEquals("10,5", percentInputText(BasisPoints(1_050), comma))
        assertEquals("0.05", percentInputText(BasisPoints(5), point))
        assertEquals("100", percentInputText(BasisPoints(10_000), point))
    }

    private fun ready(viewModel: TaxSettingsViewModel): TaxSettingsUiState.Ready =
        viewModel.uiState.value as TaxSettingsUiState.Ready

    private fun runScreen(block: suspend TestScope.(TaxSettingsViewModel) -> Unit) =
        runTest(dispatcher) {
            val clock = Clock { Instant.fromEpochMilliseconds(1_000) }
            val viewModel =
                TaxSettingsViewModel(
                    TaxSettingsUseCases(
                        observe = ObserveTaxSetupUseCase(store, store.settingsPort),
                        create = CreateTaxRateUseCase(store, store, store.settingsPort, clock),
                        edit = EditTaxRateUseCase(store, store, store.settingsPort, clock),
                        archive = ArchiveTaxRateUseCase(store, store, store.settingsPort, clock),
                        reactivate = ReactivateTaxRateUseCase(store, store, clock),
                        delete = DeleteTaxRateUseCase(store, store, store.settingsPort),
                        change = ChangeTaxSettingsUseCase(store, store.settingsPort, clock),
                    ),
                )
            val collecting = launch { viewModel.uiState.collect {} }
            try {
                block(viewModel)
            } finally {
                collecting.cancel()
            }
        }
}

/**
 * Tasas y ajustes en memoria, con transacciones de todo o nada: una copia antes de escribir que
 * vuelve si el bloque deshace. [failure] hace fallar la próxima escritura como la base.
 */
private class MemoryTaxStore :
    TaxRateRepository,
    TransactionRunner {
    val rates = MutableStateFlow<Map<Long, TaxRate>>(emptyMap())
    val settings = MutableStateFlow(TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false))
    val productCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    var failure: StorageFailure? = null
    private var nextId = 1L

    val settingsPort =
        object : TaxSettingsRepository {
            override fun observe(): Flow<TaxSettings> = settings

            override suspend fun get(): TaxSettings = settings.value

            override suspend fun update(taxes: TaxSettings, updatedAt: Instant) {
                settings.value = taxes
            }
        }

    fun add(name: String, rateBp: Long, archived: Boolean = false): Long {
        val id = nextId++
        val at = Instant.fromEpochMilliseconds(1)
        val details = TaxRateDetails(name, name.lowercase(), BasisPoints(rateBp))
        rates.value += id to TaxRate(id, details, if (archived) at else null, at, at)
        return id
    }

    override suspend fun <T> write(block: suspend () -> TxResult<T>): T {
        failure?.let { throw StorageFailureException(it) }
        val before = rates.value to settings.value
        return when (val result = block()) {
            is TxResult.Commit -> result.value

            is TxResult.CommitUncounted -> result.value

            is TxResult.Rollback -> {
                rates.value = before.first
                settings.value = before.second
                result.value
            }
        }
    }

    override suspend fun <T> read(block: suspend () -> T): T = block()

    override fun observe(filter: ArchiveFilter): Flow<List<TaxRate>> = rates.map { all ->
        all.values
            .filter { (filter == ArchiveFilter.ARCHIVED) == it.isArchived }
            .sortedBy { it.id }
    }

    override fun observeProductCounts(): Flow<Map<Long, Int>> = productCounts

    override suspend fun findById(id: Long): TaxRate? = rates.value[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): TaxRate? =
        rates.value.values.firstOrNull {
            !it.isArchived && it.details.normalizedName == normalizedName
        }

    override suspend fun countProducts(id: Long): Int = productCounts.value[id] ?: 0

    override suspend fun insert(details: TaxRateDetails, createdAt: Instant): Long {
        val id = nextId++
        rates.value += id to TaxRate(id, details, null, createdAt, createdAt)
        return id
    }

    override suspend fun update(id: Long, details: TaxRateDetails, updatedAt: Instant) {
        val rate = requireNotNull(rates.value[id])
        rates.value += id to rate.copy(details = details, updatedAt = updatedAt)
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        val rate = requireNotNull(rates.value[id])
        rates.value += id to rate.copy(archivedAt = archivedAt, updatedAt = updatedAt)
    }

    override suspend fun delete(id: Long) {
        rates.value -= id
    }
}
