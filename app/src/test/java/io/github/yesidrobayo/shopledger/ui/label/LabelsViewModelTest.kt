package io.github.yesidrobayo.shopledger.ui.label

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrintersResult
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelItem
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.GenerateOwnBarcodesResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RepricedProductsResult
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `Etiquetas imprimibles`: qué entra con cada plantilla, las copias, el permiso pedido solo al
 * imprimir, la impresora elegida, un fallo que no pierde nada y la hoja PDF. Los productos y las
 * impresoras son inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LabelsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val catalog = FakeLabelCatalog()
    private val printing = FakeLabelPrinting()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(onlyRepriced: Boolean = false) =
        LabelsViewModel(catalog, printing, now = { NOW }, onlyRepriced = onlyRepriced)

    @Test
    fun opensEmptyWithoutAskingForAnyPermission() = runTest(dispatcher) {
        val model = model()
        runCurrent()

        val state = model.uiState.value
        assertFalse(state.waiting)
        assertEquals(emptyList<LabelItem>(), state.chosen)
        assertEquals(0, state.labelCount)
        assertFalse(state.askingPermission)
        assertTrue(state.needsPrinter)
        assertEquals(BusinessDate.parse("2026-11-13"), state.since)
    }

    @Test
    fun pickedProductsComeInOrderWithTheirCopies() = runTest(dispatcher) {
        val model = model()
        runCurrent()

        model.onAction(LabelsAction.TogglePicked(OWN.id))
        model.onAction(LabelsAction.TogglePicked(FACTORY.id))
        model.onAction(LabelsAction.Increment(OWN.id))
        model.onAction(LabelsAction.Increment(OWN.id))

        val state = model.uiState.value
        assertEquals(listOf(OWN.id, FACTORY.id), state.chosen.map { it.productId })
        assertEquals(listOf(3, 1), state.chosen.map { it.copies })
        // El código de fábrica no cabe en 58 mm: salen solo las tres copias del propio.
        assertEquals(3, state.labelCount)
    }

    @Test
    fun oneCopyLessThanOneTakesTheProductOutOfTheList() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.onAction(LabelsAction.TogglePicked(OWN.id))
        model.onAction(LabelsAction.Increment(OWN.id))

        model.onAction(LabelsAction.Decrement(OWN.id))
        assertEquals(listOf(1), model.uiState.value.chosen.map { it.copies })
        model.onAction(LabelsAction.Decrement(OWN.id))

        assertEquals(emptyList<LabelItem>(), model.uiState.value.chosen)
    }

    @Test
    fun eachTemplateLeavesOutWhatItCannotPrint() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        listOf(OWN, NO_PRICE, NO_CODE).forEach {
            model.onAction(LabelsAction.TogglePicked(it.id))
        }

        val withCode = model.uiState.value
        assertEquals(listOf(OWN.id), withCode.printable.map { it.productId })
        assertEquals(listOf(NO_CODE.id), withCode.plan.withoutCode.map { it.productId })
        assertEquals(listOf(NO_PRICE.id), withCode.plan.withoutPrice.map { it.productId })

        model.onAction(LabelsAction.SelectTemplate(LabelTemplate.NAME_PRICE))
        val namePrice = model.uiState.value.printable.map { it.productId }
        assertEquals(listOf(OWN.id, NO_CODE.id), namePrice)

        model.onAction(LabelsAction.SelectTemplate(LabelTemplate.CODE_ONLY))
        val codeOnly = model.uiState.value.printable.map { it.productId }
        assertEquals(listOf(OWN.id, NO_PRICE.id), codeOnly)
    }

    @Test
    fun aFactoryCodeDoesNotFitOn58mmAndTheWiderSizeTakesIt() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.onAction(LabelsAction.TogglePicked(FACTORY.id))

        assertEquals(listOf(FACTORY.id), model.uiState.value.tooLong.map { it.productId })
        assertEquals(0, model.uiState.value.labelCount)
        assertEquals(LabelSize.MM_80, model.uiState.value.wider)

        model.onAction(LabelsAction.UseWider)

        val state = model.uiState.value
        assertEquals(LabelSize.MM_80, state.size)
        assertEquals(emptyList<LabelItem>(), state.tooLong)
        assertEquals(1, state.labelCount)
        assertNull(state.wider)
    }

    @Test
    fun aCategoryListsItsProductsByName() = runTest(dispatcher) {
        val model = model()
        runCurrent()

        model.onAction(LabelsAction.SelectScope(LabelScope.CATEGORY))
        assertEquals(LabelSheet.CATEGORY, model.uiState.value.sheet)
        model.onAction(LabelsAction.PickCategory(CATEGORY_ID))

        val state = model.uiState.value
        assertNull(state.sheet)
        assertEquals(listOf(NO_CODE.id, OWN.id), state.chosen.map { it.productId })
    }

    @Test
    fun onlyRepricedReadsTheChangesSinceTodayAndThenSinceTheChosenDay() = runTest(dispatcher) {
        catalog.repriced = mapOf(
            BusinessDate.parse("2026-11-13") to setOf(OWN.id),
            BusinessDate.parse("2026-11-01") to setOf(OWN.id, NO_CODE.id),
        )
        val model = model(onlyRepriced = true)
        runCurrent()

        assertEquals(LabelScope.CHANGED, model.uiState.value.scope)
        assertEquals(listOf(OWN.id), model.uiState.value.chosen.map { it.productId })

        model.onAction(LabelsAction.SetSince(requireNotNull(BusinessDate.parse("2026-11-01"))))
        assertTrue(model.uiState.value.waiting)
        runCurrent()

        assertEquals(listOf(NO_CODE.id, OWN.id), model.uiState.value.chosen.map { it.productId })
    }

    @Test
    fun aDeniedPermissionLeavesTheSheetAndNeverPrints() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.onAction(LabelsAction.TogglePicked(OWN.id))

        model.onAction(LabelsAction.Print)
        assertTrue(model.uiState.value.askingPermission)
        model.onAction(LabelsAction.PermissionResult(granted = false))

        val state = model.uiState.value
        assertFalse(state.askingPermission)
        assertEquals(LabelNotice.PermissionDenied, state.notice)
        assertEquals(0, printing.printed.size)
        assertEquals(0, printing.listed)
    }

    @Test
    fun printingAsksForThePrinterThenSendsAndSaysWhere() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.onAction(LabelsAction.TogglePicked(OWN.id))
        model.onAction(LabelsAction.Increment(OWN.id))

        model.onAction(LabelsAction.Print)
        model.onAction(LabelsAction.PermissionResult(granted = true))
        assertEquals(LabelSheet.PRINTERS, model.uiState.value.sheet)
        assertEquals(listOf(PRINTER), model.uiState.value.printers)

        model.onAction(LabelsAction.ChoosePrinter(PRINTER))
        assertTrue(model.uiState.value.printing)
        runCurrent()

        val state = model.uiState.value
        assertFalse(state.printing)
        assertEquals(LabelMessage.Sent(PRINTER), state.message)
        val job = printing.printed.single()
        assertEquals(PRINTER, job.printer)
        assertEquals(listOf(OWN.id to 2), job.labels.map { it.productId to it.copies })
        assertEquals(LabelSize.MM_58, job.size)
    }

    @Test
    fun aFailedPrintKeepsEverythingAndRetriesWithTheSamePrinter() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.onAction(LabelsAction.TogglePicked(OWN.id))
        model.onAction(LabelsAction.ConfigurePrinter)
        model.onAction(LabelsAction.PermissionResult(granted = true))
        model.onAction(LabelsAction.ChoosePrinter(PRINTER))
        // Configurar no imprime: solo elige.
        assertEquals(0, printing.printed.size)

        printing.next = PrintResult.Failed(PrintFailure.WRITE_FAILED)
        model.onAction(LabelsAction.Print)
        runCurrent()
        val failed = LabelNotice.PrintFailed(PrintFailure.WRITE_FAILED)
        assertEquals(failed, model.uiState.value.notice)
        assertEquals(listOf(OWN.id), model.uiState.value.chosen.map { it.productId })

        printing.next = PrintResult.Sent(BYTES)
        model.onAction(LabelsAction.Print)
        runCurrent()

        assertNull(model.uiState.value.notice)
        assertEquals(2, printing.printed.size)
        assertEquals(1, printing.listed)
    }

    @Test
    fun noPairedPrintersOrBluetoothOffIsSaid() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        printing.paired = PairedPrintersResult.Unavailable(PrintFailure.BLUETOOTH_OFF)

        model.onAction(LabelsAction.ConfigurePrinter)
        model.onAction(LabelsAction.PermissionResult(granted = true))

        assertEquals(
            LabelNotice.PrintersUnavailable(PrintFailure.BLUETOOTH_OFF),
            model.uiState.value.notice,
        )
        assertNull(model.uiState.value.sheet)
    }

    @Test
    fun theSheetUsesItsOwnWidthAndNeedsNoPermission() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        // En 58 mm el código de fábrica no cabe; en la hoja sí.
        model.onAction(LabelsAction.TogglePicked(FACTORY.id))
        model.onAction(LabelsAction.SelectSize(LabelSize.SHEET))
        assertFalse(model.uiState.value.needsPrinter)

        model.onAction(LabelsAction.Print)

        val job = requireNotNull(model.uiState.value.sheetJob)
        assertEquals(listOf(FACTORY.id), job.labels.map { it.productId })
        assertFalse(model.uiState.value.askingPermission)
        model.onAction(LabelsAction.SheetOpened(opened = false))
        assertNull(model.uiState.value.sheetJob)
        assertEquals(LabelMessage.SheetFailed, model.uiState.value.message)
    }

    @Test
    fun aFailedPrintOffersTheSheetWithTheSameLabels() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.onAction(LabelsAction.TogglePicked(OWN.id))
        model.onAction(LabelsAction.Print)
        model.onAction(LabelsAction.PermissionResult(granted = false))

        model.onAction(LabelsAction.CreatePdf)

        val job = requireNotNull(model.uiState.value.sheetJob)
        assertEquals(listOf(OWN.id), job.labels.map { it.productId })
        assertNull(model.uiState.value.notice)
    }

    @Test
    fun generatingCodesAsksFirstAndAFailureIsSaid() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.onAction(LabelsAction.TogglePicked(NO_CODE.id))

        model.onAction(LabelsAction.GenerateCodes)
        assertTrue(model.uiState.value.confirmingGenerate)
        model.onAction(LabelsAction.DismissGenerate)
        assertEquals(emptyList<Collection<Long>>(), catalog.generated)

        model.onAction(LabelsAction.GenerateCodes)
        model.onAction(LabelsAction.ConfirmGenerate)
        runCurrent()
        assertEquals(listOf(listOf(NO_CODE.id)), catalog.generated.map { it.toList() })

        catalog.generateResult =
            GenerateOwnBarcodesResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS)
        model.onAction(LabelsAction.ConfirmGenerate)
        runCurrent()
        assertEquals(LabelMessage.SaveFailed, model.uiState.value.message)
        model.onAction(LabelsAction.MessageShown)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun aReadErrorIsShownAndRetried() = runTest(dispatcher) {
        catalog.failing = true
        val model = model()
        runCurrent()
        assertTrue(model.uiState.value.readError)

        catalog.failing = false
        model.onAction(LabelsAction.Retry)
        runCurrent()

        assertFalse(model.uiState.value.readError)
        assertFalse(model.uiState.value.waiting)
    }

    private class FakeLabelCatalog : LabelCatalog {
        var failing = false
        var repriced: Map<BusinessDate?, Set<Long>> = emptyMap()
        var generateResult: GenerateOwnBarcodesResult =
            GenerateOwnBarcodesResult.Done(emptyMap(), emptyList(), emptyList())
        val generated = mutableListOf<Collection<Long>>()

        override fun products(): Flow<List<Product>> = if (failing) {
            flow { error("Sin base") }
        } else {
            MutableStateFlow(listOf(OWN, FACTORY, NO_PRICE, NO_CODE))
        }

        override fun categories(): Flow<List<Category>> = flowOf(listOf(CATEGORY))

        override fun calendar(): Flow<BusinessCalendarResult> =
            flowOf(BusinessCalendarResult.Ready(CALENDAR))

        override suspend fun repriced(from: BusinessDate): RepricedProductsResult =
            RepricedProductsResult.Found(repriced[from].orEmpty())

        override suspend fun generateCodes(
            productIds: Collection<Long>,
        ): GenerateOwnBarcodesResult {
            generated += productIds
            return generateResult
        }
    }

    private data class PrintedJob(
        val printer: PairedPrinter,
        val labels: List<LabelItem>,
        val template: LabelTemplate,
        val size: LabelSize,
    )

    private class FakeLabelPrinting : LabelPrinting {
        var paired: PairedPrintersResult = PairedPrintersResult.Listed(listOf(PRINTER))
        var next: PrintResult = PrintResult.Sent(BYTES)
        var listed = 0
        val printed = mutableListOf<PrintedJob>()

        override fun paired(): PairedPrintersResult {
            listed++
            return paired
        }

        override suspend fun printLabels(
            printer: PairedPrinter,
            labels: List<LabelItem>,
            template: LabelTemplate,
            size: LabelSize,
        ): PrintResult {
            printed += PrintedJob(printer, labels, template, size)
            return next
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-13T15:00:00Z")
        val CALENDAR =
            BusinessCalendar(requireNotNull(BusinessTimeZone.of("Etc/UTC")), DayOfWeek.MONDAY)
        const val CATEGORY_ID = 7L
        const val BYTES = 4_096
        val PRINTER = PairedPrinter(name = "Térmica de prueba", address = "00:11:22:33:44:55")
        val CATEGORY = Category(
            id = CATEGORY_ID,
            details = CategoryDetails(
                name = "Bebidas",
                normalizedName = TextNormalizer.normalize("Bebidas"),
                targetMargin = null,
            ),
            createdAt = NOW,
            updatedAt = NOW,
        )
        val OWN = product(1L, "Té verde", salePrice = 2_500L, barcode = "P00000001").inCategory()
        val FACTORY = product(2L, "Avena 500 g", salePrice = 4_000L, barcode = "2001234567890")
        val NO_PRICE = product(3L, "Bolsa", salePrice = 0L, barcode = "P00000003")
        val NO_CODE = product(4L, "Agua 600 ml", salePrice = 1_500L).inCategory()

        fun Product.inCategory(): Product = copy(details = details.copy(categoryId = CATEGORY_ID))
    }
}
