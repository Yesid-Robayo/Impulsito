package io.github.yesidrobayo.shopledger.ui.region

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsProblem
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La pantalla de configuración regional: arma la propuesta desde lo guardado y desde el teléfono,
 * la deja cambiar y la guarda con el caso de uso.
 *
 * Lee lo guardado una sola vez, al abrir: lo que la persona está eligiendo no se pisa si otra parte
 * de la app cambia un ajuste mientras tanto. La moneda guardada sin confirmar se propone con la de
 * la región del teléfono, si tiene una; si no, queda sin confirmar y la persona la elige.
 *
 * @param settings lo guardado, o `null` si la base no se pudo abrir.
 * @param save guarda lo elegido, en una sola transacción.
 * @param catalog lo que el teléfono sabe de monedas, formatos y zonas.
 * @param workContext donde se arman las listas, fuera del hilo principal.
 */
class RegionalSettingsViewModel(
    private val firstRun: Boolean,
    private val settings: suspend () -> Flow<RegionalSettings>?,
    private val save: suspend (RegionalSettingsInput) -> SaveRegionalSettingsResult,
    private val catalog: RegionalCatalog,
    private val workContext: CoroutineContext,
) : ViewModel() {
    private val state = MutableStateFlow<RegionalSettingsUiState>(RegionalSettingsUiState.Loading)
    val uiState: StateFlow<RegionalSettingsUiState> = state.asStateFlow()

    private var stored: RegionalSettings? = null
    private var lists: Lists? = null
    private var uiLocale: Locale? = null

    /** Las tres listas, en el idioma de la interfaz con que se armaron. */
    private class Lists(
        val currencies: List<RegionalOption>,
        val formats: List<RegionalOption>,
        val zones: List<RegionalOption>,
    )

    init {
        viewModelScope.launch {
            val source = settings()
            if (source == null) {
                state.value = RegionalSettingsUiState.Unavailable
                return@launch
            }
            val saved = source.first()
            stored = saved
            state.value = withContext(workContext) { initialState(saved) }
        }
    }

    /**
     * El idioma de la interfaz, del que salen los nombres de las listas. Cambia cuando la persona
     * cambia el idioma: las listas se vuelven a armar en el nuevo.
     */
    fun setUiLocale(locale: Locale) {
        if (locale == uiLocale) return
        uiLocale = locale
        viewModelScope.launch {
            lists =
                withContext(workContext) {
                    Lists(
                        catalog.currencies(locale),
                        catalog.formats(locale),
                        catalog.zones(locale),
                    )
                }
            update { it.withNames() }
        }
    }

    fun onAction(action: RegionalSettingsAction) {
        when (action) {
            is RegionalSettingsAction.OpenSheet ->
                update { it.copy(sheet = action.sheet, query = "").filtered() }

            RegionalSettingsAction.CloseSheet ->
                update { it.copy(sheet = null, query = "").filtered() }

            is RegionalSettingsAction.Search -> update { it.copy(query = action.query).filtered() }

            is RegionalSettingsAction.Pick -> update { pick(it, action.key) }

            is RegionalSettingsAction.ChooseDecimals ->
                update {
                    if (it.locked) it else it.edit(it.draft.copy(decimals = action.decimals))
                }

            is RegionalSettingsAction.EditSymbol ->
                update {
                    it.edit(it.draft.copy(symbol = action.text))
                        .without(RegionalFieldError.SYMBOL_LENGTH)
                }

            RegionalSettingsAction.LeaveSymbol -> update { it.showingSymbolError() }

            is RegionalSettingsAction.EditCashRounding ->
                update {
                    it.edit(it.draft.copy(cashRounding = action.text)).withoutCashRoundingErrors()
                }

            RegionalSettingsAction.LeaveCashRounding -> update { it.showingCashRoundingError() }

            is RegionalSettingsAction.ChooseWeekStart ->
                update { it.edit(it.draft.copy(weekStart = action.day)) }

            is RegionalSettingsAction.ChooseCsvSeparator ->
                update { it.edit(it.draft.copy(csvSeparator = action.separator)) }

            RegionalSettingsAction.Save -> requestSave()

            RegionalSettingsAction.ConfirmZoneChange -> {
                update { it.copy(confirmingZone = false) }
                persist()
            }

            RegionalSettingsAction.CancelZoneChange -> update { it.copy(confirmingZone = false) }

            RegionalSettingsAction.SavedShown ->
                update {
                    if (it.saveState == RegionalSaveState.SAVED) {
                        it.copy(saveState = RegionalSaveState.IDLE)
                    } else {
                        it
                    }
                }
        }
    }

    private fun initialState(saved: RegionalSettings): RegionalSettingsUiState.Ready {
        val currency = saved.currency
        val proposed =
            if (currency.code.isConfirmed) currency.code else catalog.deviceCurrency()
        val code = proposed ?: CurrencyCode.UNCONFIRMED
        val decimals =
            if (currency.code.isConfirmed) {
                currency.decimals
            } else {
                proposed?.let(catalog::isoDecimals) ?: currency.decimals
            }
        val draft =
            RegionalDraft(
                currency = code,
                decimals = decimals,
                symbol = currency.symbol.orEmpty(),
                cashRounding =
                    plainAmount(
                        saved.cashRounding,
                        decimals,
                        catalog.moneyInputSymbols(
                            saved.formatLocaleTag,
                            decimals,
                        )?.decimalSeparator,
                    ),
                formatTag = saved.formatLocaleTag,
                zoneId = saved.businessTimeZoneId,
                weekStart = saved.weekStartDay,
                csvSeparator = saved.csvSeparator,
            )
        return RegionalSettingsUiState
            .Ready(
                firstRun = firstRun,
                draft = draft,
                locked = currency.isLocked,
                currencyName = null,
                formatName = null,
                zoneName = null,
                isoDecimals = catalog.isoDecimals(code),
                cashRoundingStep = cashRoundingOf(draft),
            ).withNames()
            .withStoredProblems()
    }

    private fun pick(
        current: RegionalSettingsUiState.Ready,
        key: String,
    ): RegionalSettingsUiState.Ready {
        val closed = current.copy(sheet = null, query = "")
        return when (current.sheet) {
            null -> closed

            RegionalSheet.CURRENCY -> {
                val code = CurrencyCode.of(key)
                if (current.locked || code == null) {
                    closed
                } else {
                    val decimals = catalog.isoDecimals(code) ?: current.draft.decimals
                    closed
                        .edit(current.draft.copy(currency = code, decimals = decimals))
                        .copy(isoDecimals = catalog.isoDecimals(code))
                        .without(RegionalFieldError.CURRENCY_MISSING)
                }
            }

            RegionalSheet.FORMAT -> closed.edit(reproposed(current.draft, key))
                .without(RegionalFieldError.FORMAT_MISSING)

            RegionalSheet.ZONE -> closed.edit(current.draft.copy(zoneId = key))
                .without(RegionalFieldError.ZONE_UNKNOWN)
        }.withNames()
    }

    /**
     * Cambia el formato a [tag]. El primer día de la semana y el separador de CSV se proponen de
     * nuevo solo si seguían en lo que proponía el formato anterior: lo que la persona eligió a
     * mano no se pisa.
     */
    private fun reproposed(draft: RegionalDraft, tag: String): RegionalDraft {
        val oldWeek = catalog.proposedWeekStart(draft.formatTag)
        val oldCsv = catalog.proposedCsvSeparator(draft.formatTag)
        val week =
            if (oldWeek == null || oldWeek == draft.weekStart) {
                catalog.proposedWeekStart(tag) ?: draft.weekStart
            } else {
                draft.weekStart
            }
        val csv =
            if (oldCsv == null || oldCsv == draft.csvSeparator) {
                catalog.proposedCsvSeparator(tag) ?: draft.csvSeparator
            } else {
                draft.csvSeparator
            }
        // El redondeo ya escrito se vuelve a escribir con el separador decimal del formato nuevo:
        // si no, una coma escrita para un formato se leería como agrupación en el otro.
        val rounding =
            cashRoundingOf(draft)?.let { amount ->
                plainAmount(
                    amount,
                    draft.decimals,
                    catalog.moneyInputSymbols(tag, draft.decimals)?.decimalSeparator,
                )
            } ?: draft.cashRounding
        return draft.copy(
            formatTag = tag,
            weekStart = week,
            csvSeparator = csv,
            cashRounding = rounding,
        )
    }

    private fun requestSave() {
        val current = state.value as? RegionalSettingsUiState.Ready ?: return
        if (current.saveState == RegionalSaveState.SAVING) return
        val errors = fieldErrors(current)
        if (errors.isNotEmpty()) {
            update { it.copy(errors = it.errors + errors, saveFailed = false) }
            return
        }
        val zoneChanged = stored?.businessTimeZoneId != current.draft.zoneId
        if (!firstRun && zoneChanged) {
            update { it.copy(confirmingZone = true) }
        } else {
            persist()
        }
    }

    private fun persist() {
        val current = state.value as? RegionalSettingsUiState.Ready ?: return
        val input = inputOf(current) ?: return
        update { it.copy(saveState = RegionalSaveState.SAVING, saveFailed = false) }
        viewModelScope.launch {
            when (val result = save(input)) {
                SaveRegionalSettingsResult.Saved -> {
                    stored = stored?.let { saved -> saved.withInput(input) }
                    update { it.copy(saveState = RegionalSaveState.SAVED) }
                }

                is SaveRegionalSettingsResult.Rejected ->
                    update {
                        it.copy(
                            saveState = RegionalSaveState.IDLE,
                            errors = it.errors + result.problems.mapNotNull(::errorOf),
                            // Bloqueada mientras tanto por otro registro: se muestra como tal.
                            locked =
                                it.locked ||
                                    RegionalSettingsProblem.CURRENCY_LOCKED in result.problems,
                        )
                    }

                is SaveRegionalSettingsResult.Failed ->
                    update { it.copy(saveState = RegionalSaveState.IDLE, saveFailed = true) }
            }
        }
    }

    /** Lo guardado tras guardar [input], como lo normaliza la regla: el símbolo sin espacios. */
    private fun RegionalSettings.withInput(input: RegionalSettingsInput) = copy(
        currency =
            currency.copy(
                code = input.currencyCode,
                decimals = input.currencyDecimals,
                symbol = input.currencySymbol?.trim()?.takeIf { it.isNotEmpty() },
            ),
        cashRounding = input.cashRounding,
        formatLocaleTag = input.formatLocaleTag,
        businessTimeZoneId = input.businessTimeZoneId,
        weekStartDay = input.weekStartDay,
        csvSeparator = input.csvSeparator,
    )

    /** Lo que se manda a guardar, o `null` si el redondeo no se puede leer. */
    private fun inputOf(current: RegionalSettingsUiState.Ready): RegionalSettingsInput? {
        val draft = current.draft
        val rounding = cashRoundingOf(draft) ?: return null
        return RegionalSettingsInput(
            currencyCode = draft.currency,
            currencyDecimals = draft.decimals,
            currencySymbol = draft.symbol,
            cashRounding = rounding,
            formatLocaleTag = draft.formatTag,
            businessTimeZoneId = draft.zoneId,
            weekStartDay = draft.weekStart,
            csvSeparator = draft.csvSeparator,
        )
    }

    /** El paso del redondeo escrito, en unidad menor, o `null` si no es un monto válido. */
    private fun cashRoundingOf(draft: RegionalDraft): Money? =
        (readCashRounding(draft) as? NumberInput.Value)?.value

    /** El error del redondeo escrito, o `null` si se puede guardar. */
    private fun cashRoundingError(draft: RegionalDraft): RegionalFieldError? =
        when (val read = readCashRounding(draft)) {
            is NumberInput.Value -> null
            NumberInput.Blank -> null
            NumberInput.Malformed -> RegionalFieldError.CASH_ROUNDING_NUMBER
            is NumberInput.TooManyDecimals -> RegionalFieldError.CASH_ROUNDING_DECIMALS
            is NumberInput.TooLarge -> RegionalFieldError.CASH_ROUNDING_HIGH
        }

    /**
     * Lee el redondeo con los símbolos del formato y los decimales elegidos. Vacío es cero, sin
     * redondeo; un negativo no es un paso, así que se trata como mal escrito.
     */
    private fun readCashRounding(draft: RegionalDraft): NumberInput<Money> {
        if (draft.cashRounding.isBlank()) return NumberInput.Value(Money.ZERO)
        val symbols =
            catalog.moneyInputSymbols(draft.formatTag, draft.decimals)
                ?: return NumberInput.Malformed
        return when (
            val read =
                NumberInputParser.money(
                    draft.cashRounding,
                    symbols,
                    draft.decimals,
                    Limit.UNIT_MONEY,
                )
        ) {
            is NumberInput.Value -> if (read.value < Money.ZERO) NumberInput.Malformed else read
            else -> read
        }
    }

    private fun fieldErrors(current: RegionalSettingsUiState.Ready): Set<RegionalFieldError> =
        buildSet {
            val draft = current.draft
            if (!draft.currency.isConfirmed) add(RegionalFieldError.CURRENCY_MISSING)
            if (symbolTooLong(draft.symbol)) add(RegionalFieldError.SYMBOL_LENGTH)
            cashRoundingError(draft)?.let(::add)
            if (catalog.proposedCsvSeparator(draft.formatTag) == null) {
                add(RegionalFieldError.FORMAT_MISSING)
            }
            if (BusinessTimeZone.of(draft.zoneId) == null) add(RegionalFieldError.ZONE_UNKNOWN)
        }

    private fun symbolTooLong(symbol: String): Boolean =
        symbol.trim().count { !it.isLowSurrogate() } > RegionalSettingsRules.MAX_SYMBOL_LENGTH

    private fun errorOf(problem: RegionalSettingsProblem): RegionalFieldError? = when (problem) {
        RegionalSettingsProblem.CURRENCY_UNCONFIRMED -> RegionalFieldError.CURRENCY_MISSING

        RegionalSettingsProblem.SYMBOL_TOO_LONG -> RegionalFieldError.SYMBOL_LENGTH

        RegionalSettingsProblem.CASH_ROUNDING_OUT_OF_RANGE -> RegionalFieldError.CASH_ROUNDING_HIGH

        RegionalSettingsProblem.FORMAT_LOCALE_INVALID -> RegionalFieldError.FORMAT_MISSING

        RegionalSettingsProblem.TIME_ZONE_UNKNOWN -> RegionalFieldError.ZONE_UNKNOWN

        // La pantalla ya los muestra como solo lectura, o no los deja elegir.
        RegionalSettingsProblem.CURRENCY_LOCKED,
        RegionalSettingsProblem.DECIMALS_OUT_OF_RANGE,
        -> null
    }

    /** Lo guardado que este teléfono no puede usar se marca desde el principio. */
    private fun RegionalSettingsUiState.Ready.withStoredProblems(): RegionalSettingsUiState.Ready =
        copy(
            errors =
                buildSet {
                    if (catalog.proposedCsvSeparator(draft.formatTag) == null) {
                        add(RegionalFieldError.FORMAT_MISSING)
                    }
                    if (BusinessTimeZone.of(draft.zoneId) == null) {
                        add(RegionalFieldError.ZONE_UNKNOWN)
                    }
                },
        )

    private fun RegionalSettingsUiState.Ready.edit(draft: RegionalDraft) = copy(
        draft = draft,
        saveState = RegionalSaveState.IDLE,
        cashRoundingStep = cashRoundingOf(draft),
    )

    private fun RegionalSettingsUiState.Ready.without(error: RegionalFieldError) =
        copy(errors = errors - error)

    private fun RegionalSettingsUiState.Ready.showingSymbolError() = if (symbolTooLong(
            draft.symbol,
        )
    ) {
        copy(errors = errors + RegionalFieldError.SYMBOL_LENGTH)
    } else {
        this
    }

    private fun RegionalSettingsUiState.Ready.showingCashRoundingError() =
        cashRoundingError(draft)?.let { copy(errors = errors + it) } ?: this

    private fun RegionalSettingsUiState.Ready.withoutCashRoundingErrors() = copy(
        errors =
            errors - CASH_ROUNDING_ERRORS,
    )

    /** Los nombres de lo elegido, de las listas ya armadas. */
    private fun RegionalSettingsUiState.Ready.withNames(): RegionalSettingsUiState.Ready {
        val built = lists ?: return this
        return copy(
            currencyName =
                built.currencies.firstOrNull { it.key == draft.currency.code }?.title
                    ?.takeIf { draft.currency.isConfirmed },
            formatName = built.formats.firstOrNull { it.key == draft.formatTag }?.title,
            zoneName = built.zones.firstOrNull { catalog.sameZone(it.key, draft.zoneId) }?.title,
            zoneKey = built.zones.firstOrNull { catalog.sameZone(it.key, draft.zoneId) }?.key,
        ).filtered()
    }

    /** Las opciones de la hoja abierta que coinciden con la búsqueda, sin tildes ni mayúsculas. */
    private fun RegionalSettingsUiState.Ready.filtered(): RegionalSettingsUiState.Ready {
        val built = lists
        val all =
            when (sheet) {
                null -> return copy(sheetOptions = emptyList())
                RegionalSheet.CURRENCY -> built?.currencies
                RegionalSheet.FORMAT -> built?.formats
                RegionalSheet.ZONE -> built?.zones
            }.orEmpty()
        val folded = TextNormalizer.normalize(query)
        return copy(
            sheetOptions = if (folded.isEmpty()) all else all.filter { folded in it.searchKey },
        )
    }

    private fun update(
        transform: (RegionalSettingsUiState.Ready) -> RegionalSettingsUiState.Ready,
    ) {
        state.update { if (it is RegionalSettingsUiState.Ready) transform(it) else it }
    }

    companion object {
        private val CASH_ROUNDING_ERRORS =
            setOf(
                RegionalFieldError.CASH_ROUNDING_NUMBER,
                RegionalFieldError.CASH_ROUNDING_DECIMALS,
                RegionalFieldError.CASH_ROUNDING_HIGH,
            )

        /**
         * Un monto en unidad menor como lo escribiría la persona: sin agrupar, con [separator] como
         * separador decimal y exactamente [decimals] decimales. Cero queda vacío, que es sin
         * redondeo. Sin formato usable, el separador es el punto, que el campo siempre acepta.
         */
        fun plainAmount(amount: Money, decimals: Int, separator: Char?): String {
            if (amount == Money.ZERO) return ""
            val digits = amount.minor.toString().padStart(decimals + 1, '0')
            if (decimals == 0) return digits
            val point = digits.length - decimals
            return digits.substring(0, point) + (separator ?: '.') + digits.substring(point)
        }
    }
}
