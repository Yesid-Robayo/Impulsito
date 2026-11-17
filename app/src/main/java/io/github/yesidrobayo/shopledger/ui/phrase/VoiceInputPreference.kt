package io.github.yesidrobayo.shopledger.ui.phrase

import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * `Escuchar al tocar Decir`, y si la app ya pidió el micrófono, guardados como marcas vacías en la
 * carpeta que da [directory], que en la app es `noBackupFilesDir`.
 *
 * **Encendido si no está apagado.** Escuchar viene encendido: solo la marca de apagado lo apaga.
 * Quien lo había encendido antes, cuando venía apagado, no tiene esa marca y sigue encendido; la
 * marca vieja de encendido se borra al cambiar el ajuste, porque ya no dice nada.
 *
 * **Por qué un archivo y no la base.** Es una elección de este teléfono, como la de ver montos en
 * el widget: no viaja en una copia, restaurar no la cambia y Android no respalda esta carpeta. Si
 * se pierde, escuchar vuelve a encendido, y sin permiso ni servicio de voz `Decir` abre el teclado
 * igual.
 *
 * Crear o borrar una marca es una sola operación del disco: nunca queda a medias. Todo toca el
 * disco en [io].
 *
 * @param directory da la carpeta. Se pide en cada uso, porque pedir `noBackupFilesDir` también
 *   toca el disco.
 */
class VoiceInputPreference(private val directory: () -> File, private val io: CoroutineContext) {
    private val state = MutableStateFlow<Boolean?>(null)

    /** Si escuchar está encendido, o `null` mientras no se leyó. */
    val enabled: StateFlow<Boolean?> = state.asStateFlow()

    /**
     * Lee la marca, la primera vez, y dice si escuchar está encendido. Si no se puede leer, cuenta
     * como encendido: sin permiso o sin servicio, `Decir` abre el teclado de todas formas.
     */
    suspend fun load(): Boolean {
        state.value?.let { return it }
        val on = withContext(io) { !marker(OFF_FILE_NAME).isFile }
        state.compareAndSet(null, on)
        return state.value ?: on
    }

    /**
     * Enciende o apaga escuchar. Devuelve `false` si no se pudo guardar: la elección anterior
     * sigue, y la pantalla lo dice.
     */
    suspend fun set(on: Boolean): Boolean {
        val saved =
            withContext(io) {
                guarded {
                    val off = marker(OFF_FILE_NAME)
                    val done = if (on) !off.exists() || off.delete() else create(off)
                    if (done) marker(LEGACY_ON_FILE_NAME).delete()
                    done
                }
            }
        if (saved) state.value = on
        return saved
    }

    /**
     * Si la app ya pidió el micrófono alguna vez. Con eso se distingue "nunca se preguntó" de "no
     * volver a preguntar", que Android responde igual.
     */
    suspend fun micAsked(): Boolean = withContext(io) { marker(MIC_ASKED_FILE_NAME).isFile }

    /** Anota que la app pidió el micrófono. Si no se guarda, la próxima vez explica de nuevo. */
    suspend fun markMicAsked() {
        withContext(io) { guarded { create(marker(MIC_ASKED_FILE_NAME)) } }
    }

    private fun marker(name: String): File = File(directory(), name)

    private fun create(file: File): Boolean {
        file.parentFile?.mkdirs()
        return file.createNewFile() || file.isFile
    }

    private inline fun guarded(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        /** La marca de escuchar apagado dentro de `noBackupFilesDir`. */
        const val OFF_FILE_NAME = "voice-input-off"

        /** La marca de encendido de cuando escuchar venía apagado. Ya no se lee. */
        const val LEGACY_ON_FILE_NAME = "voice-input-direct"

        /** La marca de que la app ya pidió el micrófono. */
        const val MIC_ASKED_FILE_NAME = "voice-input-mic-asked"
    }
}
