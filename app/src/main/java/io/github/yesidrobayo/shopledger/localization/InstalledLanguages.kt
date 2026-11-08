package io.github.yesidrobayo.shopledger.localization

import android.content.res.Resources
import android.content.res.XmlResourceParser
import io.github.yesidrobayo.shopledger.R
import org.xmlpull.v1.XmlPullParser

/**
 * Los idiomas que trae la app, leídos de la configuración que la compilación genera con sus
 * carpetas de recursos. Agregar un idioma es agregar su carpeta: esta lista crece sola, y ningún
 * idioma está escrito en el código.
 *
 * Es la misma configuración que Android muestra en sus ajustes de idioma por app desde Android 13,
 * así que la lista de la app y la de Android coinciden. Se lee igual en todas las versiones.
 */
object InstalledLanguages {
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val LOCALE_TAG = "locale"
    private const val NAME_ATTRIBUTE = "name"

    /**
     * Las etiquetas de los idiomas de [resources], en el orden de la configuración, sin los
     * pseudoidiomas que agrega la compilación de depuración para probar textos largos y escritura de
     * derecha a izquierda: no son idiomas que alguien elija.
     */
    fun tags(resources: Resources): List<String> =
        resources.getXml(R.xml._generated_res_locale_config).use(::read).filterNot(::isPseudoLocale)

    /** Las etiquetas de cada `<locale android:name="…">` del archivo. */
    private fun read(parser: XmlResourceParser): List<String> = buildList {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == LOCALE_TAG) {
                parser.getAttributeValue(ANDROID_NAMESPACE, NAME_ATTRIBUTE)?.let(::add)
            }
        }
    }

    /**
     * Si [tag] es un pseudoidioma de Android: `en-XA`, con acentos y textos largos, o `ar-XB`, de
     * derecha a izquierda. Sus regiones son códigos reservados para uso privado.
     */
    internal fun isPseudoLocale(tag: String): Boolean {
        val region = tag.substringAfter('-', missingDelimiterValue = "")
        return region.equals(PSEUDO_ACCENTS, ignoreCase = true) ||
            region.equals(PSEUDO_BIDI, ignoreCase = true)
    }

    private const val PSEUDO_ACCENTS = "XA"
    private const val PSEUDO_BIDI = "XB"
}
