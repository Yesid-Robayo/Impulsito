package io.github.yesidrobayo.shopledger.ui.phrase

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * La voz directa: abrir el servicio de voz del teléfono con `Decir`, en vez del teclado.
 *
 * La app nunca pide el micrófono ni integra un motor de voz. Lo que hace es pedirle a otra app,
 * por un toque de la persona, que convierta la voz en texto, y recibir solo ese texto. El servicio
 * es del teléfono: puede usar internet si no tiene el idioma descargado, aunque se le pide que
 * prefiera trabajar sin conexión. Por eso está apagado de inicio y, al encenderlo, la app lo
 * explica.
 */
object VoiceInput {
    /**
     * El pedido al servicio de voz del teléfono, en [languageTag], el idioma de la interfaz. Pide
     * una sola lectura y que prefiera el reconocimiento sin conexión.
     */
    fun intent(languageTag: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        ).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    /**
     * Si el teléfono tiene un servicio de voz que atienda el pedido. Sin él, el ajuste no aparece y
     * `Decir` abre siempre el teclado. Para verlo desde Android 11, el manifiesto declara la acción
     * en sus consultas.
     */
    fun isAvailable(context: Context): Boolean =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) !=
            null

    /**
     * El texto que devolvió el servicio, o `null` si la persona canceló o no llegó nada. Es entrada
     * de otra app: solo se usa como texto de la frase, que la persona revisa antes de registrar.
     */
    fun textOf(resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        return heard?.trim()?.take(MAX_HEARD_LENGTH)?.takeIf { it.isNotEmpty() }
    }

    /** Una frase dictada nunca es tan larga: lo que sobra no es una operación. */
    private const val MAX_HEARD_LENGTH = 500
}

/**
 * Si la voz directa está encendida en este teléfono, guardado como una marca vacía en la carpeta
 * que da [directory], que en la app es `noBackupFilesDir`.
 *
 * **Por qué un archivo y no la base.** Es una elección de este teléfono, como la de ver montos en
 * el widget: no viaja en una copia, restaurar no la cambia y Android no respalda esta carpeta. Si
 * se pierde, la voz directa queda apagada, que es el lado seguro.
 *
 * Crear la marca o borrarla es una sola operación del disco: nunca queda a medias. Todo toca el
 * disco en [io].
 *
 * @param directory da la carpeta. Se pide en cada uso, porque pedir `noBackupFilesDir` también
 *   toca el disco.
 */
class VoiceInputPreference(private val directory: () -> File, private val io: CoroutineContext) {
    private val state = MutableStateFlow<Boolean?>(null)

    /** Si está encendida, o `null` mientras no se leyó. */
    val enabled: StateFlow<Boolean?> = state.asStateFlow()

    /**
     * Lee la marca, la primera vez, y dice si está encendida. Si no se puede leer, cuenta como
     * apagada: `Decir` abre el teclado, que siempre funciona.
     */
    suspend fun load(): Boolean {
        state.value?.let { return it }
        val on = withContext(io) { File(directory(), FILE_NAME).isFile }
        state.compareAndSet(null, on)
        return state.value ?: on
    }

    /**
     * Enciende o apaga la voz directa. Devuelve `false` si no se pudo guardar: la elección anterior
     * sigue, y la pantalla lo dice.
     */
    suspend fun set(on: Boolean): Boolean {
        val saved =
            withContext(io) {
                try {
                    val folder = directory()
                    folder.mkdirs()
                    val marker = File(folder, FILE_NAME)
                    if (on) {
                        marker.createNewFile() || marker.isFile
                    } else {
                        !marker.exists() || marker.delete()
                    }
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        if (saved) state.value = on
        return saved
    }

    companion object {
        /** El nombre de la marca dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "voice-input-direct"
    }
}
