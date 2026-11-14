package io.github.yesidrobayo.shopledger.ui.receipt

import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptLogoChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateField
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptTemplateInput
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveReceiptTemplateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
 * La plantilla del comprobante sin base ni selector: lo guardado se carga una vez, el largo se avisa
 * al escribir, el logo cambia o se quita, salir con cambios pregunta, guardar avisa una vez y una
 * falla conserva lo escrito.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptTemplateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = MutableStateFlow(
        ReceiptTemplateSource(ReceiptSettings(LOGO_ID, "Calle 8", null, null), "Tienda Central"),
    )
    private val saved = mutableListOf<ReceiptTemplateInput>()
    private var saveResult: SaveReceiptTemplateResult = SaveReceiptTemplateResult.Saved
    private var imported: ImageImport = ImageImport.Imported(image('b'))
    private var failRead = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): ReceiptTemplateViewModel {
        val model = ReceiptTemplateViewModel(
            observeSource = {
                if (failRead) flow { error("Falla inventada") } else source
            },
            loadImage = { id -> image('a').takeIf { id == LOGO_ID } },
            importLogo = { imported },
            save = {
                saved += it
                saveResult
            },
        )
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun ReceiptTemplateViewModel.editing() = uiState.value as ReceiptTemplateUiState.Editing

    @Test
    fun `it loads what is saved, with the business name, and nothing changed`() =
        runTest(dispatcher) {
            val editing = model().editing()

            assertEquals("Tienda Central", editing.businessName)
            assertEquals(image('a'), editing.logo)
            assertEquals("Calle 8", editing.details)
            assertEquals("", editing.footer)
            assertFalse(editing.changed)
        }

    @Test
    fun `a failed read can be retried`() = runTest(dispatcher) {
        failRead = true
        val model = model()
        assertEquals(ReceiptTemplateUiState.ReadFailed, model.uiState.value)

        failRead = false
        model.onAction(ReceiptTemplateAction.Retry)
        advanceUntilIdle()

        assertTrue(model.uiState.value is ReceiptTemplateUiState.Editing)
    }

    @Test
    fun `a text over 300 characters is flagged while typing and is not saved`() =
        runTest(dispatcher) {
            val model = model()

            model.onAction(ReceiptTemplateAction.FooterChanged("a".repeat(301)))
            advanceUntilIdle()
            model.onAction(ReceiptTemplateAction.Save)
            advanceUntilIdle()

            assertEquals(setOf(ReceiptTemplateField.FOOTER), model.editing().tooLong)
            assertTrue(saved.isEmpty())
            model.onAction(ReceiptTemplateAction.FooterChanged("  " + "a".repeat(300) + " "))
            advanceUntilIdle()
            assertEquals(emptySet<ReceiptTemplateField>(), model.editing().tooLong)
        }

    @Test
    fun `a new logo replaces the old one, and removing it leaves none`() = runTest(dispatcher) {
        val model = model()

        model.onAction(ReceiptTemplateAction.PickLogo("content://logo"))
        advanceUntilIdle()
        assertEquals(image('b'), model.editing().logo)
        assertTrue(model.editing().changed)

        model.onAction(ReceiptTemplateAction.Save)
        advanceUntilIdle()
        assertEquals(ReceiptLogoChange.Use(image('b')), saved.single().logo)
        assertFalse(model.editing().changed)

        model.onAction(ReceiptTemplateAction.RemoveLogo)
        advanceUntilIdle()
        assertNull(model.editing().logo)
        assertTrue(model.editing().changed)
    }

    @Test
    fun `an unusable image keeps the previous logo and says why`() = runTest(dispatcher) {
        imported = ImageImport.Unreadable
        val model = model()

        model.onAction(ReceiptTemplateAction.PickLogo("content://logo"))
        advanceUntilIdle()
        assertEquals(image('a'), model.editing().logo)
        assertEquals(LogoProblem.UNREADABLE, model.editing().logoProblem)

        imported = ImageImport.OutOfSpace
        model.onAction(ReceiptTemplateAction.PickLogo("content://logo"))
        advanceUntilIdle()
        assertEquals(LogoProblem.OUT_OF_SPACE, model.editing().logoProblem)
    }

    @Test
    fun `saving says so once and the saved texts become the base`() = runTest(dispatcher) {
        val model = model()

        model.onAction(ReceiptTemplateAction.DetailsChanged(" Calle 9 "))
        advanceUntilIdle()
        model.onAction(ReceiptTemplateAction.Save)
        advanceUntilIdle()

        assertEquals(ReceiptTemplateInput(ReceiptLogoChange.Keep, " Calle 9 ", ""), saved.single())
        assertTrue(model.editing().saved)
        assertFalse(model.editing().changed)
        model.onAction(ReceiptTemplateAction.SavedShown)
        advanceUntilIdle()
        assertFalse(model.editing().saved)
    }

    @Test
    fun `a failed save keeps what was written`() = runTest(dispatcher) {
        saveResult = SaveReceiptTemplateResult.Failed(StorageFailure.DISK_FULL)
        val model = model()

        model.onAction(ReceiptTemplateAction.FooterChanged("Gracias"))
        advanceUntilIdle()
        model.onAction(ReceiptTemplateAction.Save)
        advanceUntilIdle()

        assertTrue(model.editing().saveFailed)
        assertEquals("Gracias", model.editing().footer)
        assertTrue(model.editing().changed)
        model.onAction(ReceiptTemplateAction.FooterChanged("Gracias!"))
        advanceUntilIdle()
        assertFalse(model.editing().saveFailed)
    }

    @Test
    fun `leaving with changes asks first, and without them just leaves`() = runTest(dispatcher) {
        val model = model()

        model.onAction(ReceiptTemplateAction.Leave)
        advanceUntilIdle()
        assertTrue(model.editing().left)

        val other = model()
        other.onAction(ReceiptTemplateAction.DetailsChanged("Otra"))
        advanceUntilIdle()
        other.onAction(ReceiptTemplateAction.Leave)
        advanceUntilIdle()
        assertTrue(other.editing().confirmLeave)
        assertFalse(other.editing().left)
        other.onAction(ReceiptTemplateAction.DismissLeave)
        advanceUntilIdle()
        assertFalse(other.editing().confirmLeave)
        other.onAction(ReceiptTemplateAction.Leave)
        advanceUntilIdle()
        other.onAction(ReceiptTemplateAction.ConfirmLeave)
        advanceUntilIdle()
        assertTrue(other.editing().left)
    }

    @Test
    fun `the business name follows the settings`() = runTest(dispatcher) {
        val model = model()

        source.value = source.value.copy(businessName = "  ")
        advanceUntilIdle()

        assertNull(model.editing().businessName)
    }

    private fun image(digit: Char) = StoredImage(
        sha256 = digit.toString().repeat(64),
        mimeType = "image/webp",
        widthPx = 640,
        heightPx = 320,
        sizeBytes = 20_000,
    )

    private companion object {
        const val LOGO_ID = 9L
    }
}
