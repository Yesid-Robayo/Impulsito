package io.github.yesidrobayo.shopledger.ui.phrase

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
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
 * **Varios servicios, uno tras otro.** El predeterminado del sistema no siempre atiende a otras
 * apps: en algunos teléfonos es uno del fabricante que falla al instante, sin el idioma o sin el
 * micrófono. Por eso se piden todos los servicios de voz instalados y se prueban en el orden de
 * [ListenPlan.serviceOrder], cada uno nombrado, hasta que uno entiende.
 *
 * **Registro para diagnosticar.** Cada intento deja una línea con el paquete del servicio, el modo
 * y el código del sistema, nunca lo oído: así se sabe en un teléfono real cuál falla y por qué.
 *
 * @param languageTag el idioma de la frase con país, de [SpeechLanguage.tagFor].
 */
class PhoneSpeechEngine(
    private val context: Context,
    private val languageTag: String,
    private val telemetry: VoiceTelemetry,
) : SpeechEngine {
    private var recognizer: SpeechRecognizer? = null

    /** Los servicios de voz del teléfono, ya en el orden en que se prueban. */
    private val services: List<ComponentName> = recognitionServices(context)

    override val plans: List<ListenPlan> =
        ListenPlan.plansFor(onDevice = hasOnDeviceRecognizer(context), services = services.size)

    /** Los pedidos de empezar que esperan su pausa, en el hilo principal. */
    private val handler = Handler(Looper.getMainLooper())

    /** Cuenta los pedidos: uno que espera su pausa no empieza si ya llegó otro o se soltó el motor. */
    private var turn = 0

    /** Si ya hubo un intento: el siguiente espera a que el servicio anterior termine de cerrarse. */
    private var startedOnce = false

    /** Se pidió terminar mientras el intento esperaba su pausa o la respuesta del idioma. */
    private var stopPending = false

    /**
     * El idioma que los servicios dijeron tener, de [SpeechLanguage.pick]. Se pregunta una vez y
     * sirve para los intentos siguientes: algunos servicios no contestan bien esa pregunta.
     */
    private var resolvedTag: String? = null

    /** Si el intento actual ya empezó a escuchar: antes, terminar queda pendiente. */
    private var listening = false

    override fun listen(
        plan: ListenPlan,
        onEvent: (ListenEvent) -> Unit,
        onLevel: (Float) -> Unit,
    ) {
        release()
        val mine = turn
        stopPending = false
        // Crear el siguiente al instante choca con el anterior, que todavía se está cerrando: el
        // servicio contesta ocupado o desconectado y el intento se pierde sin escuchar.
        val pause = if (startedOnce) RETRY_PAUSE_MS else 0L
        startedOnce = true
        handler.postDelayed({ if (turn == mine) begin(plan, onEvent, onLevel) }, pause)
    }

    private fun begin(plan: ListenPlan, onEvent: (ListenEvent) -> Unit, onLevel: (Float) -> Unit) {
        val service = serviceOf(plan)
        val created =
            try {
                create(plan, service)
            } catch (_: UnsupportedOperationException) {
                // El motor sin red desapareció entre la consulta y el pedido.
                null
            } catch (_: SecurityException) {
                null
            }
        if (created == null) {
            telemetry.attempt(VoiceOutcome.NOT_CREATED, plan.service, plan.mode, null)
            onEvent(ListenEvent.Error(ListenError.OTHER))
            return
        }
        recognizer = created
        created.setRecognitionListener(Listener(created, plan, onEvent, onLevel))
        val known = resolvedTag
        if (known != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            start(created, plan, known ?: languageTag)
        } else {
            askLanguageThenStart(created, plan)
        }
    }

    /**
     * Le pregunta al servicio qué idiomas tiene y empieza con el más cercano al de la frase. Si no
     * contesta a tiempo, o contesta con error, empieza con el pedido tal cual.
     */
    @SuppressLint("NewApi")
    private fun askLanguageThenStart(created: SpeechRecognizer, plan: ListenPlan) {
        var answered = false
        val startOnce = { tag: String ->
            if (!answered) {
                answered = true
                if (recognizer === created) start(created, plan, tag)
            }
        }
        handler.postDelayed({ startOnce(languageTag) }, SUPPORT_TIMEOUT_MS)
        try {
            created.checkRecognitionSupport(
                intentFor(plan, languageTag),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (answered) return
                        val tag =
                            SpeechLanguage.pick(
                                languageTag,
                                support.installedOnDeviceLanguages,
                                support.onlineLanguages,
                            )
                        resolvedTag = tag
                        startOnce(tag)
                    }

                    override fun onError(error: Int) {
                        startOnce(languageTag)
                    }
                },
            )
        } catch (_: RuntimeException) {
            // El servicio no sabe contestar la pregunta: se pide el idioma tal cual.
            startOnce(languageTag)
        }
    }

    private fun start(created: SpeechRecognizer, plan: ListenPlan, tag: String) {
        created.startListening(intentFor(plan, tag))
        listening = true
        if (stopPending) created.stopListening()
    }

    override fun stop() {
        stopPending = true
        if (listening) recognizer?.stopListening()
    }

    override fun release() {
        turn++
        listening = false
        val current = recognizer ?: return
        recognizer = null
        current.cancel()
        current.destroy()
    }

    /** El servicio nombrado de [plan], o `null` para el motor sin red o el predeterminado. */
    private fun serviceOf(plan: ListenPlan): ComponentName? = plan.service?.let(services::getOrNull)

    private fun create(plan: ListenPlan, service: ComponentName?): SpeechRecognizer =
        when (plan.mode) {
            ListenMode.ON_DEVICE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }

            ListenMode.OFFLINE_FIRST, ListenMode.ONLINE ->
                if (service != null) {
                    SpeechRecognizer.createSpeechRecognizer(context, service)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
        }

    /**
     * El pedido: frase libre en [tag], el idioma de la frase con país, con lo que se va
     * entendiendo y una sola lectura. Salvo en el modo con red, pide preferir trabajar sin
     * conexión.
     */
    private fun intentFor(plan: ListenPlan, tag: String): Intent {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                ).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        when (plan.mode) {
            ListenMode.ON_DEVICE, ListenMode.OFFLINE_FIRST ->
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            ListenMode.ONLINE -> Unit
        }
        return intent
    }

    /**
     * Los avisos de un intento. Si ya hay otro, los del anterior se ignoran: un aviso tardío no
     * cambia la pantalla.
     */
    private inner class Listener(
        private val owner: SpeechRecognizer,
        private val plan: ListenPlan,
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
            if (!current) return
            telemetry.attempt(VoiceOutcome.FAILED, plan.service, plan.mode, error)
            onEvent(ListenEvent.Error(errorOf(error)))
        }

        override fun onResults(results: Bundle?) {
            if (!current) return
            telemetry.attempt(VoiceOutcome.ANSWERED, plan.service, plan.mode, null)
            onEvent(ListenEvent.Final(firstOf(results)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!current) return
            onEvent(ListenEvent.Partial(firstOf(partialResults)))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {

        /** La pausa entre un intento que falló y el siguiente, para que el anterior se cierre. */
        private const val RETRY_PAUSE_MS = 400L

        /** Lo que se espera a que el servicio diga qué idiomas tiene antes de pedir el de la frase. */
        private const val SUPPORT_TIMEOUT_MS = 1_500L

        /**
         * Los servicios de voz instalados, en el orden de [ListenPlan.serviceOrder]. Se ven por las
         * consultas del manifiesto. Si el sistema no responde, ninguno: queda el predeterminado.
         */
        private fun recognitionServices(context: Context): List<ComponentName> {
            val found =
                try {
                    queryServices(context.packageManager)
                } catch (_: SecurityException) {
                    emptyList()
                } catch (_: RuntimeException) {
                    // El servicio de paquetes murió o no respondió: se sigue con el predeterminado.
                    emptyList()
                }
            val components =
                found.mapNotNull { info ->
                    info.serviceInfo?.let { ComponentName(it.packageName, it.name) }
                }.distinct()
            return ListenPlan.serviceOrder(components.map { it.packageName }).map(components::get)
        }

        private fun queryServices(packages: PackageManager): List<ResolveInfo> {
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packages.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packages.queryIntentServices(intent, 0)
            }
        }

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
