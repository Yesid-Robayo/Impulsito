package io.github.yesidrobayo.shopledger.ui.localcopies

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.FullCopyExport
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoBackupFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoExport
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Before
import org.junit.Test

/**
 * `Exportar fotos` en `Copias locales`, sin disco ni selector: cuántas hay, el nombre sugerido con
 * la fecha del negocio y cada resultado con lo que queda a la vista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-12T03:07:00Z")
    private val exported = mutableListOf<String>()
    private var exportable: Int? = 4
    private var result: PhotoExport = PhotoExport.Saved(4)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): LocalCopiesViewModel {
        val model =
            LocalCopiesViewModel(
                export = { FullCopyExport.Failed },
                encrypted = { false },
                zone = { BusinessTimeZone.of("Etc/GMT+5") },
                now = { now },
                computation = dispatcher,
                photoFacts = {
                    exportable?.let {
                        PhotoBackupFacts(PhotoDestination.WEBDAV, 0, 0, false, 0, it)
                    } ?: error("Falla inventada")
                },
                exportPhotos = { uri ->
                    exported += uri
                    result
                },
            )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `the section knows how many photos there are, and none if it cannot read`() =
        runTest(dispatcher) {
            assertEquals(4, viewModel().uiState.value.photos.exportable)

            exportable = null
            assertEquals(0, viewModel().uiState.value.photos.exportable)
        }

    @Test
    fun `the suggested name carries the business date`() = runTest(dispatcher) {
        assertEquals("media-20261111.zip", viewModel().suggestedPhotosName())
    }

    @Test
    fun `saved photos say so briefly`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(LocalCopiesAction.PhotosFileCreated("content://prueba/documento/1"))
        advanceUntilIdle()

        assertEquals(listOf("content://prueba/documento/1"), exported)
        assertEquals(LocalCopiesMessage.PHOTOS_SAVED, model.uiState.value.message)
        assertFalse(model.uiState.value.photos.working)
        assertNull(model.uiState.value.photos.failure)
    }

    @Test
    fun `each failure stays in view until it is closed`() = runTest(dispatcher) {
        val model = viewModel()

        result = PhotoExport.KeyUnavailable
        model.onAction(LocalCopiesAction.PhotosFileCreated("content://prueba/documento/1"))
        advanceUntilIdle()
        assertEquals(PhotosFailure.KEY_MISSING, model.uiState.value.photos.failure)

        result = PhotoExport.Failed
        model.onAction(LocalCopiesAction.PhotosFileCreated("content://prueba/documento/2"))
        advanceUntilIdle()
        assertEquals(PhotosFailure.OTHER, model.uiState.value.photos.failure)

        model.onAction(LocalCopiesAction.DismissPhotosFailure)
        assertNull(model.uiState.value.photos.failure)

        result = PhotoExport.NoPhotos
        model.onAction(LocalCopiesAction.PhotosFileCreated("content://prueba/documento/3"))
        advanceUntilIdle()
        assertEquals(0, model.uiState.value.photos.exportable)
        assertNull(model.uiState.value.message)
    }
}
