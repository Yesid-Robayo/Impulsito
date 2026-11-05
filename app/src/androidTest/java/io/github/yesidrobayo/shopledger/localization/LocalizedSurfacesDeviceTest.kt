package io.github.yesidrobayo.shopledger.localization

import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.UserText
import io.github.yesidrobayo.shopledger.data.UserTextPort
import io.github.yesidrobayo.shopledger.notification.ChannelImportance
import io.github.yesidrobayo.shopledger.notification.NotificationChannelSpec
import io.github.yesidrobayo.shopledger.notification.NotificationChannels
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: lo que corre fuera de una actividad recibe sus textos en el idioma de la
 * interfaz, no en el del teléfono. Un trabajo los recibe por el puerto de la capa de datos, y un
 * canal de avisos cambia de nombre con el mismo ID al cambiar de idioma.
 *
 * Elige el idioma con el de la app, como lo hará el selector, y al terminar deja como estaban el
 * idioma de la app, el de Android para la app, el archivo de idioma y la marca de las superficies.
 * El canal de prueba se borra.
 */
@RunWith(AndroidJUnit4::class)
class LocalizedSurfacesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private val container = app.container
    private val testDirectory = File(app.noBackupFilesDir, "localized-surfaces-test")
    private val appFiles =
        listOf(LanguagePreferenceFile.FILE_NAME, LocalizedSurfaces.MARKER_FILE)
            .map { File(app.noBackupFilesDir, it) }
    private val filesBefore = mutableMapOf<File, ByteArray?>()
    private var languageBefore: UiLanguage = UiLanguage.System
    private var resourcesBefore = ""
    private var systemBefore: LocaleList? = null

    @Before
    fun rememberLanguage() {
        testDirectory.deleteRecursively()
        appFiles.forEach { filesBefore[it] = if (it.isFile) it.readBytes() else null }
        languageBefore = container.language.language.value
        resourcesBefore = app.getString(R.string.resources_language)
        if (systemManagesLanguage) systemBefore = localeManager().applicationLocales
    }

    @After
    fun restoreLanguage() {
        notificationManager().deleteNotificationChannel(TEST_CHANNEL_ID)
        testDirectory.deleteRecursively()
        if (systemManagesLanguage) {
            val before = systemBefore ?: LocaleList.getEmptyLocaleList()
            if (localeManager().applicationLocales != before) {
                localeManager().applicationLocales = before
                // El proceso copia el idioma de Android a su archivo al cambiar la configuración:
                // se espera a que lo aplique antes de devolver los archivos a como estaban.
                awaitResourcesLanguage(resourcesBefore)
                runBlocking { container.language.syncWithSystem() }
            }
        } else {
            runBlocking { container.language.choose(languageBefore) }
        }
        filesBefore.forEach { (file, bytes) ->
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
    }

    @Test
    fun beforeAndroid13ThePhoneLanguageIsReplacedByAContextInTheChosenOne() {
        val other = otherLanguage()
        val language = MutableStateFlow<UiLanguage>(chosen(other))
        // El camino de Android 8 a 12L, en cualquier versión: un contexto creado en ese idioma.
        val provider = LocalizedContextProvider(app, language, systemManagesAppLanguage = false)

        val texts = provider.context()

        assertEquals(other, texts.getString(R.string.resources_language))
        assertNotEquals(other, app.getString(R.string.resources_language))
        // Mientras nada cambie, es el mismo contexto: no se crea uno por texto.
        assertSame(texts, provider.context())

        language.value = UiLanguage.System
        assertSame(app, provider.context())
    }

    @Test
    fun fromAndroid13TheAppContextAlreadyHasTheLanguage() {
        val provider =
            LocalizedContextProvider(
                app,
                MutableStateFlow(chosen("pt")),
                systemManagesAppLanguage = true,
            )

        assertSame(app, provider.context())
    }

    @Test
    fun aWorkerGetsItsTextInTheChosenLanguageThroughThePort() {
        val other = otherLanguage()

        chooseAndWait(other)

        val worker =
            TestListenableWorkerBuilder<TextCheckWorker>(app)
                .setWorkerFactory(TextCheckWorkerFactory(container.userText))
                .build()
        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(workDataOf(TEXT to other)), result)
        if (!systemManagesLanguage) {
            // Hasta Android 12L el contexto de la app sigue en el idioma del teléfono: sin el
            // puerto, el trabajo habría salido en ese idioma.
            assertNotEquals(other, app.getString(R.string.resources_language))
        }
    }

    @Test
    fun changingTheLanguageRecreatesTheChannelWithTheSameId() {
        val surfaces = testSurfaces()
        val first = app.getString(R.string.resources_language)
        val other = otherLanguage()

        assertEquals(SurfacesRefresh.REFRESHED, runBlocking { surfaces.refreshLocalizedSurfaces() })
        assertEquals(first, testChannel().name)
        assertEquals(
            SurfacesRefresh.UP_TO_DATE,
            runBlocking {
                surfaces.refreshLocalizedSurfaces()
            },
        )

        chooseAndWait(other)
        assertEquals(SurfacesRefresh.REFRESHED, runBlocking { surfaces.refreshLocalizedSurfaces() })

        val channel = testChannel()
        assertEquals(other, channel.name)
        assertEquals(
            container.localizedContext.context().getString(R.string.nav_home),
            channel.description,
        )
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(
            1,
            notificationManager().notificationChannels.count {
                it.id == TEST_CHANNEL_ID
            },
        )
        assertEquals(
            other + LocalizedSurfaces.SEPARATOR,
            markerIn(testDirectory)?.take(other.length + 1),
        )
    }

    @Test
    fun choosingALanguageRefreshesTheAppSurfaces() {
        val other = otherLanguage()

        chooseAndWait(other)

        // Hasta Android 12L lo hace la elección misma, antes de devolver. Desde Android 13 llega
        // por el cambio de configuración, poco después.
        val expected = other + LocalizedSurfaces.SEPARATOR
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        while (markerIn(app.noBackupFilesDir)?.startsWith(expected) != true) {
            if (systemManagesLanguage && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(POLL_MILLIS)
            } else {
                throw AssertionError("La marca de las superficies no pasó al idioma elegido")
            }
        }
    }

    @Test
    fun aDamagedMarkerOnlyCostsAnotherRefresh() {
        val surfaces = testSurfaces()
        testDirectory.mkdirs()
        File(testDirectory, LocalizedSurfaces.MARKER_FILE).writeText("x".repeat(1024))

        assertEquals(SurfacesRefresh.REFRESHED, runBlocking { surfaces.refreshLocalizedSurfaces() })
        assertEquals(
            SurfacesRefresh.UP_TO_DATE,
            runBlocking { testSurfaces().refreshLocalizedSurfaces() },
        )
    }

    // Superficies con el canal de prueba y su marca en una carpeta aparte, con el idioma de la app.
    private fun testSurfaces() = LocalizedSurfaces(
        contexts = container.localizedContext,
        surfaces = listOf(NotificationChannels(app, listOf(TEST_CHANNEL))),
        directory = { testDirectory },
        installedVersion = { installedVersionOf(app) },
        log = container.log,
        ioContext = Dispatchers.IO,
    )

    /** Elige [tag] como lo hará el selector y, desde Android 13, espera a que Android lo aplique. */
    private fun chooseAndWait(tag: String) {
        assertEquals(LanguageChoice.Applied, runBlocking { container.language.choose(chosen(tag)) })
        if (systemManagesLanguage) awaitResourcesLanguage(tag)
    }

    private fun awaitResourcesLanguage(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        while (app.getString(R.string.resources_language) != tag) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw AssertionError("Android no aplicó el idioma a tiempo")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    // Un idioma con carpeta propia distinto del que responde ahora, para que el cambio se note.
    private fun otherLanguage(): String =
        if (app.getString(R.string.resources_language) == "es") "pt" else "es"

    private fun markerIn(directory: File): String? =
        File(directory, LocalizedSurfaces.MARKER_FILE).takeIf { it.isFile }?.readText()

    private fun testChannel() =
        requireNotNull(notificationManager().getNotificationChannel(TEST_CHANNEL_ID))

    private fun notificationManager(): NotificationManager =
        app.getSystemService(NotificationManager::class.java)

    private fun localeManager(): LocaleManager = app.getSystemService(LocaleManager::class.java)

    private fun chosen(tag: String): UiLanguage = requireNotNull(UiLanguage.of(tag))

    private val systemManagesLanguage: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Un trabajo como los de la capa de datos: solo conoce el puerto, nunca los recursos. */
    class TextCheckWorker(
        context: Context,
        params: WorkerParameters,
        private val texts: UserTextPort,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            Result.success(workDataOf(TEXT to texts.text(UserText.RESOURCES_LANGUAGE)))
    }

    /** Crea el trabajo con el puerto, como la fábrica de trabajos de la app. */
    private class TextCheckWorkerFactory(private val texts: UserTextPort) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = when (workerClassName) {
            TextCheckWorker::class.java.name -> TextCheckWorker(appContext, workerParameters, texts)
            else -> null
        }
    }

    private companion object {
        const val TEXT = "text"
        const val TEST_CHANNEL_ID = "localization_check"

        /** Un canal de prueba con textos que cambian en cada idioma. */
        val TEST_CHANNEL =
            NotificationChannelSpec(
                id = TEST_CHANNEL_ID,
                name = R.string.resources_language,
                description = R.string.nav_home,
                importance = ChannelImportance.LOW,
            )

        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}
