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
 */
@RunWith(AndroidJUnit4::class)
class LanguageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val testDirectory = File(app.noBackupFilesDir, "language-device-test")
    private val appFile = File(app.noBackupFilesDir, LanguagePreferenceFile.FILE_NAME)
    private var appFileBefore: ByteArray? = null
    private var systemBefore: LocaleList? = null

    @Before
    fun rememberLanguage() {
        testDirectory.deleteRecursively()
        appFileBefore = if (appFile.isFile) appFile.readBytes() else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBefore = localeManager().applicationLocales
        }
    }

    @After
    fun restoreLanguage() {
        testDirectory.deleteRecursively()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val before = systemBefore ?: LocaleList.getEmptyLocaleList()
            if (localeManager().applicationLocales != before) {
                localeManager().applicationLocales = before
                // El proceso copia el idioma de Android a su archivo al cambiar la configuración:
                // se espera a que termine antes de devolver el archivo a como estaba.
                awaitAppFile(UiLanguage.of(if (before.isEmpty) "" else before[0].toLanguageTag()))
            }
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

        localeManager().applicationLocales = LocaleList.forLanguageTags(other)

        // Nadie más escribe el archivo: lo copia el proceso de la app al cambiar la configuración.
        awaitAppFile(chosen(other))
        assertEquals(LanguageRead.Found(chosen(other)), appLanguageFile().read())
        assertEquals(chosen(other), app.container.language.language.value)
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
        awaitAppFile(UiLanguage.System)
    }

    private fun localeManager(): LocaleManager = app.getSystemService(LocaleManager::class.java)

    /** Espera a que el archivo de la app diga [expected], o a que un archivo ausente siga así. */
    private fun awaitAppFile(expected: UiLanguage?) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val read = appLanguageFile().read()
            val settled =
                (expected != null && read == LanguageRead.Found(expected)) ||
                    (read == LanguageRead.Missing && expected == UiLanguage.System)
            if (settled) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("El archivo de idioma no llegó al idioma esperado")
    }

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
    }
}
