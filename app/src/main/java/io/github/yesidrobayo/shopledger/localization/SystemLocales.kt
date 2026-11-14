package io.github.yesidrobayo.shopledger.localization

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * El idioma del teléfono, sin el que la persona eligió para la app.
 *
 * Desde Android 13, el idioma por app se aplica a todo el proceso, también a
 * `Resources.getSystem()`: leerlo ahí devuelve el idioma de la app, no el del teléfono. Por eso
 * desde Android 13 se le pregunta a `LocaleManager`, que da los idiomas del sistema sin el de la
 * app. Antes de Android 13, AppCompat solo cambia el idioma de las actividades, y la configuración
 * del sistema sigue siendo la del teléfono.
 */
object SystemLocales {
    /** El primer idioma del teléfono, o [Locale.ROOT] si Android no da ninguno. */
    fun first(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val locales = manager?.systemLocales
            if (locales != null && !locales.isEmpty) return locales[0]
        }
        return Resources.getSystem().configuration.locales.get(0) ?: Locale.ROOT
    }
}
