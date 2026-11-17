package io.github.yesidrobayo.shopledger.ui.update

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.update.ApkDownloadResult
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.ui.help.HelpBlock
import io.github.yesidrobayo.shopledger.ui.help.HelpSpan
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
 * `Actualizaciones y verificar APK`: lee la app instalada, busca una versión nueva solo con la
 * búsqueda permitida y una vez a la vez, compara un archivo a la vez con el aviso leído, y nunca
 * cierra la pantalla por una falla. `Actualizar` solo se ofrece con una versión nueva, baja con su
 * progreso, se cancela, y entrega al instalador solo un archivo con la huella del aviso, la firma
 * de la app y una versión más nueva; lo demás se borra. `Novedades` se lee sin red. Versiones,
 * huellas y fechas inventadas.
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
    private val downloadedNames = mutableListOf<String>()
    private var download: suspend ((Long, Long?) -> Unit) -> ApkDownloadResult = { progress ->
        progress(512, 1024)
        progress(1024, 1024)
        ApkDownloadResult.Downloaded(HASH, 1024)
    }
    private var checks = 0
    private var check: ApkCheckResult = ApkCheckResult.Match("1.0.1", 4, HASH, older = false)
    private var canInstall = true
    private var discards = 0
    private var whatsNew: List<HelpBlock>? = listOf(NEWS_HEADING)

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
        downloadApk = { name, progress ->
            downloadedNames += name
            download(progress)
        },
        checkDownloadedApk = { sha256 ->
            checks++
            assertEquals(HASH, sha256)
            check
        },
        canInstallPackages = { canInstall },
        discardDownload = { discards++ },
        leaveScope = CoroutineScope(dispatcher),
        whatsNew = { whatsNew },
    )

    /** Un modelo que ya buscó y encontró la versión `1.0.1`, con la huella [HASH]. */
    private fun TestScope.withNewVersion(): UpdatesViewModel {
        outcome = UpdateCheckOutcome.NewVersion("1.0.1", now, HASH, important = false)
        val model = model()
        advanceUntilIdle()
        model.onAction(UpdatesAction.Search)
        advanceUntilIdle()
        return model
    }

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

    @Test
    fun `the update is offered only after a search that found a new version`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(UpdatesAction.StartUpdate)
            advanceUntilIdle()
            assertEquals(InstallUi.Idle, model.uiState.value.install)

            model.onAction(UpdatesAction.Search)
            advanceUntilIdle()
            model.onAction(UpdatesAction.StartUpdate)
            advanceUntilIdle()

            assertEquals(InstallUi.Idle, model.uiState.value.install)
            assertTrue(downloadedNames.isEmpty())
        }

    @Test
    fun `a download shows its progress, checks the file and hands it to the installer`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            download = { progress ->
                progress(512, 1024)
                gate.await()
                ApkDownloadResult.Downloaded(HASH, 1024)
            }
            val model = withNewVersion()

            model.onAction(UpdatesAction.StartUpdate)
            advanceUntilIdle()
            assertEquals(InstallUi.Downloading(512, 1024), model.uiState.value.install)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("1.0.1"), downloadedNames)
            assertEquals(1, checks)
            assertEquals(InstallUi.Opening, model.uiState.value.install)
            model.onAction(UpdatesAction.InstallerOpened(true))
            assertEquals(InstallUi.HandedToInstaller, model.uiState.value.install)
            assertEquals(0, discards)
        }

    @Test
    fun `while downloading another tap does not download or search again`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        download = {
            gate.await()
            ApkDownloadResult.Downloaded(HASH, 1024)
        }
        val model = withNewVersion()

        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()
        model.onAction(UpdatesAction.StartUpdate)
        model.onAction(UpdatesAction.Search)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("1.0.1"), downloadedNames)
        assertEquals(1, searches)
        assertEquals(InstallUi.Opening, model.uiState.value.install)
    }

    @Test
    fun `cancelling the download stops it and deletes the file`() = runTest(dispatcher) {
        var stopped = false
        download = { progress ->
            progress(100, 1024)
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val model = withNewVersion()
        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()

        model.onAction(UpdatesAction.CancelUpdate)
        assertEquals(InstallUi.Idle, model.uiState.value.install)
        advanceUntilIdle()

        assertTrue(stopped)
        assertEquals(1, discards)
        assertEquals(0, checks)
        assertEquals(InstallUi.Idle, model.uiState.value.install)
    }

    @Test
    fun `a file that does not match the signed notice is deleted and never checked`() =
        runTest(dispatcher) {
            download = { ApkDownloadResult.Downloaded("cd".repeat(32), 1024) }
            val model = withNewVersion()

            model.onAction(UpdatesAction.StartUpdate)
            advanceUntilIdle()

            assertEquals(
                InstallUi.Failed(InstallFailure.HASH_MISMATCH),
                model.uiState.value.install,
            )
            assertEquals(0, checks)
            assertEquals(1, discards)
        }

    @Test
    fun `the hash must be the signed one exactly, in lowercase`() = runTest(dispatcher) {
        download = { ApkDownloadResult.Downloaded(HASH.uppercase(), 1024) }
        val model = withNewVersion()

        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()

        assertEquals(InstallUi.Failed(InstallFailure.HASH_MISMATCH), model.uiState.value.install)
    }

    @Test
    fun `a file with another signature is deleted and never installed`() = runTest(dispatcher) {
        check = ApkCheckResult.Mismatch(HASH)
        val model = withNewVersion()

        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()

        assertEquals(
            InstallUi.Failed(InstallFailure.WRONG_SIGNATURE),
            model.uiState.value.install,
        )
        assertEquals(1, discards)
    }

    @Test
    fun `a file that is not newer than the installed app is deleted`() = runTest(dispatcher) {
        // La instalada es la compilación 3.
        check = ApkCheckResult.Match("1.0.0", 3, HASH, older = false)
        val model = withNewVersion()

        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()

        assertEquals(InstallUi.Failed(InstallFailure.NOT_NEWER), model.uiState.value.install)
        assertEquals(1, discards)
    }

    @Test
    fun `a file that is not an APK is deleted as a failed download`() = runTest(dispatcher) {
        check = ApkCheckResult.NotApk
        val model = withNewVersion()

        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()

        assertEquals(
            InstallUi.Failed(InstallFailure.DOWNLOAD_FAILED),
            model.uiState.value.install,
        )
        assertEquals(1, discards)
    }

    @Test
    fun `without permission it explains and continues once it is allowed`() = runTest(dispatcher) {
        canInstall = false
        val model = withNewVersion()

        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()
        assertEquals(InstallUi.NeedsPermission, model.uiState.value.install)

        // Volver sin permitirlo no cambia nada.
        model.onAction(UpdatesAction.Resumed)
        assertEquals(InstallUi.NeedsPermission, model.uiState.value.install)

        canInstall = true
        model.onAction(UpdatesAction.Resumed)
        assertEquals(InstallUi.Opening, model.uiState.value.install)
        assertEquals(0, discards)
    }

    @Test
    fun `without internet it says so and can be retried`() = runTest(dispatcher) {
        download = { ApkDownloadResult.Offline }
        val model = withNewVersion()

        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()
        assertEquals(InstallUi.Failed(InstallFailure.NO_CONNECTION), model.uiState.value.install)

        download = { ApkDownloadResult.Downloaded(HASH, 1024) }
        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()

        assertEquals(listOf("1.0.1", "1.0.1"), downloadedNames)
        assertEquals(InstallUi.Opening, model.uiState.value.install)
    }

    @Test
    fun `each download failure has its own message`() = runTest(dispatcher) {
        val model = withNewVersion()
        val expected =
            listOf(
                ApkDownloadResult.TooLarge to InstallFailure.TOO_LARGE,
                ApkDownloadResult.Failed to InstallFailure.DOWNLOAD_FAILED,
                ApkDownloadResult.Refused to InstallFailure.DOWNLOAD_FAILED,
            )

        for ((result, failure) in expected) {
            download = { result }
            model.onAction(UpdatesAction.StartUpdate)
            advanceUntilIdle()
            assertEquals(InstallUi.Failed(failure), model.uiState.value.install)
        }

        download = { throw IOException("cortado") }
        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()
        assertEquals(
            InstallUi.Failed(InstallFailure.DOWNLOAD_FAILED),
            model.uiState.value.install,
        )
        assertEquals(0, checks)
        assertEquals(4, discards)
    }

    @Test
    fun `an installer that does not open says so and deletes the file`() = runTest(dispatcher) {
        val model = withNewVersion()
        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()

        model.onAction(UpdatesAction.InstallerOpened(false))
        advanceUntilIdle()

        assertEquals(InstallUi.Failed(InstallFailure.NO_INSTALLER), model.uiState.value.install)
        assertEquals(1, discards)
    }

    @Test
    fun `the installer opens again with the same file`() = runTest(dispatcher) {
        val model = withNewVersion()
        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()
        model.onAction(UpdatesAction.InstallerOpened(true))

        model.onAction(UpdatesAction.OpenInstallerAgain)

        assertEquals(InstallUi.Opening, model.uiState.value.install)
        assertEquals(1, downloadedNames.size)
        assertEquals(0, discards)
    }

    @Test
    fun `turning the search off stops the update and deletes the file`() = runTest(dispatcher) {
        val model = withNewVersion()
        model.onAction(UpdatesAction.StartUpdate)
        advanceUntilIdle()

        model.onAction(UpdatesAction.SetSearchEnabled(false))
        advanceUntilIdle()

        assertEquals(InstallUi.Idle, model.uiState.value.install)
        assertEquals(1, discards)
    }

    @Test
    fun `what is new opens with its text and closes`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(UpdatesAction.OpenWhatsNew)
        assertEquals(WhatsNewUi.Loading, model.uiState.value.whatsNew)
        advanceUntilIdle()
        assertEquals(WhatsNewUi.Ready(listOf(NEWS_HEADING)), model.uiState.value.whatsNew)

        model.onAction(UpdatesAction.CloseWhatsNew)
        assertEquals(WhatsNewUi.Closed, model.uiState.value.whatsNew)
    }

    @Test
    fun `what is new that cannot be read says so`() = runTest(dispatcher) {
        whatsNew = null
        val model = model()
        advanceUntilIdle()

        model.onAction(UpdatesAction.OpenWhatsNew)
        advanceUntilIdle()

        assertEquals(WhatsNewUi.Failed, model.uiState.value.whatsNew)
    }

    private companion object {
        val HASH = "ab".repeat(32)
        val NEWS_HEADING = HelpBlock.Heading(2, listOf(HelpSpan.Plain("1.0.2")))
    }
}
