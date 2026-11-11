package io.github.yesidrobayo.shopledger.localization

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lo que pasa con el idioma mientras corre una prueba, en orden y con milisegundos desde que empezó:
 *
 * - cada cambio de configuración que recibe la app, que es lo que dispara `syncWithSystem`, con lo
 *   que Android dice en ese momento del idioma de la app;
 * - cada valor nuevo del idioma en memoria, que es lo que esa copia leyó de Android;
 * - cada cambio del idioma de Android y del archivo de la app, mirados cada pocos milisegundos.
 *
 * Solo anota etiquetas de idioma, nunca datos del negocio. Va al registro con la etiqueta [TAG] y se
 * agrega al mensaje de una prueba que falla, para ver en qué orden llegaron las copias.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LanguageTimeline(private val app: ShopLedgerApp, private val appFile: File) {
    private val start = SystemClock.elapsedRealtime()
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val localeManager = app.getSystemService(LocaleManager::class.java)

    @Volatile private var running = true

    @Volatile var lastConfigurationAt: Long = 0L
        private set

    private val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            lastConfigurationAt = SystemClock.elapsedRealtime()
            note(
                "configuración cambia, sigue syncWithSystem: Android=${android()}, " +
                    "configuración=${newConfig.locales.toLanguageTags()}",
            )
        }

        @Deprecated("Sin uso en esta prueba")
        override fun onLowMemory() = Unit
    }

    private val poller = Thread {
        var android: String? = null
        var file: String? = null
        while (running) {
            val nowAndroid = android()
            if (nowAndroid != android) note("Android=$nowAndroid")
            android = nowAndroid
            val nowFile = fileText()
            if (nowFile != file) note("archivo=$nowFile")
            file = nowFile
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    init {
        app.registerComponentCallbacks(callbacks)
        // Sin despachador propio: cada valor nuevo se anota en el hilo que lo escribe.
        scope.launch { app.container.language.language.collect { note("memoria=${it.fileValue}") } }
        poller.isDaemon = true
        poller.start()
    }

    fun note(event: String) {
        val line = "${SystemClock.elapsedRealtime() - start} ms: $event"
        events += line
        Log.i(TAG, line)
    }

    fun stop() {
        running = false
        scope.cancel()
        app.unregisterComponentCallbacks(callbacks)
        poller.join(JOIN_MILLIS)
    }

    override fun toString(): String = synchronized(events) { events.joinToString("\n") }

    private fun android(): String =
        localeManager.applicationLocales.toLanguageTags().ifEmpty { UiLanguage.SYSTEM_VALUE }

    private fun fileText(): String =
        if (appFile.isFile) appFile.readText().trim() else "sin archivo"

    companion object {
        const val TAG = "LanguageTimeline"
        private const val POLL_MILLIS = 5L
        private const val JOIN_MILLIS = 1_000L
    }
}
