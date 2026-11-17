package io.github.yesidrobayo.shopledger.ui.phrase

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * El motor que convierte la voz en texto, detrás de una interfaz: la pantalla de escuchar no sabe
 * si es el del teléfono o uno falso de las pruebas, que nunca usan el micrófono.
 *
 * Todo se llama en el hilo principal, y los avisos llegan ahí.
 */
interface SpeechEngine {
    /** Los intentos que este teléfono permite, en orden. */
    val plans: List<ListenPlan>

    /**
     * Suelta el intento anterior, si lo hay, y escucha con [plan]. Lo que pasa llega a [onEvent], y
     * el volumen de la voz, de 0 a 1, a [onLevel].
     */
    fun listen(plan: ListenPlan, onEvent: (ListenEvent) -> Unit, onLevel: (Float) -> Unit)

    /** Deja de oír: lo dicho hasta ahí se convierte en texto y llega como [ListenEvent.Final]. */
    fun stop()

    /** Cancela y suelta el motor: el micrófono se apaga. Se puede llamar más de una vez. */
    fun release()
}

/**
 * El servicio de voz del teléfono, por `SpeechRecognizer`, sin su ventana: la app muestra su propia
 * pantalla de escuchar y recibe solo el texto. El audio lo recibe ese servicio, y la app nunca lo
 * guarda.
 *
 * El pedido usa la acción de reconocer voz solo como el formato de los datos que espera el
 * servicio: no abre ninguna actividad.
 *
 * @param languageTag el idioma de la interfaz, que es el de la frase.
 */
class PhoneSpeechEngine(private val context: Context, private val languageTag: String) :
    SpeechEngine {
    private var recognizer: SpeechRecognizer? = null

    override val plans: List<ListenPlan> =
        ListenPlan.plansFor(onDevice = hasOnDeviceRecognizer(context))

    override fun listen(
        plan: ListenPlan,
        onEvent: (ListenEvent) -> Unit,
        onLevel: (Float) -> Unit,
    ) {
        release()
        val created =
            try {
                create(plan)
            } catch (_: UnsupportedOperationException) {
                // El motor sin red desapareció entre la consulta y el pedido.
                null
            } catch (_: SecurityException) {
                null
            }
        if (created == null) {
            onEvent(ListenEvent.Error(ListenError.OTHER))
            return
        }
        recognizer = created
        created.setRecognitionListener(Listener(created, onEvent, onLevel))
        created.startListening(intentFor(plan))
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun release() {
        val current = recognizer ?: return
        recognizer = null
        current.cancel()
        current.destroy()
    }

    private fun create(plan: ListenPlan): SpeechRecognizer = when (plan) {
        ListenPlan.ON_DEVICE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

        ListenPlan.OFFLINE_FIRST, ListenPlan.ONLINE ->
            SpeechRecognizer.createSpeechRecognizer(context)
    }

    /**
     * El pedido: frase libre en el idioma de la interfaz, con lo que se va entendiendo y una sola
     * lectura. Salvo en el último intento, pide que prefiera trabajar sin conexión.
     */
    private fun intentFor(plan: ListenPlan): Intent {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                ).putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        when (plan) {
            ListenPlan.ON_DEVICE, ListenPlan.OFFLINE_FIRST ->
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            ListenPlan.ONLINE -> Unit
        }
        return intent
    }

    /**
     * Los avisos de un intento. Si ya hay otro, los del anterior se ignoran: un aviso tardío no
     * cambia la pantalla.
     */
    private inner class Listener(
        private val owner: SpeechRecognizer,
        private val onEvent: (ListenEvent) -> Unit,
        private val onLevel: (Float) -> Unit,
    ) : RecognitionListener {
        private val current: Boolean get() = recognizer === owner

        override fun onReadyForSpeech(params: Bundle?) {
            if (current) onEvent(ListenEvent.Ready)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (current) onLevel(levelOf(rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (current) onEvent(ListenEvent.Error(errorOf(error)))
        }

        override fun onResults(results: Bundle?) {
            if (current) onEvent(ListenEvent.Final(firstOf(results)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (current) onEvent(ListenEvent.Partial(firstOf(partialResults)))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        /**
         * Si el teléfono tiene un servicio de voz. Sin él, `Decir` abre siempre la hoja con el
         * teclado y el ajuste no aparece. Para verlo desde Android 11, el manifiesto declara la
         * acción del servicio en sus consultas.
         */
        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)

        private fun hasOnDeviceRecognizer(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** El primer texto del motor. Es entrada de otra app: solo se usa como la frase. */
        private fun firstOf(bundle: Bundle?): String = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

        /**
         * El volumen, de 0 a 1. El motor lo da en decibeles que suelen ir de -2 en silencio a 10
         * al hablar fuerte.
         */
        private fun levelOf(rmsdB: Float): Float =
            ((rmsdB - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f)

        /**
         * Los códigos del sistema, sin sus nombres. Los de Android 12 en adelante son números que
         * una versión anterior nunca manda: compararlos ahí no hace daño.
         */
        @SuppressLint("InlinedApi")
        private fun errorOf(code: Int): ListenError = when (code) {
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            -> ListenError.NETWORK

            SpeechRecognizer.ERROR_SERVER -> ListenError.SERVER

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            -> ListenError.LANGUAGE

            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> ListenError.NO_SPEECH

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenError.PERMISSION

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenError.BUSY

            else -> ListenError.OTHER
        }

        private const val QUIET_DB = -2f
        private const val LOUD_DB = 10f
    }
}
