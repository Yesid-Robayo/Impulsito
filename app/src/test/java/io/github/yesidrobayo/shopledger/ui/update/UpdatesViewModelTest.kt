package io.github.yesidrobayo.shopledger.ui.update

import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * `Actualizaciones y verificar APK`: lee la app instalada, compara un archivo a la vez y nunca
 * cierra la pantalla por una falla. Versiones y huellas inventadas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val installed = InstalledApp("1.0.0", 3, "3F:9A")
    private val verified = mutableListOf<String>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model(
        installedApp: suspend () -> InstalledApp? = { installed },
        verifyApk: suspend (String) -> ApkCheckResult = { uri ->
            verified += uri
            ApkCheckResult.Match("1.0.1", 4, "ab", older = false)
        },
    ) = UpdatesViewModel(installedApp, verifyApk)

    @Test
    fun `it reads the installed version and fingerprint`() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.uiState.value.installedLoading)

        advanceUntilIdle()

        assertFalse(model.uiState.value.installedLoading)
        assertEquals(installed, model.uiState.value.installed)
    }

    @Test
    fun `a failure reading the installed app leaves it empty without closing`() =
        runTest(dispatcher) {
            val model = model(installedApp = { throw IllegalStateException("sin datos") })

            advanceUntilIdle()

            assertFalse(model.uiState.value.installedLoading)
            assertNull(model.uiState.value.installed)
        }

    @Test
    fun `a chosen file is checked and shows its result`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(UpdatesAction.FileChosen("content://docs/archivo"))
        assertEquals(VerifyUi.Checking, model.uiState.value.verify)
        advanceUntilIdle()

        assertEquals(listOf("content://docs/archivo"), verified)
        assertEquals(
            VerifyUi.Done(ApkCheckResult.Match("1.0.1", 4, "ab", older = false)),
            model.uiState.value.verify,
        )
    }

    @Test
    fun `closing the picker without a file changes nothing`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(UpdatesAction.FileChosen(null))
        advanceUntilIdle()

        assertTrue(verified.isEmpty())
        assertEquals(VerifyUi.Idle, model.uiState.value.verify)
    }

    @Test
    fun `while a file is being checked another one is ignored`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model =
            model(
                verifyApk = { uri ->
                    verified += uri
                    gate.await()
                    ApkCheckResult.NotApk
                },
            )
        advanceUntilIdle()

        model.onAction(UpdatesAction.FileChosen("content://docs/uno"))
        advanceUntilIdle()
        model.onAction(UpdatesAction.FileChosen("content://docs/dos"))
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("content://docs/uno"), verified)
        assertEquals(VerifyUi.Done(ApkCheckResult.NotApk), model.uiState.value.verify)
    }

    @Test
    fun `an unexpected failure while checking says the file could not be read`() =
        runTest(dispatcher) {
            val model = model(verifyApk = { throw IOException("cortado") })
            advanceUntilIdle()

            model.onAction(UpdatesAction.FileChosen("content://docs/archivo"))
            advanceUntilIdle()

            assertEquals(VerifyUi.Done(ApkCheckResult.ReadFailed), model.uiState.value.verify)
        }

    @Test
    fun `back and choosing a file are left to the navigation`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        val before = model.uiState.value

        model.onAction(UpdatesAction.Back)
        model.onAction(UpdatesAction.ChooseFile)

        assertEquals(before, model.uiState.value)
    }
}
