package io.github.yesidrobayo.shopledger.localization

import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * El idioma de la interfaz: lo aplica al arrancar, lo guarda cuando la persona elige otro y, desde
 * Android 13, sigue lo que diga Android.
 *
 * Vive en [LanguagePreferenceFile], fuera de la base: el arranque lo aplica sin abrirla, y restaurar
 * una copia no lo cambia. Un teléfono nuevo no tiene archivo, así que empieza siguiendo al teléfono.
 *
 * **Android 8 a 12L.** Android no tiene idioma por app. [applyAtProcessStart] lee el archivo y se lo
 * pasa a AppCompat antes de la primera actividad: la primera pantalla ya sale en ese idioma, sin
 * recrearse ni cambiar de idioma ante la persona. Lo que diga el archivo manda.
 *
 * **Android 13 o superior.** Android guarda el idioma de la app y lo aplica a todo el proceso antes
 * de que la app corra, así que el arranque no lee nada. La persona también puede cambiarlo en los
 * ajustes de Android: por eso lo que diga Android manda, y [syncWithSystem] copia su valor al
 * archivo al abrir la app y cuando cambia la configuración. Las llamadas a Android van fuera del
 * hilo principal, porque el sistema puede leer su disco dentro de ellas.
 *
 * Una sola instancia por proceso: el idioma en memoria y el cerrojo de las escrituras viven en ella.
 *
 * @param ioContext contexto del disco y de las llamadas a Android que pueden tocarlo.
 * @param mainContext el hilo principal, donde AppCompat aplica el idioma a las actividades.
 */
class AppLanguage(
    private val file: LanguagePreferenceFile,
    private val platform: LocalePlatform,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
    private val mainContext: CoroutineContext,
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<UiLanguage>(UiLanguage.System)

    /**
     * El idioma elegido. No toca el disco.
     *
     * En Android 8 a 12L vale desde [applyAtProcessStart]. Desde Android 13 vale desde que
     * [syncWithSystem] termina, y hasta entonces dice [UiLanguage.System]: ahí Android aplica el
     * idioma por su cuenta, así que nada de la interfaz depende de este valor para verse en el
     * idioma correcto.
     */
    val language: StateFlow<UiLanguage> = current.asStateFlow()

    /**
     * Aplica el idioma guardado. Va en `Application.onCreate`, en el hilo principal, antes de la
     * primera actividad: también cuando el proceso arranca para un trabajo en segundo plano, que
     * así ya tiene el idioma en memoria.
     *
     * En Android 8 a 12L lee el archivo en el hilo principal. Es una lectura corta y deliberada: sin
     * ella la primera pantalla saldría en otro idioma y cambiaría ante la persona. Va con un permiso
     * de StrictMode solo para ella, que en una instalación nueva cubre también la creación de la
     * carpeta. Desde Android 13 no hace nada.
     */
    fun applyAtProcessStart() {
        if (platform.systemManagesAppLanguage) return
        val saved = platform.withStartupDiskPermit { readFile() }.language
        current.value = saved
        // Sin elección no hace falta llamar a AppCompat: las actividades ya siguen al teléfono.
        if (saved is UiLanguage.Chosen) platform.applyToActivities(saved)
    }

    /**
     * Desde Android 13, copia al archivo el idioma que tiene Android para la app, si cambió: lo que
     * diga Android manda. Va al arrancar el proceso y en `Application.onConfigurationChanged`, por
     * donde llega un idioma cambiado en los ajustes de Android con la app abierta. En Android 8 a
     * 12L no hace nada: ahí el archivo manda.
     *
     * Nunca lanza: si no puede escribir, lo registra, y el idioma en pantalla sigue siendo el de
     * Android.
     */
    suspend fun syncWithSystem() {
        if (!platform.systemManagesAppLanguage) return
        mutex.withLock {
            withContext(ioContext) {
                val system = platform.systemAppLanguage()
                current.value = system
                // Sin archivo y siguiendo al teléfono no hay nada que copiar: es un teléfono nuevo.
                val read = readFile()
                val upToDate =
                    read == LanguageRead.Found(system) ||
                        (read == LanguageRead.Missing && system == UiLanguage.System)
                if (!upToDate && save(system)) log.info(LanguageEvent.TAKEN_FROM_SYSTEM)
            }
        }
    }

    /**
     * Guarda el idioma que eligió la persona y lo aplica a toda la app. Es lo que llama el selector
     * de idioma.
     *
     * - **Android 8 a 12L.** Primero guarda el archivo, fuera del hilo principal, y solo si quedó
     *   guardado lo aplica: así el idioma en pantalla nunca es uno que se perdería al cerrar la app.
     *   Las actividades abiertas se recrean en el idioma nuevo, sin perder su estado.
     * - **Android 13 o superior.** Se lo pasa a Android, que lo guarda, lo aplica y recrea las
     *   actividades, y después lo copia al archivo. Si el archivo no se pudo escribir, el idioma
     *   igual cambió, porque Android manda, y el arranque siguiente vuelve a copiarlo.
     *
     * No cambia el formato regional ni la moneda.
     */
    suspend fun choose(language: UiLanguage): LanguageChoice = mutex.withLock {
        if (platform.systemManagesAppLanguage) {
            withContext(ioContext) {
                platform.setSystemAppLanguage(language)
                current.value = language
                save(language)
            }
            LanguageChoice.Applied
        } else {
            if (!withContext(ioContext) { save(language) }) return@withLock LanguageChoice.NotSaved
            current.value = language
            withContext(mainContext) { platform.applyToActivities(language) }
            LanguageChoice.Applied
        }
    }

    // Un archivo dañado o ilegible cuenta como inválido, y su idioma es el del teléfono: la app abre
    // igual, y la escritura siguiente lo reemplaza.
    private fun readFile(): LanguageRead = try {
        file.read().also { if (it == LanguageRead.Invalid) log.warn(LanguageEvent.FILE_INVALID) }
    } catch (failure: IOException) {
        log.warn(LanguageEvent.FILE_READ_FAILED, failure)
        LanguageRead.Invalid
    }

    private fun save(language: UiLanguage): Boolean = try {
        file.write(language)
        true
    } catch (failure: IOException) {
        log.error(LanguageEvent.FILE_WRITE_FAILED, failure, LogField.state("idioma", language.kind))
        false
    }
}

/** Lo que pasó al elegir un idioma. */
sealed interface LanguageChoice {
    /** El idioma quedó guardado y aplicado. */
    data object Applied : LanguageChoice

    /**
     * No se pudo guardar, así que no se aplicó: la app sigue en el idioma de antes. Solo pasa en
     * Android 8 a 12L, donde el archivo es lo único que lo guarda.
     */
    data object NotSaved : LanguageChoice
}

/** Si el idioma sigue al teléfono o está fijado, para el registro: nunca la etiqueta. */
internal enum class LanguageKind { SYSTEM, CHOSEN }

internal val UiLanguage.kind: LanguageKind
    get() = when (this) {
        UiLanguage.System -> LanguageKind.SYSTEM
        is UiLanguage.Chosen -> LanguageKind.CHOSEN
    }

/** Lo que registra el idioma. Cada texto es fijo y no lleva datos. */
internal enum class LanguageEvent(override val text: String) : LogEvent {
    FILE_INVALID("El archivo de idioma no trae un idioma: sigue al teléfono"),
    FILE_READ_FAILED("No se pudo leer el archivo de idioma: sigue al teléfono"),
    FILE_WRITE_FAILED("No se pudo guardar el idioma"),
    TAKEN_FROM_SYSTEM("El idioma de los ajustes de Android reemplazó al guardado"),
}
