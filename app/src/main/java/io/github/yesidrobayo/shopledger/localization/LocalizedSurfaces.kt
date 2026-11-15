package io.github.yesidrobayo.shopledger.localization

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Algo que vive fuera de la app y muestra textos suyos: Android los guarda tal como llegaron, así
 * que un cambio de idioma no los alcanza solo: los canales de avisos, el widget, el mosaico y los
 * atajos de la app.
 */
fun interface LocalizedSurface {
    /**
     * Vuelve a escribir sus textos con los de [texts], un contexto en el idioma de la interfaz.
     * Corre fuera del hilo principal.
     */
    suspend fun refresh(texts: Context)
}

/**
 * Mantiene en el idioma de la interfaz los textos que viven fuera de la app, con
 * [refreshLocalizedSurfaces].
 *
 * **Cuándo hace falta.** Una marca en `noBackupFilesDir`, junto al archivo de idioma, recuerda en
 * qué idioma respondieron los recursos y qué versión estaba instalada la última vez que se
 * escribieron. Si alguno cambió, se vuelven a escribir todas las superficies; si no, no se hace
 * nada. Así basta con llamarla siempre que el idioma pudo cambiar:
 *
 * - **Al elegir un idioma** en la app, con [AppLanguage.choose]. En Android 8 a 12L es lo que lo
 *   cambia.
 * - **Al cambiar la configuración**, en `Application.onConfigurationChanged`. Desde Android 13 el
 *   idioma elegido en la app o en los ajustes de Android llega por ahí, después de elegirlo, y en
 *   cualquier versión llega por ahí el idioma del teléfono cuando la app lo sigue.
 * - **Al arrancar el proceso.** Un idioma cambiado con la app cerrada, o una versión nueva con
 *   otros textos, se nota aquí: hasta entonces los canales siguen con el nombre anterior.
 *
 * Compara la etiqueta de la carpeta de recursos que respondió, no la del idioma: dos idiomas que
 * caen en la misma carpeta tienen los mismos textos y no piden nada.
 *
 * La marca es un recuerdo y no un dato: si falta, se daña o no se puede escribir, lo único que pasa
 * es que las superficies se vuelven a escribir una vez más.
 *
 * @param surfaces lo que se vuelve a escribir, en orden.
 * @param directory da la carpeta de la marca, `noBackupFilesDir` en la app. Se pide al actualizar,
 *   en [ioContext], porque pedir la carpeta también toca el disco.
 * @param installedVersion la versión instalada, como texto: cambia con cada actualización de la app.
 * @param ioContext contexto del disco y de las llamadas al sistema.
 */
class LocalizedSurfaces(
    private val contexts: LocalizedContextProvider,
    private val surfaces: List<LocalizedSurface>,
    private val directory: () -> File,
    private val installedVersion: () -> String,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
) {
    private val mutex = Mutex()

    // Lo que dice la marca, una vez leída o escrita: así un cambio de configuración que no cambia
    // el idioma, como girar el teléfono, no vuelve a leer el disco. Solo se toca con el cerrojo.
    private var remembered: String? = null
    private var version: String? = null

    /**
     * Vuelve a escribir los textos de todas las superficies si cambiaron el idioma de los recursos
     * o la versión instalada desde la última vez. Las llamadas a la vez esperan su turno.
     *
     * Nunca lanza, salvo la cancelación: una falla queda en el registro con su clase y devuelve
     * [SurfacesRefresh.FAILED], sin marca nueva, así que la llamada siguiente lo intenta de nuevo.
     */
    suspend fun refreshLocalizedSurfaces(): SurfacesRefresh = mutex.withLock {
        withContext(ioContext) {
            val outcome = log.contain(SurfacesEvent.REFRESH_FAILED) { refreshIfStale() }
            when (outcome) {
                is Contained.Done -> outcome.value
                is Contained.Failed -> SurfacesRefresh.FAILED
            }
        }
    }

    private suspend fun refreshIfStale(): SurfacesRefresh {
        val texts = contexts.context()
        val installed = version ?: installedVersion().also { version = it }
        val wanted = texts.getString(R.string.resources_language) + SEPARATOR + installed
        if (wanted == (remembered ?: readMarker())) {
            remembered = wanted
            return SurfacesRefresh.UP_TO_DATE
        }
        for (surface in surfaces) surface.refresh(texts)
        remembered = wanted
        writeMarker(wanted)
        log.info(SurfacesEvent.REFRESHED, LogField.count("superficies", surfaces.size.toLong()))
        return SurfacesRefresh.REFRESHED
    }

    // Una marca ilegible o demasiado larga no coincide con nada: las superficies se escriben de
    // nuevo, y la marca se reemplaza.
    private fun readMarker(): String? = try {
        val file = File(directory(), MARKER_FILE)
        if (!file.isFile || file.length() > MAX_MARKER_BYTES) null else file.readText().trim()
    } catch (failure: IOException) {
        log.warn(SurfacesEvent.MARKER_READ_FAILED, failure)
        null
    }

    // Sin la marca en disco, el arranque siguiente vuelve a escribir las superficies: sobra, pero
    // no hace daño. Por eso no hace falta temporal ni sincronización.
    private fun writeMarker(marker: String) {
        try {
            val folder = directory()
            folder.mkdirs()
            File(folder, MARKER_FILE).writeText(marker + "\n")
        } catch (failure: IOException) {
            log.warn(SurfacesEvent.MARKER_WRITE_FAILED, failure)
        }
    }

    companion object {
        /** El nombre de la marca dentro de `noBackupFilesDir`. */
        const val MARKER_FILE = "surfaces-language"

        /** Entre la etiqueta del idioma y la versión. */
        const val SEPARATOR = "|"

        // Una etiqueta, dos números y separadores caben con holgura.
        private const val MAX_MARKER_BYTES = 256L
    }
}

/** Lo que hizo [LocalizedSurfaces.refreshLocalizedSurfaces]. */
enum class SurfacesRefresh {
    /** Ni el idioma ni la versión cambiaron: no hizo nada. */
    UP_TO_DATE,

    /** Volvió a escribir todas las superficies. */
    REFRESHED,

    /** Algo falló: quedó en el registro, y la llamada siguiente lo intenta de nuevo. */
    FAILED,
}

/**
 * La versión instalada de la app, para la marca de [LocalizedSurfaces]: el código de versión y el
 * momento de la instalación, que también cambia al instalar encima la misma versión. Es una llamada
 * al sistema: va fuera del hilo principal.
 */
fun installedVersionOf(app: Context): String {
    val manager = app.packageManager
    val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(app.packageName, 0)
        }
    val versionCode = PackageInfoCompat.getLongVersionCode(info)
    return "$versionCode${LocalizedSurfaces.SEPARATOR}${info.lastUpdateTime}"
}

/** Lo que registran las superficies. Cada texto es fijo y no lleva datos. */
internal enum class SurfacesEvent(override val text: String) : LogEvent {
    REFRESHED("Textos de fuera de la app escritos en el idioma de la interfaz"),
    REFRESH_FAILED("No se pudieron escribir los textos de fuera de la app"),
    MARKER_READ_FAILED("No se pudo leer la marca del idioma de los textos de fuera de la app"),
    MARKER_WRITE_FAILED("No se pudo guardar la marca del idioma de los textos de fuera de la app"),
}
