package io.github.yesidrobayo.shopledger.ui.phrase

/**
 * Cómo se pide cada intento de escuchar, en orden. El primero que sirve se queda; los demás solo se
 * usan si el anterior falló por algo que otro motor u otra forma de pedirlo puede resolver.
 */
enum class ListenPlan {
    /** El motor del teléfono que trabaja sin red, si lo tiene. */
    ON_DEVICE,

    /** El motor de siempre, pidiéndole que prefiera trabajar sin conexión. */
    OFFLINE_FIRST,

    /** El motor de siempre, sin esa preferencia: puede usar la red si no tiene el idioma. */
    ONLINE,
    ;

    companion object {
        /**
         * Los intentos en orden. Con un motor sin red en el teléfono, ese va primero; sin él, se
         * empieza por pedir sin conexión.
         */
        fun plansFor(onDevice: Boolean): List<ListenPlan> =
            if (onDevice) entries.toList() else listOf(OFFLINE_FIRST, ONLINE)
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

    /** La app no tiene el permiso del micrófono. */
    PERMISSION,

    /** El servicio de voz está ocupado con otra app. */
    BUSY,

    /** Cualquier otro fallo del micrófono o del servicio. */
    OTHER,
}

/** En qué va la pantalla de escuchar. */
sealed interface ListeningState {
    /** Todavía no empezó. */
    data object Idle : ListeningState

    /** El motor se está preparando con el intento [attempt]. */
    data class Starting(val attempt: Int) : ListeningState

    /** Escuchando: [partial] es lo que va entendiendo, vacío al comienzo. */
    data class Listening(val attempt: Int, val partial: String) : ListeningState

    /** Se tocó `Listo`: el motor termina de convertir lo dicho hasta ahí. */
    data class Finishing(val attempt: Int, val partial: String) : ListeningState

    /** No se oyó ninguna frase: se ofrece escuchar otra vez con el mismo intento, o escribir. */
    data class Silent(val attempt: Int) : ListeningState

    /** Terminó con [text]: va a la tarjeta. */
    data class Heard(val text: String) : ListeningState

    /** Ningún intento sirvió, por [error]: queda el teclado. */
    data class Failed(val error: ListenError) : ListeningState

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
 * - **Fallar hacia el teclado, nunca a medias.** Si un intento falla por red, idioma o servidor, se
 *   prueba el siguiente de [plans] sin preguntar; si ya no quedan, termina en
 *   [ListeningState.Failed] y la hoja abre con el teclado. El motor sin red cae al de siempre por
 *   cualquier fallo, porque es el que más falta de idiomas.
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

        is ListeningState.Silent -> listen(state.attempt)

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
        is ListeningState.Starting -> ListenStep(ListeningState.Listening(state.attempt, ""))

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
        is ListeningState.Starting -> ListenStep(ListeningState.Listening(state.attempt, text))

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
            ListenStep(ListeningState.Silent(attempt), ListenCommand.Release)
        } else {
            ListenStep(ListeningState.Heard(heard), ListenCommand.Release)
        }
    }

    private fun error(state: ListeningState, error: ListenError): ListenStep {
        val attempt = state.attemptOrNull() ?: return ListenStep(state)
        val partial = state.partialOrEmpty()
        return when {
            partial.isNotEmpty() -> ListenStep(ListeningState.Heard(partial), ListenCommand.Release)

            error == ListenError.NO_SPEECH ->
                ListenStep(ListeningState.Silent(attempt), ListenCommand.Release)

            error == ListenError.PERMISSION ->
                ListenStep(ListeningState.Failed(error), ListenCommand.Release)

            fallsBack(plans[attempt], error) && attempt + 1 < plans.size -> listen(attempt + 1)

            else -> ListenStep(ListeningState.Failed(error), ListenCommand.Release)
        }
    }

    private fun finish(state: ListeningState): ListenStep = when (state) {
        is ListeningState.Starting ->
            ListenStep(ListeningState.Finishing(state.attempt, ""), ListenCommand.Stop)

        is ListeningState.Listening ->
            ListenStep(ListeningState.Finishing(state.attempt, state.partial), ListenCommand.Stop)

        ListeningState.Idle,
        is ListeningState.Finishing,
        is ListeningState.Silent,
        is ListeningState.Heard,
        is ListeningState.Failed,
        ListeningState.Cancelled,
        -> ListenStep(state)
    }

    private fun listen(attempt: Int): ListenStep =
        ListenStep(ListeningState.Starting(attempt), ListenCommand.Listen(plans[attempt]))

    /** Si vale probar el intento siguiente después de que [plan] falló por [error]. */
    private fun fallsBack(plan: ListenPlan, error: ListenError): Boolean = when (plan) {
        ListenPlan.ON_DEVICE -> true
        ListenPlan.OFFLINE_FIRST -> error in RETRIED_ONLINE
        ListenPlan.ONLINE -> false
    }

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
        /** Los fallos que un servicio sin el idioma descargado da al pedirle que no use la red. */
        val RETRIED_ONLINE = setOf(ListenError.NETWORK, ListenError.LANGUAGE, ListenError.SERVER)

        /** Una frase dictada nunca es tan larga: lo que sobra no es una operación. */
        const val MAX_HEARD_LENGTH = 500

        fun clean(text: String): String = text.trim().take(MAX_HEARD_LENGTH).trim()
    }
}

/** Por qué `Decir o escribir` abre con el teclado y no con la frase oída. */
enum class VoiceNotice {
    /** El servicio de voz no pudo escuchar, ni sin conexión ni por la red. */
    FAILED,

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
