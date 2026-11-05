package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import kotlinx.datetime.DayOfWeek

/**
 * La matriz de configuraciones regionales: las mismas filas, en el mismo orden, que la tabla de la
 * estrategia de pruebas. Cada fila combina moneda, decimales, redondeo de efectivo, formato
 * regional, zona, primer día de la semana, idioma de la interfaz y modo de impuesto, elegidos para
 * atacar un supuesto distinto.
 *
 * Es el único lugar del código donde se nombran monedas, regiones y zonas reales, y son datos de
 * prueba: ningún cálculo las supone. La comparten las pruebas de `:domain`, que corren en `check`,
 * las de `:app` en el dispositivo y las de los `ViewModel`, que la reciben desde estos accesorios de
 * prueba. Una fila nueva corre en todas ellas sin tocar las pruebas.
 */
object RegionalMatrix {
    // Abreviaturas de la tabla. Van primero: un objeto inicializa sus propiedades en orden.
    private const val NO_BREAK_SPACE = '\u00A0'
    private val MONDAY = DayOfWeek.MONDAY
    private val SUNDAY = DayOfWeek.SUNDAY
    private val POINT = DecimalSeparator.POINT
    private val COMMA = DecimalSeparator.COMMA

    /**
     * La región que hace de teléfono en las pruebas de la JVM, para la fila que toma el formato, la
     * zona y la semana del dispositivo. Ninguna fila fija la usa: agrupa cifras con un espacio y
     * tiene un desfase de tres cuartos de hora, así que ataca lo que las demás no cubren. En el
     * dispositivo, esa fila usa en cambio la región del teléfono.
     */
    val INVENTED_DEVICE: MatrixRegion =
        MatrixRegion(
            formatLocaleTag = "fr-FR",
            businessTimeZoneId = "Pacific/Chatham",
            weekStartDay = DayOfWeek.MONDAY,
            decimalSeparator = DecimalSeparator.COMMA,
            grouping = DigitGrouping(NO_BREAK_SPACE, primarySize = 3, secondarySize = 3),
        )

    /** Las filas, en el orden de la tabla de la estrategia de pruebas. */
    val ROWS: List<RegionalMatrixRow> =
        listOf(
            // Sin decimales, con el formato de un idioma que la app no tiene.
            row("JPY", 0, 0, region("ja-JP", "Asia/Tokyo", SUNDAY, POINT, ','), "en", TaxMode.NONE),
            // Sin decimales, cifras muy grandes, topes y un redondeo de efectivo de mil.
            row(
                "VND",
                0,
                1_000,
                region("vi-VN", "Asia/Ho_Chi_Minh", MONDAY, COMMA, '.'),
                "en",
                TaxMode.NONE,
            ),
            // Desfase negativo y horario de verano, con el impuesto sumado al precio.
            row(
                "USD",
                2,
                0,
                region("en-US", "America/Los_Angeles", SUNDAY, POINT, ','),
                "en",
                TaxMode.ADDED,
            ),
            // Coma decimal, columnas con punto y coma e impuesto incluido.
            row(
                "EUR",
                2,
                0,
                region("es-ES", "Europe/Madrid", MONDAY, COMMA, '.'),
                "es",
                TaxMode.INCLUDED,
            ),
            // El tercer idioma de la interfaz y coma decimal.
            row(
                "BRL",
                2,
                0,
                region("pt-BR", "America/Sao_Paulo", SUNDAY, COMMA, '.'),
                "pt",
                TaxMode.NONE,
            ),
            // Media hora positiva, grupos de tres y luego de dos, e idioma distinto del formato.
            row(
                "INR",
                2,
                0,
                region("en-IN", "Asia/Kolkata", MONDAY, POINT, ',', secondaryGroupSize = 2),
                "es",
                TaxMode.NONE,
            ),
            // Media hora negativa y redondeo de efectivo.
            row(
                "CAD",
                2,
                5,
                region("en-CA", "America/St_Johns", SUNDAY, POINT, ','),
                "pt",
                TaxMode.NONE,
            ),
            // Separador de miles poco común, con el formato de un idioma que la app no tiene.
            row(
                "CHF",
                2,
                5,
                region("de-CH", "Europe/Zurich", MONDAY, POINT, '\''),
                "es",
                TaxMode.NONE,
            ),
            // Tres decimales y semana desde el sábado.
            row(
                "KWD",
                3,
                0,
                region("en-KW", "Asia/Kuwait", DayOfWeek.SATURDAY, POINT, ','),
                "en",
                TaxMode.NONE,
            ),
            // El máximo de decimales de la moneda.
            row(
                "CLF",
                4,
                0,
                region("es-CL", "America/Santiago", MONDAY, COMMA, '.'),
                "es",
                TaxMode.NONE,
            ),
            // Moneda sin confirmar: formato, zona y semana salen del teléfono, y sus decimales
            // valen cero hasta que la persona elija una moneda.
            row("XXX", 0, 0, null, null, TaxMode.NONE),
            // Costo que sube cada semana, con impuesto incluido.
            row(
                "ARS",
                2,
                0,
                region("es-AR", "America/Argentina/Buenos_Aires", MONDAY, COMMA, '.'),
                "es",
                TaxMode.INCLUDED,
            ),
            // Punto decimal con la interfaz en español.
            row(
                "MXN",
                2,
                0,
                region("es-MX", "America/Mexico_City", SUNDAY, POINT, ','),
                "es",
                TaxMode.INCLUDED,
            ),
            // Sin decimales, con impuesto incluido y formato en inglés.
            row(
                "JPY",
                0,
                0,
                region("en-JP", "Asia/Tokyo", SUNDAY, POINT, ','),
                "en",
                TaxMode.INCLUDED,
            ),
            // Sin decimales, con la interfaz en español.
            row(
                "CLP",
                0,
                0,
                region("es-CL", "America/Santiago", MONDAY, COMMA, '.'),
                "es",
                TaxMode.INCLUDED,
            ),
            // Los decimales de la norma cambiados antes del bloqueo.
            row(
                "ARS",
                0,
                0,
                region("es-AR", "America/Argentina/Buenos_Aires", MONDAY, COMMA, '.'),
                "es",
                TaxMode.NONE,
            ),
            // El máximo de decimales con una moneda sin símbolo conocido.
            row("XTS", 4, 0, region("en", "Etc/UTC", SUNDAY, POINT, ','), "en", TaxMode.ADDED),
            // Una moneda que la norma define con dos decimales y se usa sin centavos, miles con
            // punto, redondeo de efectivo de cincuenta e impuesto incluido.
            row(
                "COP",
                0,
                50,
                region("es-CO", "America/Bogota", MONDAY, COMMA, '.'),
                "es",
                TaxMode.INCLUDED,
            ),
        )

    /**
     * Los parámetros de una prueba de JUnit que corre cada fila con cada zona e idioma extraños de
     * la JVM: el resultado no puede depender del equipo donde corre.
     */
    fun withForeignDefaults(): List<Array<Any>> =
        ROWS.flatMap { row -> ForeignSystemDefaults.ALL.map { defaults -> arrayOf(row, defaults) } }

    private fun row(
        currencyCode: String,
        currencyDecimals: Int,
        cashRounding: Long,
        region: MatrixRegion?,
        uiLanguageTag: String?,
        taxMode: TaxMode,
    ): RegionalMatrixRow = RegionalMatrixRow(
        currencyCode = requireNotNull(CurrencyCode.of(currencyCode)) { currencyCode },
        currencyDecimals = currencyDecimals,
        cashRounding = Money(cashRounding),
        region = region,
        uiLanguageTag = uiLanguageTag,
        taxMode = taxMode,
    )

    private fun region(
        formatLocaleTag: String,
        businessTimeZoneId: String,
        weekStartDay: DayOfWeek,
        decimalSeparator: DecimalSeparator,
        groupSeparator: Char,
        secondaryGroupSize: Int = 3,
    ): MatrixRegion = MatrixRegion(
        formatLocaleTag = formatLocaleTag,
        businessTimeZoneId = businessTimeZoneId,
        weekStartDay = weekStartDay,
        decimalSeparator = decimalSeparator,
        grouping = DigitGrouping(
            groupSeparator,
            primarySize = 3,
            secondarySize = secondaryGroupSize,
        ),
    )
}

/**
 * Una fila de la matriz de configuraciones regionales.
 *
 * - [cashRounding] es el paso del redondeo de efectivo, en unidad menor. Cero es sin redondeo.
 * - [region] es el formato, la zona y la semana de la fila, o `null` en la que los toma del
 *   teléfono: quien corre la prueba pone los del dispositivo con [regionOr].
 * - [uiLanguageTag] es el idioma de la interfaz, o `null` si sigue al del sistema. Nunca cambia una
 *   cifra: el idioma elige las palabras, y el formato regional, los números.
 */
data class RegionalMatrixRow(
    val currencyCode: CurrencyCode,
    val currencyDecimals: Int,
    val cashRounding: Money,
    val region: MatrixRegion?,
    val uiLanguageTag: String?,
    val taxMode: TaxMode,
) {
    /** La región de la fila, o [device] si la fila la toma del teléfono. */
    fun regionOr(device: MatrixRegion): MatrixRegion = region ?: device

    /**
     * Los ajustes regionales que guardaría un negocio con esta fila, con [device] como teléfono en
     * la fila que lo necesita. La moneda no está bloqueada, la persona no escribió un símbolo y el
     * separador del CSV es el que nace con el formato regional.
     */
    fun regionalSettings(device: MatrixRegion = RegionalMatrix.INVENTED_DEVICE): RegionalSettings {
        val region = regionOr(device)
        return RegionalSettings(
            currency =
                BusinessCurrency(
                    code = currencyCode,
                    decimals = currencyDecimals,
                    symbol = null,
                    lockedAt = null,
                ),
            cashRounding = cashRounding,
            formatLocaleTag = region.formatLocaleTag,
            businessTimeZoneId = region.businessTimeZoneId,
            weekStartDay = region.weekStartDay,
            csvSeparator = region.csvSeparator,
            referenceCurrency = null,
        )
    }

    /** El nombre de la fila en los resultados de las pruebas, distinto en cada una. */
    override fun toString(): String =
        "$currencyCode $currencyDecimals ${region?.formatLocaleTag ?: "device"}"
}

/**
 * El formato regional, la zona y el primer día de la semana de una fila, con los símbolos con que
 * la app escribe sus números en ese formato.
 *
 * Los símbolos son los que la app muestra después de normalizar lo que da ICU, y valen para montos
 * y cantidades: en las regiones de la matriz los dos usan los mismos. En la JVM no hay ICU de
 * Android, así que aquí son datos escritos a mano; la prueba de la matriz en el dispositivo
 * comprueba que coinciden con los de los formateadores en cada versión de Android.
 */
data class MatrixRegion(
    val formatLocaleTag: String,
    val businessTimeZoneId: String,
    val weekStartDay: DayOfWeek,
    /** El separador decimal de cualquier número de la región, también el del CSV. */
    val decimalSeparator: DecimalSeparator,
    /** La agrupación de las cifras enteras en pantalla. */
    val grouping: DigitGrouping,
) {
    /** Los símbolos con que el dominio lee lo que la persona escribe en esta región. */
    val numberSymbols: NumberSymbols
        get() =
            NumberSymbols(
                decimalSeparator = decimalSeparator.char,
                minusSign = NumberSymbols.TYPOGRAPHIC_MINUS,
                grouping = grouping,
            )

    /** El separador de columnas con que nace el CSV en esta región. */
    val csvSeparator: CsvSeparator get() = CsvSeparator.defaultFor(decimalSeparator)
}
