package io.github.yesidrobayo.shopledger.ui.scanner

import io.github.yesidrobayo.shopledger.di.ScannerTelemetryLog
import io.github.yesidrobayo.shopledger.domain.model.catalog.CodeMatch
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.AssignBarcodeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * Con el catálogo, el escáner toma solo el código de un producto vendible, en su unidad si es el
 * de una presentación. Uno sin producto o archivado abre su hoja, que deja crear, asignar o
 * reactivar, y mientras está abierta no se toma otro código. Los productos son inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerLookupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val taken = mutableListOf<ScanTake>()
    private val catalog = FakeScanCatalog()
    private var now = 0L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(continuous: Boolean = true) = ScannerViewModel(
        continuous = continuous,
        deliver = { taken += it },
        telemetry = ScannerTelemetryLog(NoLog),
        catalog = catalog,
        nanoTime = { now },
    )

    private fun ScannerViewModel.read(sink: (FrameOutcome) -> Unit, text: String) {
        sink(FrameOutcome(now, MS, ScannedCode(ScanFormat.EAN_13, text)))
        now += WINDOW
    }

    @Test
    fun aProductCodeIsTakenWithItsProductAndName() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()

        model.read(frames, OIL_CODE)
        runCurrent()

        assertEquals(listOf(ScanTake(OIL_CODE, CodeMatch.Found(OIL, null))), taken)
        assertEquals(OIL.details.name, model.uiState.value.lastRead)
        assertEquals(1, model.uiState.value.readCount)
    }

    @Test
    fun aParentCodeIsTakenAndEndsAContinuousSession() = runTest(dispatcher) {
        val model = model(continuous = true)
        val frames = model.openFrameSink()

        model.read(frames, OIL_CODE)
        runCurrent()
        assertFalse(model.uiState.value.closed)
        model.read(frames, SHIRT_CODE)
        runCurrent()
        model.read(frames, OIL_CODE)
        runCurrent()

        // El aceite sigue la lectura; la camiseta la cierra, para elegir su variante enseguida.
        assertEquals(listOf(OIL_CODE, SHIRT_CODE), taken.map { it.code })
        assertTrue(model.uiState.value.closed)
    }

    @Test
    fun aBoxCodeIsTakenWithItsUnit() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()

        model.read(frames, BOX_CODE)
        runCurrent()

        assertEquals(BOX, taken.single().match?.conversion)
    }

    @Test
    fun anUnknownCodeOpensItsSheetAndPausesReading() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()

        model.read(frames, UNKNOWN_CODE)
        runCurrent()
        model.read(frames, OIL_CODE)
        runCurrent()

        assertEquals(ScanSheet.Unknown(UNKNOWN_CODE), model.uiState.value.sheet)
        assertTrue(taken.isEmpty())

        model.onAction(ScannerAction.DismissSheet)
        model.read(frames, OIL_CODE)
        runCurrent()
        assertNull(model.uiState.value.sheet)
        assertEquals(listOf(OIL_CODE), taken.map { it.code })
    }

    @Test
    fun aProductCreatedFromTheSheetIsTakenOnReturn() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()
        model.read(frames, UNKNOWN_CODE)
        runCurrent()

        model.onAction(ScannerAction.CreateProduct(UNKNOWN_CODE))
        assertNull(model.uiState.value.sheet)
        // Mientras tanto, el formulario guardó el producto con ese código.
        catalog.created = NEW
        model.onResumed()
        runCurrent()
        // Volver otra vez no lo toma dos veces.
        model.onResumed()
        runCurrent()

        assertEquals(listOf(ScanTake(UNKNOWN_CODE, CodeMatch.Found(NEW, null))), taken)
    }

    @Test
    fun aCodeAssignedToAProductIsTaken() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()
        model.read(frames, UNKNOWN_CODE)
        runCurrent()

        model.onAction(ScannerAction.StartAssign)
        runCurrent()
        assertEquals(
            ScanSheet.Assign(UNKNOWN_CODE, listOf(CANDIDATE)),
            model.uiState.value.sheet,
        )
        model.onAction(ScannerAction.AssignTo(NEW.id))
        runCurrent()

        assertEquals(listOf(NEW.id to UNKNOWN_CODE), catalog.assigned)
        assertNull(model.uiState.value.sheet)
        assertEquals(listOf(ScanTake(UNKNOWN_CODE, CodeMatch.Found(NEW, null))), taken)
    }

    @Test
    fun aTakenCodeGoesBackToItsSheetWithTheOtherName() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()
        catalog.assignResult = AssignBarcodeResult.Taken("Arroz 1 kg")
        model.read(frames, UNKNOWN_CODE)
        runCurrent()

        model.onAction(ScannerAction.StartAssign)
        runCurrent()
        model.onAction(ScannerAction.AssignTo(NEW.id))
        runCurrent()

        assertEquals(
            ScanSheet.Unknown(UNKNOWN_CODE, ScanProblem.CodeTaken("Arroz 1 kg")),
            model.uiState.value.sheet,
        )
        assertTrue(taken.isEmpty())
    }

    @Test
    fun aFailedAssignmentSaysNothingWasSaved() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()
        catalog.assignResult = AssignBarcodeResult.StorageFailed(StorageFailure.DISK_FULL)
        model.read(frames, UNKNOWN_CODE)
        runCurrent()
        model.onAction(ScannerAction.StartAssign)
        runCurrent()

        model.onAction(ScannerAction.AssignTo(NEW.id))
        runCurrent()

        assertEquals(
            ScanSheet.Unknown(UNKNOWN_CODE, ScanProblem.SaveFailed),
            model.uiState.value.sheet,
        )
    }

    @Test
    fun anArchivedProductIsOfferedToReactivate() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()
        model.read(frames, ARCHIVED_CODE)
        runCurrent()
        assertEquals(
            ScanSheet.Archived(ARCHIVED_CODE, ARCHIVED.id, ARCHIVED.details.name),
            model.uiState.value.sheet,
        )

        model.onAction(ScannerAction.Reactivate(ARCHIVED.id))
        runCurrent()

        assertEquals(listOf(ARCHIVED.id), catalog.reactivated)
        assertNull(model.uiState.value.sheet)
        assertEquals(listOf(ARCHIVED_CODE), taken.map { it.code })
    }

    @Test
    fun aReactivationThatClashesKeepsTheSheet() = runTest(dispatcher) {
        val model = model()
        val frames = model.openFrameSink()
        catalog.reactivateResult = ReactivateProductResult.NameTaken("Aceite")
        model.read(frames, ARCHIVED_CODE)
        runCurrent()

        model.onAction(ScannerAction.Reactivate(ARCHIVED.id))
        runCurrent()

        assertEquals(
            ScanProblem.Duplicate,
            (model.uiState.value.sheet as ScanSheet.Archived).problem,
        )
        assertTrue(taken.isEmpty())
    }

    @Test
    fun aTypedCodeIsLookedUpToo() = runTest(dispatcher) {
        val model = model(continuous = false)

        model.onAction(ScannerAction.CodeTyped(" $OIL_CODE "))
        runCurrent()

        assertEquals(listOf(ScanTake(OIL_CODE, CodeMatch.Found(OIL, null))), taken)
        assertTrue(model.uiState.value.closed)
    }

    /** Un catálogo en memoria: el aceite, su caja, un archivado y lo que se cree o asigne. */
    private class FakeScanCatalog : ScanCatalog {
        var created: Product? = null
        var assignResult: AssignBarcodeResult = AssignBarcodeResult.Assigned(UNKNOWN_CODE)
        var reactivateResult: ReactivateProductResult = ReactivateProductResult.Reactivated
        val assigned = mutableListOf<Pair<Long, String>>()
        val reactivated = mutableListOf<Long>()
        private var archivedIsActive = false

        override suspend fun find(code: String): CodeMatch = when (code) {
            OIL_CODE -> CodeMatch.Found(OIL, null)

            BOX_CODE -> CodeMatch.Found(OIL, BOX)

            SHIRT_CODE -> CodeMatch.Found(SHIRT, null)

            ARCHIVED_CODE ->
                if (archivedIsActive) {
                    CodeMatch.Found(ARCHIVED, null)
                } else {
                    CodeMatch.Archived(ARCHIVED)
                }

            else -> created?.let { CodeMatch.Found(it, null) } ?: CodeMatch.Unknown(code)
        }

        override suspend fun withoutCode(): List<ScanCandidate> = listOf(CANDIDATE)

        override suspend fun assign(productId: Long, code: String): AssignBarcodeResult {
            assigned += productId to code
            if (assignResult is AssignBarcodeResult.Assigned) created = NEW
            return assignResult
        }

        override suspend fun reactivate(productId: Long): ReactivateProductResult {
            reactivated += productId
            if (reactivateResult == ReactivateProductResult.Reactivated) archivedIsActive = true
            return reactivateResult
        }

        override suspend fun hasVariants(productId: Long): Boolean = productId == SHIRT.id
    }

    private object NoLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }

    private companion object {
        const val MS = 1_000_000L
        const val WINDOW = 2_000_000_000L
        const val OIL_CODE = "2900000123458"
        const val BOX_CODE = "2900000999992"
        const val ARCHIVED_CODE = "2900000555551"
        const val SHIRT_CODE = "2900000777773"
        const val UNKNOWN_CODE = "P00000042"
        val OIL = product(1L, "Aceite 900 ml")
        val NEW = product(2L, "Jabón de avena")
        val ARCHIVED = product(3L, "Arroz 1 kg")
        val SHIRT = product(4L, "Camiseta básica")
        val CANDIDATE = ScanCandidate(NEW.id, NEW.details.name, "jabon de avena")
        val BOX =
            ProductConversion(
                id = 5L,
                productId = OIL.id,
                details = ConversionDetails(7L, 12_000L, null, BOX_CODE, true, true),
            )
    }
}
