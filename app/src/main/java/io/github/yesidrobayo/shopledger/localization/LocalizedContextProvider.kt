package io.github.yesidrobayo.shopledger.localization

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.StateFlow

/**
 * Da un contexto con el idioma de la interfaz a lo que corre fuera de una actividad: trabajos en
 * segundo plano, avisos y sus canales, widget y mosaico. Sus textos salen de aquí, nunca del
 * contexto de la app sin más.
 *
 * - **Android 13 o superior.** Android aplica el idioma de la app a todo el proceso, también al
 *   contexto de la app: se devuelve ese.
 * - **Android 8 a 12L.** AppCompat solo cambia el idioma de las actividades, y el contexto de la app
 *   sigue en el idioma del teléfono. Con un idioma fijado se devuelve un contexto creado con
 *   `createConfigurationContext` en ese idioma, y siguiendo al teléfono, el de la app.
 *
 * El idioma sale de [language], que ya está en memoria desde el arranque del proceso, también
 * cuando el sistema lo arranca solo para un trabajo: dar el contexto nunca toca el disco.
 *
 * Se puede llamar desde cualquier hilo.
 *
 * Lint pide descargar de Google Play un idioma antes de usarlo, lo que solo hace falta con un
 * paquete de Play dividido por idioma: la app se instala como APK universal, con todos sus idiomas.
 *
 * @param app el contexto de la aplicación.
 * @param language el idioma elegido, el de [AppLanguage.language].
 * @param systemManagesAppLanguage si Android aplica por su cuenta el idioma de la app, como pasa
 *   desde Android 13. Las pruebas lo fijan para recorrer el camino de Android 8 a 12L en cualquier
 *   versión.
 */
@SuppressLint("AppBundleLocaleChanges")
class LocalizedContextProvider(
    private val app: Context,
    private val language: StateFlow<UiLanguage>,
    private val systemManagesAppLanguage: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
) {
    // Crear un contexto cuesta: se reutiliza mientras no cambien el idioma ni el resto de la
    // configuración, como el modo oscuro o el tamaño de letra, que también cambian sus recursos.
    private var cached: Pair<Configuration, Context>? = null

    /** El contexto con el idioma de la interfaz. */
    fun context(): Context {
        if (systemManagesAppLanguage) return app
        val chosen = language.value as? UiLanguage.Chosen ?: return app
        val wanted =
            Configuration(app.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(chosen.tag))
            }
        synchronized(this) {
            cached?.let { (configuration, context) -> if (configuration == wanted) return context }
            return app.createConfigurationContext(wanted).also { cached = wanted to it }
        }
    }
}
