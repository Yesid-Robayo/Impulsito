package io.github.yesidrobayo.shopledger.data.work

import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El estado del respaldo se vuelve a calcular cuando una subida empieza o termina, y no cuando los
 * trabajos cambian sin cambiar lo que importa: encolado, corriendo o ninguno.
 */
class UploadWorkChangesTest {
    @Test
    fun `starting and finishing an upload emits, repeating the same does not`() = runBlocking {
        val upload = MutableStateFlow(emptyList<WorkInfo.State>())
        val unmetered = MutableStateFlow(emptyList<WorkInfo.State>())
        var emissions = 0
        val job = launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
            uploadWorkChanges(listOf(upload, unmetered)).collect { emissions += 1 }
        }

        // Al empezar a mirar, una emisión: así el estado se calcula enseguida.
        assertEquals(1, emissions)
        upload.value = listOf(WorkInfo.State.ENQUEUED)
        assertEquals(2, emissions)
        upload.value = listOf(WorkInfo.State.RUNNING)
        assertEquals(3, emissions)
        // La otra subida encolada no cambia nada mientras una corre.
        unmetered.value = listOf(WorkInfo.State.ENQUEUED)
        assertEquals(3, emissions)
        // Termina la que corría: vuelve a la encolada.
        upload.value = listOf(WorkInfo.State.SUCCEEDED)
        assertEquals(4, emissions)
        unmetered.value = listOf(WorkInfo.State.CANCELLED)
        assertEquals(5, emissions)
        // Terminado y cancelado son lo mismo: ninguna corre ni espera.
        upload.value = listOf(WorkInfo.State.FAILED)
        assertEquals(5, emissions)
        job.cancel()
    }
}
