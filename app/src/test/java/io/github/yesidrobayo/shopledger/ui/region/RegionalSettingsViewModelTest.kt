package io.github.yesidrobayo.shopledger.ui.region

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsProblem
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La pantalla regional sin Android: la propuesta desde lo guardado y desde el teléfono, la lectura
 * del redondeo, la confirmación de la zona y los errores, con un catálogo inventado. Las monedas
 * son las de prueba de ISO 4217 y los formatos, etiquetas que no son de ningún país.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegionalSettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val xts = requireNotNull(CurrencyCode.of("XTS"))
    private val xtt = requireNotNull(CurrencyCode.of("XTT"))
    private val saved = mutableListOf<RegionalSettingsInput>()
    private var nextResult: SaveRegionalSettingsResult = SaveRegionalSettingsResult.Saved

    private val unconfirmed =
        RegionalSettings(
            currency = BusinessCurrency(CurrencyCode.UNCONFIRMED, 0, null, lockedAt = null),
            cashRounding = Money.ZERO,
            formatLocaleTag = COMMA_FORMAT,
            businessTimeZoneId = "Etc/GMT+5",
            weekStartDay = DayOfWeek.MONDAY,
            csvSeparator = CsvSeparator.SEMICOLON,
            referenceCurrency = null,
        )

    @Before
    fun mainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private fun viewModel(
        stored: RegionalSettings? = unconfirmed,
        firstRun: Boolean = true,
        catalog: FakeCatalog = FakeCatalog(device = xts),
    ) = RegionalSettingsViewModel(
        firstRun = firstRun,
        settings = { stored?.let { flowOf(it) } },
        save = { input ->
            saved += input
            nextResult
        },
        catalog = catalog,
        workContext = dispatcher,
    ).also { it.setUiLocale(Locale.ENGLISH) }

    private fun RegionalSettingsViewModel.ready(): RegionalSettingsUiState.Ready =
        uiState.value as RegionalSettingsUiState.Ready

    @Test
    fun `without a database there is nothing to edit`() {
        assertEquals(RegionalSettingsUiState.Unavailable, viewModel(stored = null).uiState.value)
    }

    @Test
    fun `an unconfirmed business proposes the phone currency with its standard decimals`() {
        val state = viewModel().ready()

        assertEquals(xts, state.draft.currency)
        assertEquals(2, state.draft.decimals)
        assertEquals("Test money", state.currencyName)
        assertEquals("Comma format", state.formatName)
        assertEquals(2, state.isoDecimals)
        // Nada se guardó todavía: confirmar es de la persona.
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a phone without a known currency keeps it unconfirmed and asks to choose one`() {
        val model = viewModel(catalog = FakeCatalog(device = null))
        assertEquals(CurrencyCode.UNCONFIRMED, model.ready().draft.currency)
        assertEquals(null, model.ready().currencyName)

        model.onAction(RegionalSettingsAction.Save)

        assertTrue(RegionalFieldError.CURRENCY_MISSING in model.ready().errors)
        assertTrue(saved.isEmpty())

        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.CURRENCY))
        model.onAction(RegionalSettingsAction.Pick("XTT"))
        assertEquals(xtt, model.ready().draft.currency)
        assertEquals(3, model.ready().draft.decimals)
        assertFalse(RegionalFieldError.CURRENCY_MISSING in model.ready().errors)
    }

    @Test
    fun `confirming on the first run saves everything as chosen without asking about the zone`() {
        val model = viewModel()
        model.onAction(RegionalSettingsAction.ChooseDecimals(3))
        model.onAction(RegionalSettingsAction.EditSymbol(" ¤ "))
        model.onAction(RegionalSettingsAction.EditCashRounding("0,050"))
        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.ZONE))
        model.onAction(RegionalSettingsAction.Pick("Etc/GMT-9"))
        model.onAction(RegionalSettingsAction.ChooseCsvSeparator(CsvSeparator.TAB))

        model.onAction(RegionalSettingsAction.Save)

        assertEquals(
            listOf(
                RegionalSettingsInput(
                    currencyCode = xts,
                    currencyDecimals = 3,
                    currencySymbol = " ¤ ",
                    cashRounding = Money(50),
                    formatLocaleTag = COMMA_FORMAT,
                    businessTimeZoneId = "Etc/GMT-9",
                    weekStartDay = DayOfWeek.MONDAY,
                    csvSeparator = CsvSeparator.TAB,
                ),
            ),
            saved,
        )
        assertEquals(RegionalSaveState.SAVED, model.ready().saveState)
        assertFalse(model.ready().confirmingZone)
    }

    @Test
    fun `changing the zone of a business with records asks first`() {
        val model = viewModel(stored = unconfirmed.withCurrency(xts), firstRun = false)
        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.ZONE))
        model.onAction(RegionalSettingsAction.Pick("Etc/GMT-9"))

        model.onAction(RegionalSettingsAction.Save)
        assertTrue(model.ready().confirmingZone)
        assertTrue(saved.isEmpty())

        model.onAction(RegionalSettingsAction.CancelZoneChange)
        assertFalse(model.ready().confirmingZone)
        assertTrue(saved.isEmpty())

        model.onAction(RegionalSettingsAction.Save)
        model.onAction(RegionalSettingsAction.ConfirmZoneChange)
        assertEquals("Etc/GMT-9", saved.single().businessTimeZoneId)

        // Ya guardada, guardar de nuevo no vuelve a preguntar.
        model.onAction(RegionalSettingsAction.Save)
        assertFalse(model.ready().confirmingZone)
        assertEquals(2, saved.size)
    }

    @Test
    fun `changing the format proposes again only what the person had not changed`() {
        val model = viewModel()

        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.FORMAT))
        model.onAction(RegionalSettingsAction.Pick(POINT_FORMAT))
        assertEquals(DayOfWeek.SUNDAY, model.ready().draft.weekStart)
        assertEquals(CsvSeparator.COMMA, model.ready().draft.csvSeparator)

        model.onAction(RegionalSettingsAction.ChooseWeekStart(DayOfWeek.SATURDAY))
        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.FORMAT))
        model.onAction(RegionalSettingsAction.Pick(COMMA_FORMAT))
        assertEquals(DayOfWeek.SATURDAY, model.ready().draft.weekStart)
        assertEquals(CsvSeparator.SEMICOLON, model.ready().draft.csvSeparator)
    }

    @Test
    fun `changing the format rewrites the rounding with its decimal separator`() {
        val model = viewModel()
        model.onAction(RegionalSettingsAction.EditCashRounding("0,05"))

        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.FORMAT))
        model.onAction(RegionalSettingsAction.Pick(POINT_FORMAT))

        assertEquals("0.05", model.ready().draft.cashRounding)
        assertEquals(Money(5), model.ready().cashRoundingStep)
    }

    @Test
    fun `the cash rounding error shows when leaving the field, never while typing`() {
        val model = viewModel()

        model.onAction(RegionalSettingsAction.EditCashRounding("0,055"))
        assertTrue(model.ready().errors.isEmpty())
        assertEquals(null, model.ready().cashRoundingStep)

        model.onAction(RegionalSettingsAction.LeaveCashRounding)
        assertEquals(setOf(RegionalFieldError.CASH_ROUNDING_DECIMALS), model.ready().errors)

        model.onAction(RegionalSettingsAction.EditCashRounding("0,05"))
        assertTrue(model.ready().errors.isEmpty())
        assertEquals(Money(5), model.ready().cashRoundingStep)

        model.onAction(RegionalSettingsAction.EditCashRounding("abc"))
        model.onAction(RegionalSettingsAction.Save)
        assertEquals(setOf(RegionalFieldError.CASH_ROUNDING_NUMBER), model.ready().errors)

        model.onAction(RegionalSettingsAction.EditCashRounding("10000000000,01"))
        model.onAction(RegionalSettingsAction.LeaveCashRounding)
        assertEquals(setOf(RegionalFieldError.CASH_ROUNDING_HIGH), model.ready().errors)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a symbol longer than eight characters is marked when leaving`() {
        val model = viewModel()
        model.onAction(RegionalSettingsAction.EditSymbol("ABCDEFGHI"))
        assertTrue(model.ready().errors.isEmpty())

        model.onAction(RegionalSettingsAction.LeaveSymbol)
        assertEquals(setOf(RegionalFieldError.SYMBOL_LENGTH), model.ready().errors)

        model.onAction(RegionalSettingsAction.EditSymbol("ABCDEFGH"))
        model.onAction(RegionalSettingsAction.LeaveSymbol)
        assertTrue(model.ready().errors.isEmpty())
    }

    @Test
    fun `once locked, currency and decimals stay as saved`() {
        val locked =
            unconfirmed.copy(
                currency = BusinessCurrency(xts, 2, null, Instant.fromEpochMilliseconds(1)),
            )
        val model = viewModel(stored = locked, firstRun = false)
        assertTrue(model.ready().locked)

        model.onAction(RegionalSettingsAction.ChooseDecimals(4))
        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.CURRENCY))
        model.onAction(RegionalSettingsAction.Pick("XTT"))

        assertEquals(xts, model.ready().draft.currency)
        assertEquals(2, model.ready().draft.decimals)
        model.onAction(RegionalSettingsAction.EditCashRounding("0,10"))
        model.onAction(RegionalSettingsAction.Save)
        assertEquals(Money(10), saved.single().cashRounding)
    }

    @Test
    fun `a lock that arrives while editing is shown as such`() {
        nextResult =
            SaveRegionalSettingsResult.Rejected(setOf(RegionalSettingsProblem.CURRENCY_LOCKED))
        val model = viewModel()

        model.onAction(RegionalSettingsAction.Save)

        assertTrue(model.ready().locked)
        assertEquals(RegionalSaveState.IDLE, model.ready().saveState)
    }

    @Test
    fun `a failed save keeps everything and can be retried`() {
        nextResult = SaveRegionalSettingsResult.Failed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.onAction(RegionalSettingsAction.EditSymbol("¤"))

        model.onAction(RegionalSettingsAction.Save)
        assertTrue(model.ready().saveFailed)
        assertEquals("¤", model.ready().draft.symbol)

        nextResult = SaveRegionalSettingsResult.Saved
        model.onAction(RegionalSettingsAction.Save)
        assertFalse(model.ready().saveFailed)
        assertEquals(RegionalSaveState.SAVED, model.ready().saveState)
        model.onAction(RegionalSettingsAction.SavedShown)
        assertEquals(RegionalSaveState.IDLE, model.ready().saveState)
    }

    @Test
    fun `a stored zone or format this phone cannot use is marked from the start`() {
        val foreign = unconfirmed.copy(
            businessTimeZoneId = "Nowhere/Invented",
            formatLocaleTag = "und",
        )
        val model = viewModel(stored = foreign)

        assertEquals(
            setOf(RegionalFieldError.ZONE_UNKNOWN, RegionalFieldError.FORMAT_MISSING),
            model.ready().errors,
        )
        model.onAction(RegionalSettingsAction.Save)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `searching folds accents and case`() {
        val model = viewModel()
        model.onAction(RegionalSettingsAction.OpenSheet(RegionalSheet.ZONE))
        assertEquals(2, model.ready().sheetOptions.size)

        model.onAction(RegionalSettingsAction.Search("SÁO"))
        assertEquals(listOf("Etc/GMT-9"), model.ready().sheetOptions.map { it.key })

        model.onAction(RegionalSettingsAction.CloseSheet)
        assertEquals(null, model.ready().sheet)
        assertTrue(model.ready().sheetOptions.isEmpty())
    }

    @Test
    fun `an amount is written back as typed, without grouping`() {
        assertEquals("", RegionalSettingsViewModel.plainAmount(Money.ZERO, 2, ','))
        assertEquals("0,05", RegionalSettingsViewModel.plainAmount(Money(5), 2, ','))
        assertEquals("50", RegionalSettingsViewModel.plainAmount(Money(50), 0, ','))
        assertEquals("1234.567", RegionalSettingsViewModel.plainAmount(Money(1_234_567), 3, null))
    }

    private fun RegionalSettings.withCurrency(code: CurrencyCode) =
        copy(currency = currency.copy(code = code, decimals = 2))

    /** Un catálogo inventado: dos monedas de prueba, dos formatos y dos zonas. */
    private class FakeCatalog(private val device: CurrencyCode?) : RegionalCatalog {
        private fun option(key: String, title: String) =
            RegionalOption(key, title, key, TextNormalizer.normalize("$title $key"))

        override fun currencies(uiLocale: Locale) =
            listOf(option("XTS", "Test money"), option("XTT", "Other test money"))

        override fun formats(uiLocale: Locale) =
            listOf(option(COMMA_FORMAT, "Comma format"), option(POINT_FORMAT, "Point format"))

        override fun zones(uiLocale: Locale) =
            listOf(option("Etc/GMT+5", "West"), option("Etc/GMT-9", "Sáo Leste"))

        override fun sameZone(optionKey: String, zoneId: String): Boolean = optionKey == zoneId

        override fun isoDecimals(code: CurrencyCode): Int? = when (code.code) {
            "XTS" -> 2
            "XTT" -> 3
            else -> null
        }

        override fun deviceCurrency(): CurrencyCode? = device

        override fun proposedWeekStart(formatTag: String): DayOfWeek? = when (formatTag) {
            COMMA_FORMAT -> DayOfWeek.MONDAY
            POINT_FORMAT -> DayOfWeek.SUNDAY
            else -> null
        }

        override fun proposedCsvSeparator(formatTag: String): CsvSeparator? = when (formatTag) {
            COMMA_FORMAT -> CsvSeparator.SEMICOLON
            POINT_FORMAT -> CsvSeparator.COMMA
            else -> null
        }

        override fun moneyInputSymbols(formatTag: String, decimals: Int): NumberSymbols? =
            when (formatTag) {
                COMMA_FORMAT -> NumberSymbols(',', '-', DigitGrouping('.', 3, 3))
                POINT_FORMAT -> NumberSymbols('.', '-', DigitGrouping(',', 3, 3))
                else -> null
            }
    }

    private companion object {
        const val COMMA_FORMAT = "xx-AA"
        const val POINT_FORMAT = "xx-BB"
    }
}
