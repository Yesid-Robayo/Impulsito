package io.github.yesidrobayo.shopledger.domain.model.backup

import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/**
 * La revisión que impide que los metadatos o la cabecera de una copia lleven una moneda, una región
 * o una zona.
 *
 * No busca palabras prohibidas, que se esquivan con una abreviatura o un sinónimo. Hace lo
 * contrario, en dos frentes:
 *
 * - **Nombres:** cada nombre debe armarse solo con palabras de [NEUTRAL_WORDS], un vocabulario
 *   cerrado y revisado. Cualquier palabra nueva, sea `currency`, `cur`, `tz` u `offset`, falla hasta
 *   que alguien la agregue aquí a propósito, después de comprobar que no es un dato regional.
 * - **Valores:** cada forma de valor se prueba contra [RegionalCorpus], que no es una lista escrita a
 *   mano: son todas las combinaciones de dos y tres letras, más los códigos, símbolos, idiomas,
 *   regiones y zonas que trae la JVM. Una forma que acepte cualquiera de ellos falla.
 *
 * Las formas numéricas aceptan números, y un número puede ser un código numérico de moneda o un
 * desfase en minutos. Lo que dice qué significa un número es el nombre de su clave, y el nombre pasa
 * por el vocabulario cerrado.
 */
object NeutralityReview {
    /**
     * Las únicas palabras con que se arma el nombre de una clave o de un campo de la cabecera. Todas
     * hablan del archivo, de su identidad, de sus tamaños y conteos, o de marcas del respaldo.
     * Ninguna nombra una moneda, una región, una zona, un idioma ni un formato regional.
     *
     * Agregar una palabra exige revisar que no lo haga. `utc` está porque es la base universal de
     * tiempo, no una zona: es lo que hace que la copia no dependa de ninguna.
     */
    val NEUTRAL_WORDS: Set<String> =
        setOf(
            // Prefijo de las claves de metadatos.
            "sl",
            // Identidad y clase del archivo.
            "kind", "format", "magic", "header", "media", "device", "id", "label", "key",
            "source", "aead", "payload", "codec", "reserved", "extensions",
            // Versiones y orden.
            "generation", "schema", "version", "app",
            // Tamaños, sumas y conteos.
            "len", "raw", "size", "file", "sha256", "records", "catalog", "count",
            // Marcas del respaldo.
            "valid", "fatal", "pinned",
            // Instante de creación, siempre en milisegundos UTC.
            "created", "at", "utc", "ms",
        )

    /** Las palabras de [name] que no están en el vocabulario neutro, o la forma rota del nombre. */
    fun nameProblems(name: String): List<String> {
        if (!name.matches(SNAKE_CASE)) return listOf("no es snake_case ASCII: $name")
        return name.split('_').filterNot { it in NEUTRAL_WORDS }
    }

    /** Los valores regionales que [form] acepta. Una forma neutra no acepta ninguno. */
    fun acceptedRegionalValues(form: MetaValueForm): List<String> =
        RegionalCorpus.values.filter(form::accepts)

    private val SNAKE_CASE = Regex("[a-z0-9]+(_[a-z0-9]+)*")
}

/**
 * Identificadores de moneda, región, idioma y zona en todas sus formas, generados o tomados de la
 * JVM, nunca escritos a mano.
 */
object RegionalCorpus {
    val values: Set<String> by lazy { build() }

    private fun build(): Set<String> = buildSet {
        // La forma de los códigos de moneda, de país y de idioma, existan o no: toda combinación de
        // dos y tres letras ASCII, en mayúscula y en minúscula.
        val letters = 'A'..'Z'
        for (first in letters) {
            for (second in letters) {
                addBothCases("$first$second")
                for (third in letters) addBothCases("$first$second$third")
            }
        }
        // Las monedas que conoce la JVM, con su código y su símbolo en cada idioma.
        val locales = Locale.getAvailableLocales().toList()
        for (currency in Currency.getAvailableCurrencies()) {
            add(currency.currencyCode)
            add(currency.getSymbol(Locale.ROOT))
            for (locale in locales) add(currency.getSymbol(locale))
        }
        // Los símbolos de moneda de Unicode.
        for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
            val char = code.toChar()
            if (char.category == CharCategory.CURRENCY_SYMBOL) add(char.toString())
        }
        // Idiomas y regiones: la etiqueta estándar, la forma con guion bajo y la región sola.
        for (locale in locales) {
            add(locale.toLanguageTag())
            add(locale.toString())
            add(locale.country)
        }
        addAll(Locale.getISOCountries())
        addAll(Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA3))
        addAll(Locale.getISOLanguages())
        // Todas las zonas de la base de zonas, y los desfases de -18:00 a +18:00 cada 15 minutos en
        // las formas habituales.
        addAll(ZoneId.getAvailableZoneIds())
        addAll(ZoneId.SHORT_IDS.keys)
        addAll(listOf("Z", "UTC", "GMT", "UT"))
        for (quarters in -72..72) {
            val sign = if (quarters < 0) "-" else "+"
            val hours = abs(quarters) / 4
            val minutes = abs(quarters) % 4 * 15
            val hh = hours.toString().padStart(2, '0')
            val mm = minutes.toString().padStart(2, '0')
            for (prefix in listOf("", "UTC", "GMT")) {
                add("$prefix$sign$hh:$mm")
                add("$prefix$sign$hh$mm")
                add("$prefix$sign$hours")
            }
        }
        // Un valor vacío no es regional, y lo rechazan las formas por su cuenta. Los códigos
        // numéricos, como el de una moneda o el de una región de varios países, son números: un
        // número de la copia es un conteo, y su significado lo fija el nombre de su clave.
        remove("")
        removeAll { value -> value.all { it in '0'..'9' } }
    }

    private fun MutableSet<String>.addBothCases(code: String) {
        add(code)
        add(code.lowercase())
    }
}
