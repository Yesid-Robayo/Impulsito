package io.github.yesidrobayo.shopledger.ui.safety

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.usecase.safety.PolicyConsentUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Qué pregunta muestra la aceptación de la política, si muestra alguna. */
enum class PolicyQuestion {
    /** La hoja `Política y términos`, antes de conectar un destino. */
    ACCEPT_TO_CONNECT,

    /** El diálogo `Cambió el uso de los datos`, cuando cambió una versión ya aceptada. */
    CHANGED,
}

/** En qué va la compuerta de una pantalla que conecta un destino. */
enum class PolicyGate {
    /** Leyendo la aceptación: todavía no se muestra nada. */
    CHECKING,

    /** Falta aceptar: se muestra la hoja, y la pantalla espera detrás. */
    WAITING,

    /** Aceptada: la pantalla se muestra. */
    ALLOWED,

    /** La persona no aceptó: se vuelve atrás sin conectar nada. */
    DECLINED,
}

/**
 * Lo que ve la persona de la aceptación.
 *
 * @param question la pregunta abierta, si hay una.
 * @param terms si se muestran los términos de uso completos.
 * @param saving si la aceptación se está guardando.
 * @param notSaved si el teléfono no pudo guardarla: nada cambió y se puede intentar otra vez.
 * @param gate la compuerta de la pantalla que conecta un destino, si hay una abierta.
 */
data class PolicyPromptState(
    val question: PolicyQuestion? = null,
    val terms: Boolean = false,
    val saving: Boolean = false,
    val notSaved: Boolean = false,
    val gate: PolicyGate? = null,
)

/**
 * La aceptación de la política y los términos en toda la app: la hoja antes de conectar un destino,
 * desde cualquier entrada, y el diálogo al abrir la app si cambió una versión ya aceptada, que vuelve
 * en cada apertura hasta que la persona acepte.
 *
 * Decir que no nunca bloquea nada: cierra la pregunta, y las copias en la nube quedan en pausa.
 */
class PolicyPrompt(private val consent: PolicyConsentUseCase, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PolicyPromptState())
    val state: StateFlow<PolicyPromptState> = mutable.asStateFlow()

    /** La app se abrió o volvió a primer plano: si cambió lo aceptado, lo pregunta. */
    fun checkOnOpen() {
        scope.launch {
            val changed = read { consent.changedSinceAccepted() } ?: return@launch
            if (changed) {
                mutable.update {
                    if (it.question ==
                        null
                    ) {
                        it.copy(question = PolicyQuestion.CHANGED)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * `Revisar la política`, desde el estado del respaldo en pausa: el diálogo de cambios si había
     * una versión aceptada, o la hoja si nunca se aceptó.
     */
    fun review() {
        scope.launch {
            val status = read { consent.status() } ?: return@launch
            val question =
                when (status) {
                    PolicyConsentStatus.ACCEPTED -> null
                    PolicyConsentStatus.NEVER_ACCEPTED -> PolicyQuestion.ACCEPT_TO_CONNECT
                    PolicyConsentStatus.OUTDATED -> PolicyQuestion.CHANGED
                }
            mutable.update { it.copy(question = question, notSaved = false) }
        }
    }

    /** Se abre una pantalla que conecta un destino: sin la versión vigente aceptada, pregunta. */
    fun enterGate() {
        mutable.update { it.copy(gate = PolicyGate.CHECKING) }
        scope.launch {
            // Si no se puede leer, se pregunta: nunca se conecta sin aceptar.
            val needs = read { consent.needsAcceptance() } ?: true
            mutable.update {
                if (needs) {
                    it.copy(gate = PolicyGate.WAITING, question = PolicyQuestion.ACCEPT_TO_CONNECT)
                } else {
                    it.copy(gate = PolicyGate.ALLOWED)
                }
            }
        }
    }

    /** La pantalla de la compuerta se cerró: la compuerta deja de contar. */
    fun leaveGate() {
        mutable.update { it.copy(gate = null) }
    }

    /** `Aceptar y continuar` o `Aceptar el cambio`. */
    fun accept() {
        if (mutable.value.saving) return
        mutable.update { it.copy(saving = true, notSaved = false) }
        scope.launch {
            val saved = read { consent.accept() } == true
            mutable.update {
                if (saved) {
                    val gate = if (it.gate == PolicyGate.WAITING) PolicyGate.ALLOWED else it.gate
                    PolicyPromptState(gate = gate)
                } else {
                    it.copy(saving = false, notSaved = true)
                }
            }
        }
    }

    /**
     * `Ahora no`, o cerrar la hoja: no registra nada. Antes de conectar, vuelve atrás sin conectar;
     * el diálogo de cambios vuelve en la próxima apertura.
     */
    fun later() {
        mutable.update {
            val gate = if (it.gate == PolicyGate.WAITING) PolicyGate.DECLINED else it.gate
            it.copy(question = null, terms = false, saving = false, notSaved = false, gate = gate)
        }
    }

    /** `Leer el texto completo` o `Términos de uso`. */
    fun openTerms() {
        mutable.update { it.copy(terms = true) }
    }

    fun closeTerms() {
        mutable.update { it.copy(terms = false) }
    }

    /** Lo leído, o nulo si falló: leer la aceptación nunca cierra la app. */
    private suspend fun <T> read(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }
}

/**
 * La aceptación de la app, para las pantallas que conectan un destino y para `Revisar la política`.
 * No tiene valor por defecto: solo existe dentro de la navegación.
 */
val LocalPolicyPrompt =
    staticCompositionLocalOf<PolicyPrompt> {
        error("Falta la aceptación de la política: solo existe dentro de la navegación")
    }
