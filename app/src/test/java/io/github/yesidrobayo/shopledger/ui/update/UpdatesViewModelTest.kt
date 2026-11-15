package io.github.yesidrobayo.shopledger.ui.update

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
 * `Actualizaciones y verificar APK`: lee la app instalada, busca una versión nueva solo con la
 * búsqueda permitida y una vez a la vez, compara un archivo a la vez con el aviso leído, y nunca
 * cierra la pantalla por una falla. Versiones, huellas y fechas inventadas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val installed = InstalledApp("1.0.0", 3, "3F:9A")
    private val verified = mutableListOf<String>()
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val enabled = MutableStateFlow(true)
    private val saved = mutableListOf<Boolean>()
    private var saveWorks = true
    private var searches = 0
    private var outcome: UpdateCheckOutcome = UpdateCheckOutcome.Latest(HASH)
    private var lastSearch: Instant? = null
    private val zone = MutableStateFlow<BusinessTimeZone?>(BusinessTimeZone.of("America/Bogota"))

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
        searchEnabled: Flow<Boolean> = enabled,
        checkForUpdate: suspend () -> UpdateCheckOutcome = {
            searches++
            lastSearch = now
            outcome
        },
    ) = UpdatesViewModel(
        installedApp = installedApp,
        verifyApk = verifyApk,
        searchEnabled = searchEnabled,
        setSearchEnabled = { value ->
            saved += value
            if (saveWorks) enabled.value = value
            saveWorks
        },
        checkForUpdate = checkForUpdate,
        lastSearchAt = { lastSearch },
        zone = zone,
        now = { now },
    )

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
    fun `it reads whether the search is allowed, the last search and the zone`() =
        runTest(dispatcher) {
            lastSearch = Instant.parse("2026-10-28T15:00:00Z")
            val model = model()

            advanceUntilIdle()

            val state = model.uiState.value
            assertEquals(true, state.searchEnabled)
            assertEquals(Instant.parse("2026-10-28T15:00:00Z"), state.lastSearchAt)
            assertEquals(BusinessTimeZone.of("America/Bogota"), state.zone)
            assertEquals(now, state.now)
            assertEquals(SearchUi.Idle, state.search)
            assertEquals(0, searches)
        }

    @Test
    fun `settings that cannot be read offer nothing of the search`() = runTest(dispatcher) {
        val model = model(searchEnabled = flow<Boolean> { throw IOException("sin base") })

        advanceUntilIdle()
        model.onAction(UpdatesAction.Search)
        advanceUntilIdle()

        assertNull(model.uiState.value.searchEnabled)
        assertEquals(0, searches)
    }

    @Test
    fun `without a business zone the dates are read in UTC`() = runTest(dispatcher) {
        zone.value = null
        val model = model()

        advanceUntilIdle()

        assertEquals(UpdatesUiState.UTC, model.uiState.value.zone)
    }

    @Test
    fun `a search shows its result and the new last search`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(UpdatesAction.Search)
        assertEquals(SearchUi.Searching, model.uiState.value.search)
        advanceUntilIdle()

        assertEquals(1, searches)
        assertEquals(SearchUi.Done(UpdateCheckOutcome.Latest(HASH)), model.uiState.value.search)
        assertEquals(now, model.uiState.value.lastSearchAt)
    }

    @Test
    fun `while searching another tap does not search again`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model =
            model(
                checkForUpdate = {
                    searches++
                    gate.await()
                    UpdateCheckOutcome.Offline
                },
            )
        advanceUntilIdle()

        model.onAction(UpdatesAction.Search)
        advanceUntilIdle()
        model.onAction(UpdatesAction.Search)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, searches)
        assertEquals(SearchUi.Done(UpdateCheckOutcome.Offline), model.uiState.value.search)
    }

    @Test
    fun `with the search turned off nothing is checked`() = runTest(dispatcher) {
        enabled.value = false
        val model = model()
        advanceUntilIdle()

        model.onAction(UpdatesAction.Search)
        advanceUntilIdle()

        assertEquals(0, searches)
        assertEquals(SearchUi.Idle, model.uiState.value.search)
    }

    @Test
    fun `an unexpected failure while searching says the notice could not be read`() =
        runTest(dispatcher) {
            val model = model(checkForUpdate = { throw IllegalStateException("roto") })
            advanceUntilIdle()

            model.onAction(UpdatesAction.Search)
            advanceUntilIdle()

            assertEquals(
                SearchUi.Done(UpdateCheckOutcome.Unreadable),
                model.uiState.value.search,
            )
        }

    @Test
    fun `turning the search off saves it and clears the result`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(UpdatesAction.Search)
        advanceUntilIdle()

        model.onAction(UpdatesAction.SetSearchEnabled(false))
        advanceUntilIdle()

        assertEquals(listOf(false), saved)
        assertEquals(false, model.uiState.value.searchEnabled)
        assertEquals(SearchUi.Idle, model.uiState.value.search)
        assertFalse(model.uiState.value.saveFailed)
    }

    @Test
    fun `turning it on again keeps the screen as it was`() = runTest(dispatcher) {
        enabled.value = false
        val model = model()
        advanceUntilIdle()

        model.onAction(UpdatesAction.SetSearchEnabled(true))
        advanceUntilIdle()

        assertEquals(listOf(true), saved)
        assertEquals(true, model.uiState.value.searchEnabled)
        assertEquals(SearchUi.Idle, model.uiState.value.search)
    }

    @Test
    fun `a switch that was not saved says so once`() = runTest(dispatcher) {
        saveWorks = false
        val model = model()
        advanceUntilIdle()

        model.onAction(UpdatesAction.SetSearchEnabled(false))
        advanceUntilIdle()
        assertTrue(model.uiState.value.saveFailed)
        assertEquals(true, model.uiState.value.searchEnabled)

        model.onAction(UpdatesAction.MessageShown)
        assertFalse(model.uiState.value.saveFailed)
    }

    @Test
    fun `a file with the hash of the notice read in this visit says it matches`() =
        runTest(dispatcher) {
            outcome = UpdateCheckOutcome.NewVersion("1.0.1", now, HASH, important = false)
            val model =
                model(verifyApk = { ApkCheckResult.Match("1.0.1", 4, HASH.uppercase(), false) })
            advanceUntilIdle()
            model.onAction(UpdatesAction.Search)
            advanceUntilIdle()

            model.onAction(UpdatesAction.FileChosen("content://docs/archivo"))
            advanceUntilIdle()

            assertEquals(
                VerifyUi.Done(
                    ApkCheckResult.Match("1.0.1", 4, HASH.uppercase(), false),
                    manifestMatch = true,
                ),
                model.uiState.value.verify,
            )
        }

    @Test
    fun `without a notice, with another hash or another signature nothing matches`() =
        runTest(dispatcher) {
            val other = ApkCheckResult.Match("1.0.1", 4, "cd".repeat(32), false)
            var result: ApkCheckResult = other
            val model = model(verifyApk = { result })
            advanceUntilIdle()

            // Sin búsqueda en esta visita no hay aviso con qué comparar.
            model.onAction(UpdatesAction.FileChosen("content://docs/uno"))
            advanceUntilIdle()
            assertEquals(VerifyUi.Done(other), model.uiState.value.verify)

            model.onAction(UpdatesAction.Search)
            advanceUntilIdle()
            model.onAction(UpdatesAction.FileChosen("content://docs/dos"))
            advanceUntilIdle()
            assertEquals(VerifyUi.Done(other), model.uiState.value.verify)

            result = ApkCheckResult.Mismatch(HASH)
            model.onAction(UpdatesAction.FileChosen("content://docs/tres"))
            advanceUntilIdle()
            assertEquals(VerifyUi.Done(ApkCheckResult.Mismatch(HASH)), model.uiState.value.verify)
        }

    @Test
    fun `a notice ignored for its age or a failed search leave nothing to compare`() =
        runTest(dispatcher) {
            outcome = UpdateCheckOutcome.Latest(null)
            val match = ApkCheckResult.Match("1.0.1", 4, HASH, false)
            val model = model(verifyApk = { match })
            advanceUntilIdle()
            model.onAction(UpdatesAction.Search)
            advanceUntilIdle()
            outcome = UpdateCheckOutcome.BadSignature
            model.onAction(UpdatesAction.Search)
            advanceUntilIdle()

            model.onAction(UpdatesAction.FileChosen("content://docs/archivo"))
            advanceUntilIdle()

            assertEquals(VerifyUi.Done(match), model.uiState.value.verify)
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

    private companion object {
        val HASH = "ab".repeat(32)
    }
}
