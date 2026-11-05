package io.github.yesidrobayo.shopledger.localization

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Lo que [AppLanguage] le pide a Android. Existe para probar [AppLanguage] en el PC con un doble: la
 * app usa [AndroidLocalePlatform].
 */
interface LocalePlatform {
    /**
     * Si Android guarda y aplica el idioma de la app por su cuenta, como pasa desde Android 13. Con
     * `true`, lo que diga Android manda, porque la persona también puede cambiarlo en sus ajustes.
     */
    val systemManagesAppLanguage: Boolean

    /**
     * El idioma que Android tiene para esta app, solo si [systemManagesAppLanguage]. Es una
     * llamada al sistema que puede tocar su disco: va fuera del hilo principal.
     */
    fun systemAppLanguage(): UiLanguage

    /**
     * Le pasa a Android el idioma de esta app, solo si [systemManagesAppLanguage]. Android lo
     * guarda y lo aplica a todo el proceso. También puede tocar el disco del sistema: va fuera del
     * hilo principal.
     */
    fun setSystemAppLanguage(language: UiLanguage)

    /**
     * Aplica el idioma a las actividades de este proceso, solo si no [systemManagesAppLanguage]. Va
     * en el hilo principal. Sin actividades vivas lo guarda en memoria, y la primera actividad lo
     * toma al crearse, sin recrearse. Con actividades vivas, las recrea en el idioma nuevo.
     */
    fun applyToActivities(language: UiLanguage)

    /**
     * Corre [block] con permiso de StrictMode para tocar el disco en este hilo. Solo para la lectura
     * corta y deliberada del arranque: StrictMode sigue viendo todo lo demás.
     *
     * Admite también escribir, porque en una instalación nueva pedir `noBackupFilesDir` crea la
     * carpeta: es la única escritura posible dentro del permiso, y pasa una vez.
     */
    fun <T> withStartupDiskPermit(block: () -> T): T
}

/**
 * [LocalePlatform] sobre Android: `LocaleManager` desde Android 13, y AppCompat en Android 8 a 12L,
 * donde Android no tiene idioma por app.
 *
 * En Android 8 a 12L, AppCompat solo guarda el idioma en memoria, porque la app no usa su
 * almacenamiento automático, que lee el disco en el hilo principal: cada arranque lo vuelve a
 * aplicar desde el archivo de idioma.
 */
class AndroidLocalePlatform(context: Context) : LocalePlatform {
    private val app = context.applicationContext

    override val systemManagesAppLanguage: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun systemAppLanguage(): UiLanguage {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return UiLanguage.System
        val locales = app.getSystemService(LocaleManager::class.java).applicationLocales
        // Android admite una lista, pero sus ajustes y esta app solo fijan un idioma: vale el
        // primero. Una etiqueta que no se entiende sigue al teléfono.
        if (locales.isEmpty) return UiLanguage.System
        return UiLanguage.of(locales[0].toLanguageTag()) ?: UiLanguage.System
    }

    override fun setSystemAppLanguage(language: UiLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        app.getSystemService(LocaleManager::class.java).applicationLocales =
            when (language) {
                UiLanguage.System -> LocaleList.getEmptyLocaleList()
                is UiLanguage.Chosen -> LocaleList.forLanguageTags(language.tag)
            }
    }

    override fun applyToActivities(language: UiLanguage) {
        if (systemManagesAppLanguage) return
        AppCompatDelegate.setApplicationLocales(
            when (language) {
                UiLanguage.System -> LocaleListCompat.getEmptyLocaleList()
                is UiLanguage.Chosen -> LocaleListCompat.forLanguageTags(language.tag)
            },
        )
    }

    override fun <T> withStartupDiskPermit(block: () -> T): T {
        // Permitir escribir incluye leer.
        val previous = StrictMode.allowThreadDiskWrites()
        try {
            return block()
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }
}
