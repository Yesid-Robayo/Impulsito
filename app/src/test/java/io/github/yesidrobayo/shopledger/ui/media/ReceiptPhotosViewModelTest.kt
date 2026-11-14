package io.github.yesidrobayo.shopledger.ui.media

import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptPhoto
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.document.AttachReceiptResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.RemoveReceiptResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Las fotos del comprobante sin base: agregar recodifica antes de unir, cada falla se dice una vez
 * sin dejar nada a medias, y quitar pide confirmar. Imágenes inventadas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptPhotosViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val photos = MutableStateFlow(listOf(ReceiptPhoto(1L, IMAGE)))
    private var imported: ImageImport = ImageImport.Imported(IMAGE)
    private var attached: AttachReceiptResult = AttachReceiptResult.Attached(2L)
    private var removed: RemoveReceiptResult = RemoveReceiptResult.Removed
    private val sources = mutableListOf<String>()
    private val attachedImages = mutableListOf<StoredImage>()
    private val removedIds = mutableListOf<Long>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = ReceiptPhotosViewModel(
        photos = photos,
        importPhoto = {
            sources += it
            imported
        },
        attach = {
            attachedImages += it
            attached
        },
        remove = {
            removedIds += it
            removed
        },
    )

    @Test
    fun `a picked photo is recoded and then attached`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        assertEquals(listOf(ReceiptPhoto(1L, IMAGE)), model.uiState.value.photos)

        model.onAction(ReceiptPhotosAction.Picked("content://example/9"))
        assertTrue(model.uiState.value.adding)
        advanceUntilIdle()

        assertEquals(listOf("content://example/9"), sources)
        assertEquals(listOf(IMAGE), attachedImages)
        assertFalse(model.uiState.value.adding)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun `each failure is said once and nothing is attached`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        imported = ImageImport.Unreadable
        model.onAction(ReceiptPhotosAction.Picked("a"))
        advanceUntilIdle()
        assertEquals(ReceiptPhotoError.UNREADABLE, model.uiState.value.error)

        imported = ImageImport.OutOfSpace
        model.onAction(ReceiptPhotosAction.Picked("b"))
        advanceUntilIdle()
        assertEquals(ReceiptPhotoError.OUT_OF_SPACE, model.uiState.value.error)
        assertTrue(attachedImages.isEmpty())

        imported = ImageImport.Imported(IMAGE)
        attached = AttachReceiptResult.Failed(StorageFailure.DISK_FULL)
        model.onAction(ReceiptPhotosAction.Picked("c"))
        advanceUntilIdle()
        assertEquals(ReceiptPhotoError.OUT_OF_SPACE, model.uiState.value.error)

        attached = AttachReceiptResult.Failed(StorageFailure.IO_ERROR)
        model.onAction(ReceiptPhotosAction.Picked("d"))
        advanceUntilIdle()
        assertEquals(ReceiptPhotoError.SAVE_FAILED, model.uiState.value.error)

        attached = AttachReceiptResult.DocumentUnavailable
        model.onAction(ReceiptPhotosAction.Picked("e"))
        advanceUntilIdle()
        assertEquals(ReceiptPhotoError.SAVE_FAILED, model.uiState.value.error)

        model.onAction(ReceiptPhotosAction.Choose)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun `removing asks first and closes the photo`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        val photo = ReceiptPhoto(1L, IMAGE)

        model.onAction(ReceiptPhotosAction.Open(photo))
        model.onAction(ReceiptPhotosAction.AskRemove)
        assertTrue(model.uiState.value.confirmRemove)
        model.onAction(ReceiptPhotosAction.CancelRemove)
        assertFalse(model.uiState.value.confirmRemove)
        assertTrue(removedIds.isEmpty())

        removed = RemoveReceiptResult.Failed(StorageFailure.IO_ERROR)
        model.onAction(ReceiptPhotosAction.AskRemove)
        model.onAction(ReceiptPhotosAction.ConfirmRemove)
        advanceUntilIdle()
        assertEquals(ReceiptPhotoError.SAVE_FAILED, model.uiState.value.error)
        assertEquals(photo, model.uiState.value.viewing)

        removed = RemoveReceiptResult.Removed
        model.onAction(ReceiptPhotosAction.ConfirmRemove)
        advanceUntilIdle()
        assertEquals(listOf(1L, 1L), removedIds)
        assertNull(model.uiState.value.viewing)

        model.onAction(ReceiptPhotosAction.Open(photo))
        model.onAction(ReceiptPhotosAction.Close)
        assertNull(model.uiState.value.viewing)
    }

    @Test
    fun `a photo that disappears closes and a read failure shows none`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(ReceiptPhotosAction.Open(ReceiptPhoto(1L, IMAGE)))

        photos.value = emptyList()
        advanceUntilIdle()
        assertNull(model.uiState.value.viewing)

        val failing = ReceiptPhotosViewModel(
            photos = flow { error("Lectura fallida de prueba") },
            importPhoto = { ImageImport.Unreadable },
            attach = { AttachReceiptResult.DocumentUnavailable },
            remove = { RemoveReceiptResult.NotFound },
        )
        advanceUntilIdle()
        assertEquals(emptyList<ReceiptPhoto>(), failing.uiState.value.photos)
    }

    private companion object {
        val IMAGE = StoredImage("c".repeat(64), "image/webp", 1_600, 1_200, 250_000)
    }
}
