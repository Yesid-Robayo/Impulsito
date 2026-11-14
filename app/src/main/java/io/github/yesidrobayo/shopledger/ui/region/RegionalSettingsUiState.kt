package io.github.yesidrobayo.shopledger.ui.region

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import kotlinx.datetime.DayOfWeek

/**
 * Lo que la persona va eligiendo, antes de confirmar. Los textos de los campos se guardan como se
 * escribieron: un error nunca borra lo escrito.
 */
@Immutable
data class RegionalDraft(
    val currency: CurrencyCode,
    val decimals: Int,
    /** El símbolo propio tal como se escribió. Vacío: el de la moneda. */
    val symbol: String,
    /** El paso del redondeo de efectivo, en unidades de la moneda y con el formato elegido. */
    val cashRounding: String,
    val formatTag: String,
    val zoneId: String,
    val weekStart: DayOfWeek,
    val csvSeparator: CsvSeparator,
)

/** Cada hoja de búsqueda de la pantalla. */
enum class RegionalSheet {
    CURRENCY,
    FORMAT,
    ZONE,

    /** La moneda correcta, entre las que tienen los mismos decimales, con la moneda bloqueada. */
    CORRECTION,
}

/** Por qué no se corrigió la moneda. Cada uno lleva su texto, que elige la pantalla. */
enum class CurrencyCorrectionError {
    /** Es la moneda de referencia: primero hay que quitarla como referencia. */
    REFERENCE,

    /** Una cuenta propia ya está en esa moneda. */
    ACCOUNT,

    /** Un cobro, una compra o una cotización la guardó como otra moneda. */
    OTHER_CURRENCY,

    /** Ya no sirve: cambió lo guardado mientras se elegía, o la moneda ya no está bloqueada. */
    UNAVAILABLE,

    /** No hubo espacio para la copia de seguridad. */
    NO_SPACE,

    /** Hay una restauración en curso. */
    BUSY,

    /** Las dos copias de seguridad guardan cambios que no llegaron al destino. */
    UNSYNCED_COPIES,

    /** La copia de seguridad falló por otra causa. */
    COPY_FAILED,

    /** La base no pudo guardar: `state_error_save`. */
    SAVE_FAILED,
}

/** Un campo que muestra su error. Cada error lleva su texto, que elige la pantalla. */
enum class RegionalFieldError {
    /** `Elegir una moneda para continuar`. */
    CURRENCY_MISSING,

    /** `Usar entre 1 y 8 caracteres`. */
    SYMBOL_LENGTH,

    /** El redondeo no es un número del formato elegido: `Escribir un número`. */
    CASH_ROUNDING_NUMBER,

    /** El redondeo tiene más decimales que la moneda. */
    CASH_ROUNDING_DECIMALS,

    /** El redondeo pasa el tope de un precio por unidad: `Ese valor parece demasiado alto`. */
    CASH_ROUNDING_HIGH,

    /** El formato guardado no sirve en este teléfono: hay que elegir otro. */
    FORMAT_MISSING,

    /** La zona no la conoce este teléfono: hay que elegir otra. */
    ZONE_UNKNOWN,
}

/** El botón de guardar: listo, guardando o recién guardado. */
enum class RegionalSaveState {
    IDLE,
    SAVING,
    SAVED,
}

/** Todos los estados de la pantalla de configuración regional. */
sealed interface RegionalSettingsUiState {
    /** Leyendo lo guardado. El esqueleto solo aparece si tarda. */
    data object Loading : RegionalSettingsUiState

    /** La base no se pudo abrir: no hay nada que editar sin riesgo. */
    data object Unavailable : RegionalSettingsUiState

    /**
     * El formulario.
     *
     * @property firstRun si es el paso del primer uso: cambia el texto del botón y no pide confirmar
     *   la zona, porque todavía no hay registros.
     * @property locked si ya hay registros con dinero: moneda y decimales solo se leen.
     * @property currencyName el nombre de la moneda elegida en el idioma de la interfaz, o `null` si
     *   este teléfono no la conoce o sigue sin confirmar.
     * @property formatName el nombre del formato elegido, o `null` si no es usable aquí.
     * @property zoneName la ciudad de la zona elegida, o `null` si este teléfono no la conoce.
     * @property zoneKey la opción de la lista que es la zona elegida, aunque lo guardado use otro
     *   nombre de la misma zona.
     * @property isoDecimals los decimales de la norma para la moneda elegida, si se conocen.
     * @property cashRoundingStep el paso del redondeo escrito, en unidad menor, para el ejemplo en
     *   vivo, o `null` mientras no sea un monto válido.
     * @property errors los errores visibles. Se muestran al salir de un campo o al confirmar,
     *   nunca mientras se escribe.
     * @property saveFailed si el último intento de guardar falló en la base.
     * @property confirmingZone si se pregunta antes de cambiar la zona de un negocio con registros.
     * @property sheet la hoja abierta, con su búsqueda [query] y sus opciones ya filtradas.
     * @property correctionOptions con la moneda bloqueada, las monedas a las que se puede
     *   corregir: las que tienen los mismos decimales según la norma, sin la actual. `null` sin
     *   bloqueo, o mientras se arman las listas.
     * @property correctionTarget la moneda elegida para corregir, mientras se pide confirmar.
     * @property correcting si se está guardando la copia de seguridad y corrigiendo la moneda.
     * @property correctionError por qué no se corrigió la moneda la última vez, si falló.
     * @property currencyCorrected si la moneda se acaba de corregir y falta decirlo.
     */
    @Immutable
    data class Ready(
        val firstRun: Boolean,
        val draft: RegionalDraft,
        val locked: Boolean,
        val currencyName: String?,
        val formatName: String?,
        val zoneName: String?,
        val zoneKey: String? = null,
        val isoDecimals: Int?,
        val cashRoundingStep: Money? = null,
        val errors: Set<RegionalFieldError> = emptySet(),
        val saveState: RegionalSaveState = RegionalSaveState.IDLE,
        val saveFailed: Boolean = false,
        val confirmingZone: Boolean = false,
        val sheet: RegionalSheet? = null,
        val query: String = "",
        val sheetOptions: List<RegionalOption> = emptyList(),
        val correctionOptions: List<RegionalOption>? = null,
        val correctionTarget: RegionalOption? = null,
        val correcting: Boolean = false,
        val correctionError: CurrencyCorrectionError? = null,
        val currencyCorrected: Boolean = false,
    ) : RegionalSettingsUiState
}

/** Lo que la persona puede hacer en la pantalla. */
sealed interface RegionalSettingsAction {
    /** Vuelve a leer lo guardado tras una falla de lectura. */
    data object Retry : RegionalSettingsAction

    data class OpenSheet(val sheet: RegionalSheet) : RegionalSettingsAction

    data object CloseSheet : RegionalSettingsAction

    data class Search(val query: String) : RegionalSettingsAction

    /** Eligió una opción de la hoja abierta, por su clave. */
    data class Pick(val key: String) : RegionalSettingsAction

    data class ChooseDecimals(val decimals: Int) : RegionalSettingsAction

    data class EditSymbol(val text: String) : RegionalSettingsAction

    /** Salió del campo del símbolo: es cuando se muestra su error. */
    data object LeaveSymbol : RegionalSettingsAction

    data class EditCashRounding(val text: String) : RegionalSettingsAction

    data object LeaveCashRounding : RegionalSettingsAction

    data class ChooseWeekStart(val day: DayOfWeek) : RegionalSettingsAction

    data class ChooseCsvSeparator(val separator: CsvSeparator) : RegionalSettingsAction

    /** `Confirmar configuración`, `Guardar cambios` o `Reintentar`. */
    data object Save : RegionalSettingsAction

    data object ConfirmZoneChange : RegionalSettingsAction

    data object CancelZoneChange : RegionalSettingsAction

    /** Ya se mostró que quedó guardada. */
    data object SavedShown : RegionalSettingsAction

    /** Confirmó corregir la moneda a la elegida en la hoja de corrección. */
    data object ConfirmCorrection : RegionalSettingsAction

    /** No quiso corregir la moneda: se cierra la pregunta sin cambiar nada. */
    data object CancelCorrection : RegionalSettingsAction

    /** Ya se mostró que la moneda quedó corregida. */
    data object CorrectionShown : RegionalSettingsAction
}
