package io.github.yesidrobayo.shopledger.localization

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.domain.port.ImportHeaderLabels

/**
 * Los encabezados con que puede llegar cada columna de un archivo para importar: el de la
 * exportación en cada idioma que trae la app, más sus alias. No depende del idioma elegido: un
 * archivo exportado en español entra igual con la app en inglés o en portugués.
 *
 * Cada idioma se lee con un contexto creado con `createConfigurationContext` en ese idioma, a
 * partir de la lista de idiomas instalados: agregar un idioma a la app lo suma aquí sin tocar
 * código. Los contextos se crean una vez, al pedir el primer encabezado, y nunca cambian el idioma
 * de la interfaz.
 *
 * Los alias son un recurso `csv_alias_<clave>` por columna, con varios nombres separados por
 * [ALIAS_SEPARATOR]. Empiezan vacíos: la clave en inglés y los encabezados de cada idioma ya
 * cubren un archivo exportado, y un alias solo se agrega si aparece un encabezado que esos dos no
 * cubren.
 *
 * Lint pide descargar de Google Play un idioma antes de usarlo, lo que solo hace falta con un
 * paquete de Play dividido por idioma: la app se instala como APK universal, con todos sus
 * idiomas.
 */
@SuppressLint("AppBundleLocaleChanges")
class ImportHeaderNaming(private val context: Context) : ImportHeaderLabels {
    private val languages: List<Resources> by lazy {
        InstalledLanguages.tags(context.resources).map(::resourcesIn)
    }

    override fun labels(key: String): List<String> {
        val header = CsvExportNaming.headerRes(key)
        val alias = aliasRes(key)
        return languages
            .flatMap { resources ->
                val aliases = alias?.let { resources.getString(it).split(ALIAS_SEPARATOR) }
                listOfNotNull(header?.let(resources::getString)) + aliases.orEmpty()
            }.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }

    private fun resourcesIn(tag: String): Resources {
        val configuration =
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            }
        return context.createConfigurationContext(configuration).resources
    }

    companion object {
        /** Lo que separa un alias del siguiente dentro de su recurso. */
        const val ALIAS_SEPARATOR: Char = '|'

        /**
         * El recurso de alias de la columna [key], o `null` si no tiene. Es una tabla escrita,
         * como la de los encabezados, para que el compilador compruebe que el recurso existe. Hoy
         * está vacía.
         */
        @StringRes
        fun aliasRes(key: String): Int? = ALIASES[key]

        private val ALIASES: Map<String, Int> = emptyMap()
    }
}
