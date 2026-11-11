package io.github.yesidrobayo.shopledger.localization

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: dónde vive el archivo de idioma, que su escritura sincroniza de verdad, y cómo se
 * aplica el idioma en cada nivel de Android.
 *
 * Las pruebas de este archivo usan su propio archivo de idioma, en una carpeta aparte, salvo las de
 * Android 13 o superior que miran el de la app, porque ahí el proceso lo actualiza solo al cambiar
 * el idioma en Android. Todas dejan el idioma de la app, el de Android y el archivo como estaban.
 *
 * Desde Android 13, cada cambio del idioma en Android le llega a la app como un cambio de
 * configuración, y la app lo copia a memoria y al archivo un momento después, fuera de la prueba.
 * Por eso cada prueba empieza y termina esperando a que todo quede quieto: Android, la memoria y el
 * archivo dicen lo mismo, y ningún cambio de configuración llegó en el último medio segundo. Sin esa
 * espera, la copia que deja pendiente la limpieza de una prueba cae en medio de la siguiente. Una
 * línea de tiempo anota el orden de esos cambios y se suma al mensaje si algo falla.
 */
@RunWith(AndroidJUnit4::class)
class LanguageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val testDirectory = File(app.noBackupFilesDir, "language-device-test")
    private val appFile = File(app.noBackupFilesDir, LanguagePreferenceFile.FILE_NAME)
    private var appFileBefore: ByteArray? = null
    private var systemBefore: LocaleList? = null
    private var timeline: LanguageTimeline? = null

    @Before
    fun rememberLanguage() {
        testDirectory.deleteRecursively()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            timeline = LanguageTimeline(app, appFile)
            // Lo que dejó la prueba anterior ya llegó a la memoria y al archivo antes de empezar.
            awaitSettled(androidLanguage())
            systemBefore = localeManager().applicationLocales
        }
        appFileBefore = if (appFile.isFile) appFile.readBytes() else null
    }

    @After
    fun restoreLanguage() {
        testDirectory.deleteRecursively()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val before = systemBefore ?: LocaleList.getEmptyLocaleList()
            if (localeManager().applicationLocales != before) {
                localeManager().applicationLocales = before
            }
            // El proceso copia el idioma de Android a su memoria y a su archivo al cambiar la
            // configuración: se espera a que termine antes de devolver el archivo a como estaba.
            awaitSettled(languageOf(before))
            timeline?.stop()
        } else {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
        }
        val bytes = appFileBefore
        if (bytes == null) appFile.delete() else appFile.writeBytes(bytes)
    }

    @Test
    fun theLanguageLivesOutsideTheDatabaseFolder() {
        val databaseFolder = requireNotNull(app.getDatabasePath("probe").parentFile)

        assertEquals(app.noBackupFilesDir, appFile.parentFile)
        assertNotEquals(databaseFolder.absoluteFile, appFile.parentFile?.absoluteFile)
        assertFalse(appFile.absolutePath.startsWith(databaseFolder.absolutePath + File.separator))
    }

    @Test
    fun aWriteOnTheDeviceSyncsAndReadsBack() {
        val file = LanguagePreferenceFile({ testDirectory })

        file.write(chosen("pt"))
        file.write(chosen("es"))

        assertEquals(LanguageRead.Found(chosen("es")), file.read())
        assertEquals(listOf(LanguagePreferenceFile.FILE_NAME), testDirectory.list()?.toList())
    }

    @Test
    fun beforeAndroid13TheFirstScreenAlreadyShowsTheSavedLanguage() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        LanguagePreferenceFile({ testDirectory }).write(chosen("es"))
        val language = languageInTestFolder()
        val created = CreationCounter().also { app.registerActivityLifecycleCallbacks(it) }
        try {
            // Como en el arranque del proceso: sin actividades vivas, en el hilo principal.
            instrumentation.runOnMainSync { language.applyAtProcessStart() }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals("es", activity.resources.configuration.locales[0].language)
                    assertEquals("Inicio", activity.getString(R.string.nav_home))
                }
                // Una sola actividad: el idioma no llegó con una recreación, que se vería como un
                // cambio de idioma ante la persona.
                assertEquals(1, created.count)

                // Con la actividad viva, elegir otro idioma sí la recrea, ya en ese idioma.
                assertEquals(LanguageChoice.Applied, runBlocking { language.choose(chosen("pt")) })
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals("Início", activity.getString(R.string.nav_home))
                }
                assertEquals(2, created.count)
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(created)
        }
    }

    @Test
    fun beforeAndroid13NothingIsSavedFollowsThePhone() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        val language = languageInTestFolder()

        instrumentation.runOnMainSync { language.applyAtProcessStart() }

        assertEquals(UiLanguage.System, language.language.value)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun fromAndroid13ALanguageChangedInAndroidIsCopiedToTheFile() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val other = if (systemBefore?.toLanguageTags() == "pt") "es" else "pt"

        timeline?.note("la prueba pide $other a Android")
        localeManager().applicationLocales = LocaleList.forLanguageTags(other)

        // Nadie más escribe el archivo: lo copia el proceso de la app al cambiar la configuración.
        awaitSettled(chosen(other))
        assertEquals(
            "Archivo distinto. Línea de tiempo:\n$timeline",
            LanguageRead.Found(chosen(other)),
            appLanguageFile().read(),
        )
        assertEquals(
            "Memoria distinta. Línea de tiempo:\n$timeline",
            chosen(other),
            app.container.language.language.value,
        )
    }

    @Test
    fun fromAndroid13ChoosingGoesThroughAndroid() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val language = languageInTestFolder()

        val result = runBlocking { language.choose(chosen("pt")) }

        assertEquals(LanguageChoice.Applied, result)
        assertEquals("pt", localeManager().applicationLocales.toLanguageTags())
        assertEquals(
            LanguageRead.Found(chosen("pt")),
            LanguagePreferenceFile({
                testDirectory
            }).read(),
        )
    }

    @Test
    fun onReachingAndroid13TheSavedLanguageIsHandedToAndroidOnce() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        // Como un teléfono que llega a Android 13: Android sin idioma para la app, y el archivo con
        // el idioma elegido antes, sin la marca del paso.
        clearAndroidLanguage()
        val file = LanguagePreferenceFile({ testDirectory })
        file.write(chosen("es"))
        val language = languageInTestFolder()

        runBlocking { language.syncWithSystem() }

        assertEquals("es", localeManager().applicationLocales.toLanguageTags())
        assertEquals(chosen("es"), language.language.value)
        assertTrue(file.handedToSystem)

        // Después manda Android: la persona vuelve al idioma del teléfono desde sus ajustes.
        clearAndroidLanguage()
        runBlocking { language.syncWithSystem() }

        assertTrue(localeManager().applicationLocales.isEmpty)
        assertEquals(LanguageRead.Found(UiLanguage.System), file.read())
    }

    @Test
    fun fromAndroid13TheStartLeavesTheLanguageToAndroid() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        LanguagePreferenceFile({ testDirectory }).write(chosen("es"))
        val language = languageInTestFolder()

        instrumentation.runOnMainSync { language.applyAtProcessStart() }

        assertEquals(systemBefore, localeManager().applicationLocales)
    }

    private fun languageInTestFolder(): AppLanguage = AppLanguage(
        LanguagePreferenceFile({ testDirectory }),
        AndroidLocalePlatform(app),
        app.container.log,
        Dispatchers.IO,
        Dispatchers.Main,
    )

    private fun appLanguageFile() = LanguagePreferenceFile({ app.noBackupFilesDir })

    // Quita el idioma de Android para la app, y espera a que el proceso lo copie a su archivo.
    private fun clearAndroidLanguage() {
        if (localeManager().applicationLocales.isEmpty) return
        localeManager().applicationLocales = LocaleList.getEmptyLocaleList()
        awaitSettled(UiLanguage.System)
    }

    private fun androidLanguage(): UiLanguage = languageOf(localeManager().applicationLocales)

    private fun languageOf(locales: LocaleList): UiLanguage =
        UiLanguage.of(if (locales.isEmpty) "" else locales[0].toLanguageTag()) ?: UiLanguage.System

    /**
     * Espera a que todo quede quieto en [expected]: Android, el idioma en memoria y el archivo de la
     * app dicen lo mismo, y así siguen sin ningún cambio de configuración durante [QUIET_MILLIS].
     * Un archivo ausente vale como el idioma del teléfono, como en un teléfono nuevo.
     */
    private fun awaitSettled(expected: UiLanguage) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        var quietSince: Long? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val read = appLanguageFile().read()
            val now = SystemClock.elapsedRealtime()
            val agreed =
                androidLanguage() == expected &&
                    app.container.language.language.value == expected &&
                    (
                        read == LanguageRead.Found(expected) ||
                            (read == LanguageRead.Missing && expected == UiLanguage.System)
                        )
            val lastChange = timeline?.lastConfigurationAt ?: 0L
            if (!agreed || now - lastChange < QUIET_MILLIS) {
                quietSince = null
            } else {
                val since = quietSince ?: now.also { quietSince = it }
                if (now - since >= QUIET_MILLIS) return
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("El idioma no quedó quieto en ${expected.fileValue}:\n$timeline")
    }

    private fun localeManager(): LocaleManager = app.getSystemService(LocaleManager::class.java)

    private fun chosen(tag: String): UiLanguage = requireNotNull(UiLanguage.of(tag))

    /** Cuenta las actividades creadas mientras está registrado. */
    private class CreationCounter : Application.ActivityLifecycleCallbacks {
        @Volatile var count = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) count += 1
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L

        /** Cuánto debe seguir todo igual, sin cambios de configuración, para darlo por quieto. */
        const val QUIET_MILLIS = 500L
    }
}
