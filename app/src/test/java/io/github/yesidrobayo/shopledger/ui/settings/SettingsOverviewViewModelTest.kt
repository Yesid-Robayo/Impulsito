package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.localization.UiLanguage
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * El resumen de Ajustes: el estado del respaldo con su destino, la moneda y el idioma. El destino se
 * lee de nuevo al volver a primer plano, y si el respaldo no se puede leer, el resto sigue. Datos
 * inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsOverviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val overviews = MutableStateFlow(overview(BackupStatus.UP_TO_DATE))
    private var destination: BackupDestinationInfo? =
        BackupDestinationInfo.WebDav("cuenta@example.com", "dav.example.com")
    private var destinationReads = 0
    private val currencies = MutableStateFlow(CurrencyCode.UNCONFIRMED)
    private val language = MutableStateFlow<UiLanguage>(UiLanguage.System)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun overview(status: BackupStatus, kind: BackupDestinationKind? = null) =
        BackupOverview(
            report = BackupStatusReport(status, pendingChanges = 0, savingSoon = false),
            destinationKind = kind ?: BackupDestinationKind.WEBDAV,
            lastSuccessAt = now,
            failureCode = null,
            staleReason = null,
            meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
            nextMeteredUploadAt = null,
            lastFullReadAt = null,
            computedAt = now,
        )

    private fun model(
        backup: (Flow<Unit>) -> Flow<BackupOverview> = { refreshes ->
            combine(overviews, refreshes) { value, _ -> value }
        },
    ) = SettingsOverviewViewModel(
        backupOverview = backup,
        describeDestination = {
            destinationReads += 1
            destination
        },
        currencies = currencies,
        language = language,
        computation = dispatcher,
    )

    @Test
    fun `it shows the backup status with the connected account`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals(BackupStatus.UP_TO_DATE, state.backup?.overview?.report?.status)
        assertEquals(destination, state.destination)
        assertNull(state.backup?.providerLabel)
    }

    @Test
    fun `phone cloud slots give their provider name to the wording`() = runTest(dispatcher) {
        destination = BackupDestinationInfo.Slots("Drive")
        overviews.value = overview(BackupStatus.HANDED_OFF, BackupDestinationKind.DOCUMENT_SLOTS)
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertEquals("Drive", model.uiState.value.backup?.providerLabel)
    }

    @Test
    fun `returning to the screen reads the destination again`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        val before = destinationReads

        destination = null
        model.onReturnToForeground()
        advanceUntilIdle()

        assertEquals(before + 1, destinationReads)
        assertNull(model.uiState.value.destination)
    }

    @Test
    fun `an unreadable backup hides only its group`() = runTest(dispatcher) {
        currencies.value = requireNotNull(CurrencyCode.of("EUR"))
        val model = model(backup = { flow { error("sin estado") } })
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        val state = model.uiState.value
        assertNull(state.backup)
        assertEquals("EUR", state.currency?.code)
    }

    @Test
    fun `currency and language follow their sources`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(CurrencyCode.UNCONFIRMED, model.uiState.value.currency)
        assertEquals(UiLanguage.System, model.uiState.value.language)

        currencies.value = requireNotNull(CurrencyCode.of("PEN"))
        language.value = requireNotNull(UiLanguage.parse("pt"))
        advanceUntilIdle()

        assertEquals("PEN", model.uiState.value.currency?.code)
        assertEquals(UiLanguage.parse("pt"), model.uiState.value.language)
    }
}
