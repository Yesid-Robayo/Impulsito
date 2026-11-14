package io.github.yesidrobayo.shopledger.ui.region

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.backup.SafetyCopyFailure
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCorrection
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.region.CurrencyCorrectionProblem
import io.github.yesidrobayo.shopledger.domain.usecase.region.CorrectCurrencyResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import java.util.Locale
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 * Corregir la moneda en la pantalla regional, sin Android: la acción solo existe con la moneda
 * bloqueada, la hoja ofrece solo monedas con los mismos decimales, nada cambia sin confirmar, y
 * cada fallo se muestra con su motivo. Las monedas son códigos de prueba de ninguna parte.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyCorrectionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val xts = code("XTS")
    private val xtu = code("XTU")
    private val lockedAt = Instant.fromEpochMilliseconds(1_793_000_000_000)
    private val corrections = mutableListOf<CurrencyCorrection>()
    private val saved = mutableListOf<RegionalSettingsInput>()
    private var nextResult: CorrectCurrencyResult? = null

    // El negocio en XTS, con dos decimales y ya con registros con dinero.
    private val locked =
        RegionalSettings(
            currency = BusinessCurrency(xts, 2, null, lockedAt),
            cashRounding = Money.ZERO,
            formatLocaleTag = FORMAT,
            businessTimeZoneId = "Etc/GMT+5",
            weekStartDay = DayOfWeek.MONDAY,
            csvSeparator = CsvSeparator.SEMICOLON,
            referenceCurrency = null,
        )

    @Before
    fun mainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private fun viewModel(stored: RegionalSettings = locked) = RegionalSettingsViewModel(
        firstRun = false,
        settings = { flowOf(stored) },
        save = { input ->
            saved += input
            SaveRegionalSettingsResult.Saved
        },
        correct = { request ->
            corrections += request
            nextResult ?: CorrectCurrencyResult.Applied(
                BusinessCurrency(request.code, request.isoDecimals, null, lockedAt),
                accounts = 1,
            )
        },
        catalog = FakeCatalog(),
        workContext = dispatcher,
    ).also { it.setUiLocale(Locale.ENGLISH) }

    private fun RegionalSettingsViewModel.ready(): RegionalSettingsUiState.Ready =
        uiState.value as RegionalSettingsUiState.Ready

    @Test
    fun `before the lock there is nothing to correct`() {
        val model = viewModel(locked.copy(currency = locked.currency.copy(lockedAt = null)))

        assertFalse(model.ready().locked)
        assertNull(model.ready().correctionOptions)
    }

    @Test
    fun `with the lock only other currencies with the same decimals are offered`() {
        val model = viewModel()

        assertEquals(listOf("XTU", "XTV"), model.ready().correctionOptions?.map { it.key })

        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.CORRECTION))
        assertEquals(listOf("XTU", "XTV"), model.ready().sheetOptions.map { it.key })
        model.onAction(RegionalSettingsAction.Search("third"))
        assertEquals(listOf("XTU"), model.ready().sheetOptions.map { it.key })
    }

    @Test
    fun `picking asks first, and cancelling changes nothing`() {
        val model = viewModel()
        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.CORRECTION))

        model.onAction(RegionalSettingsAction.Pick("XTU"))

        assertEquals("XTU", model.ready().correctionTarget?.key)
        assertNull(model.ready().sheet)
        assertTrue(corrections.isEmpty())

        model.onAction(RegionalSettingsAction.CancelCorrection)
        assertNull(model.ready().correctionTarget)
        assertEquals(xts, model.ready().draft.currency)
        // Sin una moneda elegida, confirmar no hace nada.
        model.onAction(RegionalSettingsAction.ConfirmCorrection)
        assertTrue(corrections.isEmpty())
    }

    @Test
    fun `a currency outside the offered ones is never a target`() {
        val model = viewModel()
        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.CORRECTION))

        model.onAction(RegionalSettingsAction.Pick("XTT"))

        assertNull(model.ready().correctionTarget)
    }

    @Test
    fun `confirming corrects only the label and later saves keep it`() {
        val model = viewModel()
        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.CORRECTION))
        model.onAction(RegionalSettingsAction.Pick("XTU"))

        model.onAction(RegionalSettingsAction.ConfirmCorrection)

        assertEquals(listOf(CurrencyCorrection(xtu, 2)), corrections)
        val state = model.ready()
        assertEquals(xtu, state.draft.currency)
        assertEquals(2, state.draft.decimals)
        assertEquals("Third test money", state.currencyName)
        assertTrue(state.currencyCorrected)
        assertFalse(state.correcting)
        assertNull(state.correctionTarget)
        assertNull(state.correctionError)
        // La moneda de antes vuelve a ofrecerse, y la nueva ya no.
        assertEquals(listOf("XTS", "XTV"), state.correctionOptions?.map { it.key })

        model.onAction(RegionalSettingsAction.CorrectionShown)
        assertFalse(model.ready().currencyCorrected)

        // Guardar el formulario después usa la moneda corregida, que la regla acepta.
        model.onAction(RegionalSettingsAction.EditCashRounding("0,05"))
        model.onAction(RegionalSettingsAction.Save)
        assertEquals(xtu, saved.single().currencyCode)
        assertEquals(2, saved.single().currencyDecimals)
    }

    @Test
    fun `each failure is shown with its reason and the currency stays`() {
        val expected =
            listOf(
                CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.SAME_AS_REFERENCE) to
                    CurrencyCorrectionError.REFERENCE,
                CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.USED_BY_ACCOUNT) to
                    CurrencyCorrectionError.ACCOUNT,
                CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.USED_AS_OTHER_CURRENCY) to
                    CurrencyCorrectionError.OTHER_CURRENCY,
                CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.NOT_LOCKED) to
                    CurrencyCorrectionError.UNAVAILABLE,
                CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.UNCONFIRMED) to
                    CurrencyCorrectionError.UNAVAILABLE,
                CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.SAME_AS_CURRENT) to
                    CurrencyCorrectionError.UNAVAILABLE,
                CorrectCurrencyResult.Rejected(CurrencyCorrectionProblem.DECIMALS_DIFFER) to
                    CurrencyCorrectionError.UNAVAILABLE,
                CorrectCurrencyResult.SafetyCopyFailed(SafetyCopyFailure.NO_SPACE) to
                    CurrencyCorrectionError.NO_SPACE,
                CorrectCurrencyResult.SafetyCopyFailed(SafetyCopyFailure.BUSY) to
                    CurrencyCorrectionError.BUSY,
                CorrectCurrencyResult.SafetyCopyFailed(SafetyCopyFailure.UNSYNCED_COPIES) to
                    CurrencyCorrectionError.UNSYNCED_COPIES,
                CorrectCurrencyResult.SafetyCopyFailed(SafetyCopyFailure.FAILED) to
                    CurrencyCorrectionError.COPY_FAILED,
                CorrectCurrencyResult.StorageFailed(StorageFailure.DISK_FULL) to
                    CurrencyCorrectionError.SAVE_FAILED,
            )
        val model = viewModel()
        for ((result, error) in expected) {
            nextResult = result
            model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.CORRECTION))
            model.onAction(RegionalSettingsAction.Pick("XTV"))
            // Elegir otra vez borra el fallo anterior.
            assertNull(model.ready().correctionError)

            model.onAction(RegionalSettingsAction.ConfirmCorrection)

            assertEquals(error, model.ready().correctionError)
            assertFalse(model.ready().correcting)
            assertEquals(xts, model.ready().draft.currency)
            assertFalse(model.ready().currencyCorrected)
        }
        assertEquals(expected.size, corrections.size)
    }

    private fun code(text: String): CurrencyCode = requireNotNull(CurrencyCode.of(text))

    /** Un catálogo inventado: tres monedas de prueba con dos decimales y una con tres. */
    private class FakeCatalog : RegionalCatalog {
        private fun option(key: String, title: String) =
            RegionalOption(key, title, key, TextNormalizer.normalize("$title $key"))

        override fun currencies(uiLocale: Locale) = listOf(
            option("XTS", "Test money"),
            option("XTT", "Other test money"),
            option("XTU", "Third test money"),
            option("XTV", "Fourth test money"),
        )

        override fun formats(uiLocale: Locale) = listOf(option(FORMAT, "Comma format"))

        override fun zones(uiLocale: Locale) = listOf(option("Etc/GMT+5", "West"))

        override fun sameZone(optionKey: String, zoneId: String): Boolean = optionKey == zoneId

        override fun isoDecimals(code: CurrencyCode): Int? = when (code.code) {
            "XTS", "XTU", "XTV" -> 2
            "XTT" -> 3
            else -> null
        }

        override fun deviceCurrency(): CurrencyCode? = null

        override fun proposedWeekStart(formatTag: String): DayOfWeek? =
            DayOfWeek.MONDAY.takeIf { formatTag == FORMAT }

        override fun proposedCsvSeparator(formatTag: String): CsvSeparator? =
            CsvSeparator.SEMICOLON.takeIf { formatTag == FORMAT }

        override fun moneyInputSymbols(formatTag: String, decimals: Int): NumberSymbols? =
            NumberSymbols(',', '-', DigitGrouping('.', 3, 3)).takeIf { formatTag == FORMAT }
    }

    private companion object {
        const val FORMAT = "xx-AA"
    }
}
