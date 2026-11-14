package io.github.yesidrobayo.shopledger.ui.location

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationsOverview
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LocationLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.SaveLocationResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferDetail
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferSummary
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransfersRead
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La pantalla de ubicaciones y transferencias con respuestas fijas del dominio. Datos
 * inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeLocationsOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): LocationsViewModel =
        LocationsViewModel(operations).also { advanceUntilIdle() }

    private fun LocationsViewModel.act(scope: TestScope, action: LocationsAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    @Test
    fun `locations and transfers by day are read on open`() = runTest(dispatcher) {
        val model = model()

        val state = model.uiState.value
        assertEquals(LocationsContent.Ready(LocationSamples.OVERVIEW), state.content)
        val transfers = state.transfers as TransfersContent.Ready
        assertEquals(listOf(LocationSamples.DAY), transfers.days.map { it.date })
        assertEquals(listOf(10L, 11L), transfers.days.single().transfers.map { it.transfer.id })
        assertTrue(state.canTransfer)
        assertEquals(listOf(30), operations.windows)
    }

    @Test
    fun `an open location lists only its products with stock, filtered by the search`() =
        runTest(dispatcher) {
            val model = model()

            model.act(this, LocationsAction.Select(LocationSamples.STALL.id))
            val detail = model.uiState.value.detail as LocationDetailContent.Ready
            // El arroz tiene 8, el queso −2,35 y el tercero cero: el de cero no se ve.
            assertEquals(listOf(7L, 8L), detail.items.map { it.id })
            assertTrue(detail.hasStock)

            model.act(this, LocationsAction.DetailQuery("ARROZ"))
            val searched = model.uiState.value.detail as LocationDetailContent.Ready
            assertEquals(listOf(7L), searched.items.map { it.id })
            assertTrue(searched.hasStock)

            model.act(this, LocationsAction.Select(null))
            assertNull(model.uiState.value.selectedId)
            assertEquals("", model.uiState.value.detailQuery)
        }

    @Test
    fun `archiving a location with stock says to transfer it first, without asking`() =
        runTest(dispatcher) {
            val model = model()
            model.act(this, LocationsAction.Select(LocationSamples.STALL.id))

            model.act(this, LocationsAction.AskArchive)

            assertTrue(model.uiState.value.archiveBlocked)
            assertNull(model.uiState.value.dialog)
            assertTrue(operations.archived.isEmpty())
        }

    @Test
    fun `an empty location asks, archives and goes back to the list`() = runTest(dispatcher) {
        operations.stock.value = emptyList()
        val model = model()
        model.act(this, LocationsAction.Select(LocationSamples.STALL.id))

        model.act(this, LocationsAction.AskArchive)
        assertEquals(
            LocationsDialog.Archive(LocationSamples.STALL),
            model.uiState.value.dialog,
        )
        model.act(this, LocationsAction.ConfirmDialog)

        assertEquals(listOf(LocationSamples.STALL.id), operations.archived)
        assertNull(model.uiState.value.selectedId)
    }

    @Test
    fun `stock that arrives while confirming still blocks the archive`() = runTest(dispatcher) {
        operations.stock.value = emptyList()
        operations.archiveResult = LocationLifecycleResult.HasStock
        val model = model()
        model.act(this, LocationsAction.Select(LocationSamples.STALL.id))
        model.act(this, LocationsAction.AskArchive)

        model.act(this, LocationsAction.ConfirmDialog)

        assertTrue(model.uiState.value.archiveBlocked)
        assertEquals(LocationSamples.STALL.id, model.uiState.value.selectedId)
    }

    @Test
    fun `the initial location is never archived`() = runTest(dispatcher) {
        operations.stock.value = emptyList()
        val model = model()
        model.act(this, LocationsAction.Select(LocationSamples.MAIN.id))

        model.act(this, LocationsAction.AskArchive)

        assertNull(model.uiState.value.dialog)
        assertFalse(model.uiState.value.archiveBlocked)
    }

    @Test
    fun `saving a repeated name marks the field and keeps what was written`() =
        runTest(dispatcher) {
            operations.saveResult = SaveLocationResult.DuplicateName
            val model = model()
            model.act(this, LocationsAction.NewLocation)
            model.act(this, LocationsAction.EditorName("Bodega"))
            model.act(this, LocationsAction.EditorKind(LocationKind.WAREHOUSE))

            model.act(this, LocationsAction.SaveLocation)

            val editor = requireNotNull(model.uiState.value.editor)
            assertEquals(setOf(LocationEditorError.NAME_REPEATED), editor.errors)
            assertEquals("Bodega", editor.name)
            assertEquals(
                listOf(null to LocationDraft("Bodega", LocationKind.WAREHOUSE)),
                operations.saves,
            )

            operations.saveResult = SaveLocationResult.Saved(5L)
            model.act(this, LocationsAction.SaveLocation)
            assertNull(model.uiState.value.editor)
            assertEquals(LocationsMessage.LocationSaved, model.uiState.value.message)
        }

    @Test
    fun `renaming the initial location starts from its own name, blank if it has none`() =
        runTest(dispatcher) {
            val model = model()
            model.act(this, LocationsAction.Select(LocationSamples.MAIN.id))

            model.act(this, LocationsAction.RenameLocation)

            val editor = requireNotNull(model.uiState.value.editor)
            assertEquals(LocationSamples.MAIN.id, editor.editingId)
            assertTrue(editor.isInitial)
            assertEquals("", editor.name)
            assertEquals(LocationKind.STORE, editor.kind)
        }

    @Test
    fun `a storage failure on save stays in the sheet`() = runTest(dispatcher) {
        operations.saveResult = SaveLocationResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = model()
        model.act(this, LocationsAction.NewLocation)
        model.act(this, LocationsAction.EditorName("Casa"))

        model.act(this, LocationsAction.SaveLocation)

        assertEquals(SaveFailed(outOfSpace = true), model.uiState.value.editor?.failure)
        assertFalse(model.uiState.value.editor?.saving ?: true)
    }

    @Test
    fun `reaching the end asks for the next window of transfers`() = runTest(dispatcher) {
        operations.hasOlder = true
        val model = model()

        model.act(this, LocationsAction.LoadOlderTransfers)

        assertEquals(listOf(30, 60), operations.windows)
        val transfers = model.uiState.value.transfers as TransfersContent.Ready
        assertFalse(transfers.loadingOlder)
    }

    @Test
    fun `voiding a transfer confirms first and then says it is voided`() = runTest(dispatcher) {
        val model = model()
        model.act(this, LocationsAction.OpenTransfer(10L))
        assertEquals(
            TransferSheet.Ready(LocationSamples.DETAIL),
            model.uiState.value.transfer,
        )

        model.act(this, LocationsAction.AskVoidTransfer)
        assertEquals(LocationsDialog.VoidTransfer(10L), model.uiState.value.dialog)
        model.act(this, LocationsAction.ConfirmDialog)

        assertEquals(listOf(10L), operations.voided)
        assertNull(model.uiState.value.transfer)
        assertEquals(LocationsMessage.TransferVoided, model.uiState.value.message)
    }

    @Test
    fun `a failed void keeps the sheet open with the error`() = runTest(dispatcher) {
        operations.voidResult = VoidStockTransferResult.StorageFailed(StorageFailure.IO_ERROR)
        val model = model()
        model.act(this, LocationsAction.OpenTransfer(10L))
        model.act(this, LocationsAction.AskVoidTransfer)

        model.act(this, LocationsAction.ConfirmDialog)

        val sheet = model.uiState.value.transfer as TransferSheet.Ready
        assertEquals(SaveFailed(outOfSpace = false), sheet.failure)
        assertFalse(sheet.voiding)
    }

    @Test
    fun `a transfer that no longer exists closes its sheet`() = runTest(dispatcher) {
        val model = model()

        model.act(this, LocationsAction.OpenTransfer(99L))

        assertNull(model.uiState.value.transfer)
    }

    @Test
    fun `an unknown zone asks to choose it and a failed read can be retried`() =
        runTest(dispatcher) {
            operations.unknownZone = true
            operations.failingReads = 1
            val model = model()
            assertEquals(LocationsContent.ReadError, model.uiState.value.content)
            assertEquals(TransfersContent.UnknownZone, model.uiState.value.transfers)

            model.act(this, LocationsAction.Retry)

            assertEquals(
                LocationsContent.Ready(LocationSamples.OVERVIEW),
                model.uiState.value.content,
            )
        }
}

/** Respuestas fijas para la pantalla, que anotan lo que se pidió. */
private class FakeLocationsOperations : LocationsOperations {
    val stock = MutableStateFlow(
        listOf(
            LocationSamples.RICE,
            LocationSamples.CHEESE,
            LocationSamples.product(9L, "Sal", LocationSamples.UNIT.id, onHand = 0L),
        ),
    )
    var failingReads = 0
    var unknownZone = false
    var hasOlder = false
    var saveResult: SaveLocationResult = SaveLocationResult.Saved(1L)
    var archiveResult: LocationLifecycleResult = LocationLifecycleResult.Done
    var voidResult: VoidStockTransferResult = VoidStockTransferResult.Voided
    val windows = mutableListOf<Int>()
    val saves = mutableListOf<Pair<Long?, LocationDraft>>()
    val archived = mutableListOf<Long>()
    val voided = mutableListOf<Long>()

    override fun locations(): Flow<LocationsOverview> = flow {
        if (failingReads > 0) {
            failingReads--
            error("Lectura fallida a propósito")
        }
        emit(LocationSamples.OVERVIEW)
    }

    override fun stockAt(locationId: Long): Flow<InventoryOverview> = stock.map { products ->
        InventoryOverview(
            hasProducts = products.isNotEmpty(),
            tracked = products,
            lowStock = emptyList(),
            negative = emptyList(),
            value = Money.ZERO,
            archivedValue = null,
            hasStockWithoutCost = false,
            units = listOf(LocationSamples.UNIT, LocationSamples.KILOGRAM).associateBy { it.id },
        )
    }

    override fun transfers(days: Int): Flow<StockTransfersRead> = flow {
        windows += days
        if (unknownZone) {
            emit(StockTransfersRead.UnknownZone)
        } else {
            emit(
                StockTransfersRead.Ready(
                    listOf(
                        StockTransferSummary(LocationSamples.ACTIVE_TRANSFER, 2),
                        StockTransferSummary(LocationSamples.VOIDED_TRANSFER, 1),
                    ),
                    hasOlder,
                ),
            )
        }
    }

    override suspend fun readTransfer(transferId: Long): StockTransferDetail? =
        LocationSamples.DETAIL.takeIf { it.transfer.id == transferId }

    override suspend fun saveLocation(editingId: Long?, draft: LocationDraft): SaveLocationResult {
        saves += editingId to draft
        return saveResult
    }

    override suspend fun archive(locationId: Long): LocationLifecycleResult {
        archived += locationId
        return archiveResult
    }

    override suspend fun reactivate(locationId: Long): LocationLifecycleResult =
        LocationLifecycleResult.Done

    override suspend fun voidTransfer(transferId: Long): VoidStockTransferResult {
        voided += transferId
        return voidResult
    }
}
