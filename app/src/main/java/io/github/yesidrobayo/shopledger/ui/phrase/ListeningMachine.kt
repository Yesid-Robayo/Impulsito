package io.github.yesidrobayo.shopledger.ui.phrase

/** Cómo se le pide a un servicio de voz que escuche. */
enum class ListenMode {
    /** El motor del teléfono que trabaja sin red, si lo tiene. */
    ON_DEVICE,

    /** Un servicio de voz, pidiéndole que prefiera trabajar sin conexión. */
    OFFLINE_FIRST,

    /** El mismo servicio, sin esa preferencia: puede usar la red si no tiene el idioma. */
    ONLINE,
}

/**
 * Un intento de escuchar: el modo y el servicio que lo atiende.
 *
 * @param service el lugar del servicio en la lista que arma el motor, sin saber cuál es: así la
 *   máquina no depende de Android. `null` es el motor sin red o, si el teléfono no lista ningún
 *   servicio, el predeterminado del sistema.
 */
data class ListenPlan(val mode: ListenMode, val service: Int? = null) {
    companion object {
        /**
         * Los intentos en orden: el motor sin red, si está, y luego cada servicio de [services], ya
         * ordenados, primero sin conexión y después con red. Sin servicios listados, el
         * predeterminado del sistema de las dos formas.
         */
        fun plansFor(onDevice: Boolean, services: Int): List<ListenPlan> {
            val first = if (onDevice) listOf(ListenPlan(ListenMode.ON_DEVICE)) else emptyList()
            val chosen: List<Int?> = if (services > 0) (0 until services).toList() else listOf(null)
            return first +
                chosen.flatMap { service ->
                    listOf(
                        ListenPlan(ListenMode.OFFLINE_FIRST, service),
                        ListenPlan(ListenMode.ONLINE, service),
                    )
                }
        }

        /**
         * En qué orden probar los servicios de voz de [packages], que llegan en el orden del
         * sistema: primero los de [PREFERRED_PACKAGES], en ese orden, porque son los que atienden a
         * cualquier app en casi todos los teléfonos; después el resto, como vinieron. Devuelve sus
         * lugares en [packages].
         */
        fun serviceOrder(packages: List<String>): List<Int> = packages.indices.sortedBy { index ->
            PREFERRED_PACKAGES.indexOf(packages[index]).takeIf { it >= 0 }
                ?: PREFERRED_PACKAGES.size
        }

        /** Los paquetes de servicio de voz que se prueban primero, en orden. */
        val PREFERRED_PACKAGES =
            listOf(
                "com.google.android.googlequicksearchbox",
                "com.google.android.as",
                "com.google.android.tts",
            )
    }
}

/** Por qué falló un intento, sin los códigos del sistema. */
enum class ListenError {
    /** La red no respondió o se cortó. */
    NETWORK,

    /** El servicio de voz respondió con un error. */
    SERVER,

    /** El motor no tiene el idioma, o no lo tiene descargado. */
    LANGUAGE,

    /** No se oyó nada que se pudiera convertir en texto. */
    NO_SPEECH,

    /**
     * El servicio de voz no tiene el permiso del micrófono. El de la app ya se pidió antes de
     * escuchar: este lo da el sistema a ese servicio, que es otra app.
     */
    PERMISSION,

    /** El servicio de voz está ocupado con otra app. */
    BUSY,

    /** Cualquier otro fallo del micrófono o del servicio. */
    OTHER,
}

/**
 * En qué va la pantalla de escuchar. En cada estado de un intento, `micDenied` dice si algún
 * intento anterior falló porque su servicio no tiene el micrófono: si al final ninguno sirve, el
 * aviso lo dice.
 */
sealed interface ListeningState {
    /** Todavía no empezó. */
    data object Idle : ListeningState

    /** El motor se está preparando con el intento [attempt]. */
    data class Starting(val attempt: Int, val micDenied: Boolean = false) : ListeningState

    /** Escuchando: [partial] es lo que va entendiendo, vacío al comienzo. */
    data class Listening(val attempt: Int, val partial: String, val micDenied: Boolean = false) :
        ListeningState

    /** Se tocó `Listo`: el motor termina de convertir lo dicho hasta ahí. */
    data class Finishing(val attempt: Int, val partial: String, val micDenied: Boolean = false) :
        ListeningState

    /** No se oyó ninguna frase: se ofrece escuchar otra vez con el mismo intento, o escribir. */
    data class Silent(val attempt: Int, val micDenied: Boolean = false) : ListeningState

    /** Terminó con [text]: va a la tarjeta. */
    data class Heard(val text: String) : ListeningState

    /**
     * Ningún intento sirvió; [error] es el del último. Con [micDenied], alguno falló porque su
     * servicio no tiene el micrófono.
     */
    data class Failed(val error: ListenError, val micDenied: Boolean = false) : ListeningState

    /** La persona cerró la pantalla de escuchar. */
    data object Cancelled : ListeningState
}

/** Si [ListeningState] ya terminó y la pantalla de escuchar se cierra. */
val ListeningState.isFinal: Boolean
    get() = when (this) {
        is ListeningState.Heard, is ListeningState.Failed, ListeningState.Cancelled -> true

        ListeningState.Idle,
        is ListeningState.Starting,
        is ListeningState.Listening,
        is ListeningState.Finishing,
        is ListeningState.Silent,
        -> false
    }

/** Lo que pasa mientras se escucha: lo que avisa el motor y lo que toca la persona. */
sealed interface ListenEvent {
    /** Se abrió la pantalla de escuchar, o se tocó `Escuchar otra vez`. */
    data object Start : ListenEvent

    /** El motor ya oye. */
    data object Ready : ListenEvent

    /** El motor entendió [text] hasta ahora. */
    data class Partial(val text: String) : ListenEvent

    /** El motor terminó con [text]. */
    data class Final(val text: String) : ListenEvent

    /** El motor falló por [error]. */
    data class Error(val error: ListenError) : ListenEvent

    /** `Listo`: lo dicho hasta ahora basta. */
    data object Finish : ListenEvent

    /** `Cancelar`, atrás, cerrar la hoja o salir de la app. */
    data object Cancel : ListenEvent
}

/** Lo que el motor tiene que hacer después de un paso. */
sealed interface ListenCommand {
    data object None : ListenCommand

    /** Soltar el intento anterior, si lo hay, y escuchar con [plan]. */
    data class Listen(val plan: ListenPlan) : ListenCommand

    /** Dejar de oír y convertir lo dicho hasta ahí. */
    data object Stop : ListenCommand

    /** Cancelar y soltar el motor: el micrófono se apaga. */
    data object Release : ListenCommand
}

/** Un paso de [ListeningMachine]: el estado nuevo y lo que el motor debe hacer. */
data class ListenStep(val state: ListeningState, val command: ListenCommand = ListenCommand.None)

/**
 * Las reglas de la pantalla de escuchar, sin nada de Android: así se prueban en la JVM.
 *
 * - **Fallar hacia el teclado, nunca a medias.** Si un intento falla por cualquier cosa que no
 *   sea silencio, se prueba el siguiente de [plans] sin preguntar: otro modo u otro servicio de voz
 *   pueden servir donde el anterior no, como un servicio sin micrófono o sin el idioma. Si ya no
 *   quedan, termina en [ListeningState.Failed] con el último error, y la hoja abre con el teclado.
 * - **Lo dicho no se pierde.** Si el motor falla después de haber entendido algo, ese texto se
 *   usa: la persona lo revisa en la tarjeta antes de registrar.
 * - **El silencio no es un fallo.** Sin frase, se ofrece escuchar otra vez o escribir.
 *
 * @param plans los intentos en orden, al menos uno.
 */
class ListeningMachine(private val plans: List<ListenPlan>) {
    init {
        require(plans.isNotEmpty()) { "Hace falta al menos un intento" }
    }

    fun next(state: ListeningState, event: ListenEvent): ListenStep {
        if (state.isFinal) return ListenStep(state)
        return when (event) {
            ListenEvent.Start -> start(state)
            ListenEvent.Ready -> ready(state)
            is ListenEvent.Partial -> partial(state, clean(event.text))
            is ListenEvent.Final -> final(state, clean(event.text))
            is ListenEvent.Error -> error(state, event.error)
            ListenEvent.Finish -> finish(state)
            ListenEvent.Cancel -> ListenStep(ListeningState.Cancelled, ListenCommand.Release)
        }
    }

    private fun start(state: ListeningState): ListenStep = when (state) {
        ListeningState.Idle -> listen(0)

        is ListeningState.Silent -> listen(state.attempt, state.micDenied)

        // Ya está escuchando, o ya terminó: un segundo toque no abre otro intento.
        is ListeningState.Starting,
        is ListeningState.Listening,
        is ListeningState.Finishing,
        is ListeningState.Heard,
        is ListeningState.Failed,
        ListeningState.Cancelled,
        -> ListenStep(state)
    }

    private fun ready(state: ListeningState): ListenStep = when (state) {
        is ListeningState.Starting ->
            ListenStep(ListeningState.Listening(state.attempt, "", state.micDenied))

        ListeningState.Idle,
        is ListeningState.Listening,
        is ListeningState.Finishing,
        is ListeningState.Silent,
        is ListeningState.Heard,
        is ListeningState.Failed,
        ListeningState.Cancelled,
        -> ListenStep(state)
    }

    private fun partial(state: ListeningState, text: String): ListenStep = when (state) {
        // Algunos motores mandan lo entendido sin avisar antes que ya oyen.
        is ListeningState.Starting ->
            ListenStep(ListeningState.Listening(state.attempt, text, state.micDenied))

        is ListeningState.Listening ->
            ListenStep(state.copy(partial = text.ifEmpty { state.partial }))

        is ListeningState.Finishing ->
            ListenStep(state.copy(partial = text.ifEmpty { state.partial }))

        // Un aviso tardío de un intento que ya terminó no cambia nada.
        ListeningState.Idle,
        is ListeningState.Silent,
        is ListeningState.Heard,
        is ListeningState.Failed,
        ListeningState.Cancelled,
        -> ListenStep(state)
    }

    private fun final(state: ListeningState, text: String): ListenStep {
        val attempt = state.attemptOrNull() ?: return ListenStep(state)
        val heard = text.ifEmpty { state.partialOrEmpty() }
        return if (heard.isEmpty()) {
            ListenStep(
                ListeningState.Silent(attempt, state.micDeniedOrFalse()),
                ListenCommand.Release,
            )
        } else {
            ListenStep(ListeningState.Heard(heard), ListenCommand.Release)
        }
    }

    private fun error(state: ListeningState, error: ListenError): ListenStep {
        val attempt = state.attemptOrNull() ?: return ListenStep(state)
        val partial = state.partialOrEmpty()
        val micDenied = state.micDeniedOrFalse() || error == ListenError.PERMISSION
        return when {
            partial.isNotEmpty() -> ListenStep(ListeningState.Heard(partial), ListenCommand.Release)

            // El servicio oyó bien y no hubo frase: otro servicio no oiría más.
            error == ListenError.NO_SPEECH ->
                ListenStep(
                    ListeningState.Silent(attempt, state.micDeniedOrFalse()),
                    ListenCommand.Release,
                )

            attempt + 1 < plans.size -> listen(attempt + 1, micDenied)

            else -> ListenStep(ListeningState.Failed(error, micDenied), ListenCommand.Release)
        }
    }

    private fun finish(state: ListeningState): ListenStep = when (state) {
        is ListeningState.Starting ->
            ListenStep(
                ListeningState.Finishing(state.attempt, "", state.micDenied),
                ListenCommand.Stop,
            )

        is ListeningState.Listening ->
            ListenStep(
                ListeningState.Finishing(state.attempt, state.partial, state.micDenied),
                ListenCommand.Stop,
            )

        ListeningState.Idle,
        is ListeningState.Finishing,
        is ListeningState.Silent,
        is ListeningState.Heard,
        is ListeningState.Failed,
        ListeningState.Cancelled,
        -> ListenStep(state)
    }

    private fun listen(attempt: Int, micDenied: Boolean = false): ListenStep = ListenStep(
        ListeningState.Starting(attempt, micDenied),
        ListenCommand.Listen(plans[attempt]),
    )

    private fun ListeningState.attemptOrNull(): Int? = when (this) {
        is ListeningState.Starting -> attempt

        is ListeningState.Listening -> attempt

        is ListeningState.Finishing -> attempt

        ListeningState.Idle,
        is ListeningState.Silent,
        is ListeningState.Heard,
        is ListeningState.Failed,
        ListeningState.Cancelled,
        -> null
    }

    private fun ListeningState.micDeniedOrFalse(): Boolean = when (this) {
        is ListeningState.Starting -> micDenied

        is ListeningState.Listening -> micDenied

        is ListeningState.Finishing -> micDenied

        is ListeningState.Silent -> micDenied

        is ListeningState.Failed -> micDenied

        ListeningState.Idle,
        is ListeningState.Heard,
        ListeningState.Cancelled,
        -> false
    }

    private fun ListeningState.partialOrEmpty(): String = when (this) {
        is ListeningState.Listening -> partial

        is ListeningState.Finishing -> partial

        ListeningState.Idle,
        is ListeningState.Starting,
        is ListeningState.Silent,
        is ListeningState.Heard,
        is ListeningState.Failed,
        ListeningState.Cancelled,
        -> ""
    }

    private companion object {
        /** Una frase dictada nunca es tan larga: lo que sobra no es una operación. */
        const val MAX_HEARD_LENGTH = 500

        fun clean(text: String): String = text.trim().take(MAX_HEARD_LENGTH).trim()
    }
}

/** Por qué `Decir o escribir` abre con el teclado y no con la frase oída. */
enum class VoiceNotice {
    /** Ningún servicio de voz pudo escuchar, ni sin conexión ni por la red. */
    FAILED,

    /** Ningún servicio sirvió, y alguno no tiene el permiso del micrófono en el teléfono. */
    SERVICE_NO_MIC,

    /** La persona no dio el permiso del micrófono. */
    NO_PERMISSION,

    /** Android ya no deja preguntar: el permiso se da en los ajustes del teléfono. */
    PERMISSION_BLOCKED,
}

/** Cómo terminó un pedido de escuchar. */
sealed interface VoiceResult {
    /** Se oyó [text]: va a la tarjeta. */
    data class Heard(val text: String) : VoiceResult

    /** Queda el teclado, con [notice] si hay algo que decir. */
    data class Keyboard(val notice: VoiceNotice?) : VoiceResult

    /** La persona cerró sin escribir ni decir nada. */
    data object Cancelled : VoiceResult
}
