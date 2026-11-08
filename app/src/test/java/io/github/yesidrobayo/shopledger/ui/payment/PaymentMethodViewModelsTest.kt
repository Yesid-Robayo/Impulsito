package io.github.yesidrobayo.shopledger.ui.payment

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * Lo que hacen la lista y el formulario de métodos de pago con lo que la persona escribe y toca. Los
 * casos de uso son los reales, sobre una base en memoria.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentMethodViewModelsTest {
    private val base = FakePaymentMethods()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        base.seedInitial()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theListShowsTheSixInitialMethodsInOrderAndNoArchived() = runTest {
        val list = base.listViewModel()

        val ready = list.uiState.value as PaymentMethodsUiState.Ready
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), ready.active.map { it.id })
        assertTrue(ready.archived.isEmpty())
        assertFalse(ready.archivedExpanded)
    }

    @Test
    fun sortingMovesRowsAndSavesTheWholeOrder() = runTest {
        val list = base.listViewModel()

        list.onAction(PaymentMethodsAction.StartSorting)
        list.onAction(PaymentMethodsAction.Move(from = 5, to = 0))
        list.onAction(PaymentMethodsAction.Move(from = 1, to = 2))
        list.onAction(PaymentMethodsAction.SaveOrder)

        val ready = list.uiState.value as PaymentMethodsUiState.Ready
        assertNull(ready.sorting)
        assertEquals(listOf(6L, 2L, 1L, 3L, 4L, 5L), ready.active.map { it.id })
    }

    @Test
    fun aListThatChangedWhileSortingStartsAgain() = runTest {
        val list = base.listViewModel()
        list.onAction(PaymentMethodsAction.StartSorting)
        list.onAction(PaymentMethodsAction.Move(from = 0, to = 1))
        // Mientras se ordena, otro cambio archiva un método.
        base.put(requireNotNull(base.methods.value[3L]).copy(archivedAt = AT))

        list.onAction(PaymentMethodsAction.SaveOrder)

        val sorting = requireNotNull((list.uiState.value as PaymentMethodsUiState.Ready).sorting)
        assertEquals(SortingStatus.ListChanged, sorting.status)
        assertEquals(listOf(1L, 2L, 4L, 5L, 6L), sorting.order.map { it.id })
    }

    @Test
    fun aFullDiskKeepsTheOrderBeingBuilt() = runTest {
        val list = base.listViewModel()
        list.onAction(PaymentMethodsAction.StartSorting)
        list.onAction(PaymentMethodsAction.Move(from = 0, to = 5))
        base.failure = StorageFailure.DISK_FULL

        list.onAction(PaymentMethodsAction.SaveOrder)

        val sorting = requireNotNull((list.uiState.value as PaymentMethodsUiState.Ready).sorting)
        assertEquals(SortingStatus.Failed(SaveProblem.OUT_OF_SPACE), sorting.status)
        assertEquals(1L, sorting.order.last().id)
    }

    @Test
    fun movingOutOfRangeChangesNothing() {
        val order = base.methods.value.values.toList()

        assertEquals(order, PaymentMethodsViewModel.moved(order, -1, 2))
        assertEquals(order, PaymentMethodsViewModel.moved(order, 0, 6))
        assertEquals(order, PaymentMethodsViewModel.moved(order, 3, 3))
    }

    @Test
    fun aNewMethodIsReadWithTheRegionalFormatAndSaved() = runTest {
        val form = PaymentMethodFormViewModel(null, base.formUseCases(), NUMBERS)
        form.onAction(PaymentMethodFormAction.NameChanged("Billetera del barrio"))
        form.onAction(PaymentMethodFormAction.KindChanged(PaymentMethodKind.WALLET))
        form.onAction(PaymentMethodFormAction.FeeRateChanged("2,75"))
        form.onAction(PaymentMethodFormAction.FeeFixedChanged("1,5"))
        form.onAction(PaymentMethodFormAction.SettlementDaysChanged("2"))
        form.onAction(PaymentMethodFormAction.PriceAdjustmentChanged("-5"))

        form.onAction(PaymentMethodFormAction.Save)

        assertTrue((form.uiState.value as PaymentMethodFormUiState.Editing).finished)
        val saved = base.methods.value.values.single { it.systemKey == null }.details
        assertEquals(BasisPoints(275), saved.feeRate)
        assertEquals(Money(150), saved.feeFixed)
        assertEquals(2, saved.settlementDays)
        assertEquals(BasisPoints(-500), saved.priceAdjustment)
    }

    @Test
    fun fieldErrorsKeepWhatWasWritten() = runTest {
        val form = PaymentMethodFormViewModel(null, base.formUseCases(), NUMBERS)
        form.onAction(PaymentMethodFormAction.FeeRateChanged("101"))
        form.onAction(PaymentMethodFormAction.SettlementDaysChanged("3651"))
        form.onAction(PaymentMethodFormAction.PriceAdjustmentChanged("-100,01"))

        form.onAction(PaymentMethodFormAction.Save)

        val editing = form.uiState.value as PaymentMethodFormUiState.Editing
        assertEquals(
            mapOf(
                FormField.FEE_RATE to FieldProblem.FEE_RATE_RANGE,
                FormField.SETTLEMENT_DAYS to FieldProblem.SETTLEMENT_TOO_HIGH,
                FormField.PRICE_ADJUSTMENT to FieldProblem.PRICE_ADJUSTMENT_RANGE,
            ),
            editing.errors,
        )
        assertEquals("101", editing.form.feeRate)
        assertFalse(editing.finished)
        // Al corregir un campo, su error se va.
        form.onAction(PaymentMethodFormAction.FeeRateChanged("10"))
        val fixed = form.uiState.value as PaymentMethodFormUiState.Editing
        assertNull(fixed.errors[FormField.FEE_RATE])
    }

    @Test
    fun theNameErrorsComeFromTheUseCase() = runTest {
        val form = PaymentMethodFormViewModel(null, base.formUseCases(), NUMBERS)

        form.onAction(PaymentMethodFormAction.Save)
        assertEquals(
            FieldProblem.NAME_BLANK,
            (form.uiState.value as PaymentMethodFormUiState.Editing).errors[FormField.NAME],
        )

        base.put(
            requireNotNull(base.methods.value[2L]).copy(
                details = FakePaymentMethods.details("Tarjeta", PaymentMethodKind.CARD),
            ),
        )
        form.onAction(PaymentMethodFormAction.NameChanged("Tarjeta"))
        form.onAction(PaymentMethodFormAction.Save)
        assertEquals(
            FieldProblem.NAME_TAKEN,
            (form.uiState.value as PaymentMethodFormUiState.Editing).errors[FormField.NAME],
        )
    }

    @Test
    fun onAccountIgnoresTheHiddenFeeFields() = runTest {
        val form = PaymentMethodFormViewModel(null, base.formUseCases(), NUMBERS)
        form.onAction(PaymentMethodFormAction.NameChanged("Fiado del barrio"))
        form.onAction(PaymentMethodFormAction.FeeRateChanged("150"))
        form.onAction(PaymentMethodFormAction.KindChanged(PaymentMethodKind.ON_ACCOUNT))

        form.onAction(PaymentMethodFormAction.Save)

        assertTrue((form.uiState.value as PaymentMethodFormUiState.Editing).finished)
        val saved = base.methods.value.values.single { it.systemKey == null }.details
        assertEquals(BasisPoints.ZERO, saved.feeRate)
    }

    @Test
    fun anInitialMethodKeepsItsTranslatedNameUntilTouched() = runTest {
        val form = PaymentMethodFormViewModel(2L, base.formUseCases(), NUMBERS)
        val editing = form.uiState.value as PaymentMethodFormUiState.Editing
        assertEquals("CARD", editing.systemKey)
        assertFalse(editing.form.nameEdited)
        // Los demás iniciales sin nombre propio sirven para avisar de un nombre repetido.
        assertEquals(
            setOf("CASH", "TRANSFER", "WALLET", "ON_ACCOUNT", "OTHER"),
            editing.initialMethods.keys,
        )

        form.onAction(PaymentMethodFormAction.FeeRateChanged("3"))
        form.onAction(PaymentMethodFormAction.Save)

        val saved = requireNotNull(base.methods.value[2L]).details
        assertNull(saved.name)
        assertEquals(BasisPoints(300), saved.feeRate)
    }

    @Test
    fun aSavedMethodOpensWithItsNumbersInTheRegionalFormat() = runTest {
        base.put(
            requireNotNull(base.methods.value[4L]).copy(
                details = FakePaymentMethods.details(
                    "Billetera",
                    PaymentMethodKind.WALLET,
                    250,
                    1_005,
                    3,
                ),
            ),
        )

        val form = PaymentMethodFormViewModel(4L, base.formUseCases(), NUMBERS).uiState.value

        val fields = (form as PaymentMethodFormUiState.Editing).form
        assertEquals("2,5", fields.feeRate)
        assertEquals("10,05", fields.feeFixed)
        assertEquals("3", fields.settlementDays)
        assertEquals("", fields.priceAdjustment)
        assertTrue(fields.nameEdited)
    }

    @Test
    fun theKindOfAMethodWithSalesDoesNotChange() = runTest {
        base.withSales += 2L
        val form = PaymentMethodFormViewModel(2L, base.formUseCases(), NUMBERS)

        form.onAction(PaymentMethodFormAction.KindChanged(PaymentMethodKind.OTHER))

        val editing = form.uiState.value as PaymentMethodFormUiState.Editing
        assertEquals(PaymentMethodKind.CARD, editing.form.kind)
        assertEquals(true, editing.options?.isKindLocked)
        assertEquals(false, editing.options?.canDelete)
    }

    @Test
    fun archivingAsksFirstAndTheLastActiveIsNotArchived() = runTest {
        val form = PaymentMethodFormViewModel(3L, base.formUseCases(), NUMBERS)
        form.onAction(PaymentMethodFormAction.AskArchive)
        assertEquals(
            FormDialog.ARCHIVE,
            (form.uiState.value as PaymentMethodFormUiState.Editing).dialog,
        )

        form.onAction(PaymentMethodFormAction.ConfirmDialog)

        assertTrue((form.uiState.value as PaymentMethodFormUiState.Editing).finished)
        assertTrue(requireNotNull(base.methods.value[3L]).isArchived)

        for (id in listOf(1L, 2L, 4L, 5L)) {
            base.put(requireNotNull(base.methods.value[id]).copy(archivedAt = AT))
        }
        val last = PaymentMethodFormViewModel(6L, base.formUseCases(), NUMBERS)
        last.onAction(PaymentMethodFormAction.AskArchive)
        val editing = last.uiState.value as PaymentMethodFormUiState.Editing
        assertNull(editing.dialog)
        assertEquals(FormNotice.LastActive, editing.notice)
    }

    @Test
    fun aMissingMethodSaysSo() = runTest {
        val form = PaymentMethodFormViewModel(99L, base.formUseCases(), NUMBERS)

        assertEquals(PaymentMethodFormUiState.NotFound, form.uiState.value)
    }

    @Test
    fun aStorageFailureKeepsTheFormAndSaysWhy() = runTest {
        val form = PaymentMethodFormViewModel(null, base.formUseCases(), NUMBERS)
        form.onAction(PaymentMethodFormAction.NameChanged("Vale"))
        base.failure = StorageFailure.IO_ERROR

        form.onAction(PaymentMethodFormAction.Save)

        val editing = form.uiState.value as PaymentMethodFormUiState.Editing
        assertEquals(FormNotice.SaveFailed(SaveProblem.OTHER), editing.notice)
        assertEquals("Vale", editing.form.name)
        assertFalse(editing.working)
    }

    @Test
    fun aPickedImageIsAssignedOnSaveAndRemovingItReleasesIt() = runTest {
        val qr = StoredImage("ab".repeat(32), "image/webp", 640, 640, 30_000)
        base.nextImport = ImageImport.Imported(qr)
        val form = PaymentMethodFormViewModel(3L, base.formUseCases(), NUMBERS)

        form.onAction(PaymentMethodFormAction.PickImage("content://picker/1"))
        val picked = form.uiState.value as PaymentMethodFormUiState.Editing
        assertEquals(FormImage.Picked(qr), picked.form.image)
        form.onAction(PaymentMethodFormAction.Save)

        val imageId = requireNotNull(base.methods.value[3L]?.details?.instructionsMediaId)
        assertEquals(qr, base.images[imageId])
        // Abierto otra vez, la muestra como guardada; quitarla y guardar la deja sin uso.
        val again = PaymentMethodFormViewModel(3L, base.formUseCases(), NUMBERS)
        val saved = again.uiState.value as PaymentMethodFormUiState.Editing
        assertEquals(FormImage.Saved(qr), saved.form.image)
        again.onAction(PaymentMethodFormAction.RemoveImage)
        again.onAction(PaymentMethodFormAction.Save)
        assertNull(base.methods.value[3L]?.details?.instructionsMediaId)
        assertTrue(imageId in base.orphaned)
    }

    @Test
    fun anUnreadableImageChangesNothingAndSaysSo() = runTest {
        val form = PaymentMethodFormViewModel(3L, base.formUseCases(), NUMBERS)
        base.nextImport = ImageImport.Unreadable

        form.onAction(PaymentMethodFormAction.PickImage("content://picker/2"))

        val editing = form.uiState.value as PaymentMethodFormUiState.Editing
        assertEquals(FormImage.None, editing.form.image)
        assertEquals(ImageProblem.UNREADABLE, editing.imageProblem)
        base.nextImport = ImageImport.OutOfSpace
        form.onAction(PaymentMethodFormAction.PickImage("content://picker/3"))
        assertEquals(
            ImageProblem.OUT_OF_SPACE,
            (form.uiState.value as PaymentMethodFormUiState.Editing).imageProblem,
        )
    }

    @Test
    fun theListFindsTheImageOfEachMethod() = runTest {
        val qr = StoredImage("cd".repeat(32), "image/webp", 640, 640, 30_000)
        base.images[100L] = qr
        val card = requireNotNull(base.methods.value[2L])
        base.put(card.copy(details = card.details.copy(instructionsMediaId = 100L)))

        val ready = base.listViewModel().uiState.value as PaymentMethodsUiState.Ready

        assertEquals(mapOf(2L to qr), ready.images)
    }

    @Test
    fun plainNumbersUseTheRegionSeparatorsWithoutGroups() {
        assertEquals("2,75", plainNumberText(275, 2, COMMA))
        assertEquals("2,5", plainNumberText(250, 2, COMMA))
        assertEquals("2", plainNumberText(200, 2, COMMA))
        assertEquals("0,05", plainNumberText(5, 2, COMMA))
        assertEquals("-100", plainNumberText(-10_000, 2, COMMA))
        assertEquals("1234567", plainNumberText(1_234_567, 0, COMMA))
        assertEquals("1,234", plainNumberText(1_234, 3, COMMA))
    }
}
