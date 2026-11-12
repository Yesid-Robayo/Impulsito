package io.github.yesidrobayo.shopledger.ui.encryption

import io.github.yesidrobayo.shopledger.ui.components.RECOVERY_CODE_GROUPS

/**
 * `Cifrado de copias`: el estado del cifrado y, mientras se activa, el paso en curso.
 *
 * El código recién creado vive solo aquí, en memoria: nunca en el estado guardado de la pantalla,
 * así que si el sistema cierra la app a mitad de la activación, el código se pierde y el cifrado
 * sigue apagado.
 */
data class EncryptionUiState(
    val status: EncryptionStatusUi = EncryptionStatusUi.Loading,
    val step: ActivationStepUi? = null,
    /** La pregunta `¿Cambiar el código?` a la vista. */
    val confirmChange: Boolean = false,
)

/** Lo que muestra la pantalla fuera de la activación. */
sealed interface EncryptionStatusUi {
    /** Leyendo `state.json`, que es local. */
    data object Loading : EncryptionStatusUi

    /** Apagado. [wasOn] si se apagó a propósito y quedan copias cifradas que se conservan. */
    data class Off(val wasOn: Boolean) : EncryptionStatusUi

    /** Las copias salen cifradas. */
    data object On : EncryptionStatusUi

    /** Activo, pero el código sellado ya no abre: las copias están detenidas. */
    data object KeyUnavailable : EncryptionStatusUi

    /** No se pudo leer el estado: no se ofrece nada que cambie datos. */
    data object Unknown : EncryptionStatusUi
}

/** Los cuatro pasos de la activación, y el cierre. */
sealed interface ActivationStepUi {
    /** 1. `Antes de activar`: el riesgo y la casilla que lo acepta. */
    data class Risk(val accepted: Boolean = false) : ActivationStepUi

    /**
     * 2. `Código de recuperación`, con el kit o el papel. [groups] es el código impreso: la forma
     * de texto del estado no lo muestra.
     */
    class ShowCode(
        val groups: List<String>,
        val kit: KitState = KitState.NONE,
        val onPaper: Boolean = false,
    ) : ActivationStepUi {
        /** Se puede seguir con el kit guardado o con el código anotado en papel. */
        val canContinue: Boolean get() = kit == KitState.SAVED || onPaper

        fun copy(kit: KitState = this.kit, onPaper: Boolean = this.onPaper) =
            ShowCode(groups, kit, onPaper)

        override fun toString(): String = "ShowCode(kit=$kit, onPaper=$onPaper)"
    }

    /** 3. `Escribir el código`. [errorGroup] es el grupo que no coincide, desde 0. */
    class Retype(
        val typed: List<String> = List(RECOVERY_CODE_GROUPS) { "" },
        val errorGroup: Int? = null,
    ) : ActivationStepUi {
        override fun toString(): String = "Retype(errorGroup=$errorGroup)"
    }

    /** 4. `Probando el cifrado`. Sin [failure], la prueba corre. */
    data class Testing(val failure: ActivationFailure? = null) : ActivationStepUi

    /** `Cifrado activado`. [changed] si fue un cambio de código: las copias anteriores siguen con el suyo. */
    data class Done(val changed: Boolean = false) : ActivationStepUi

    /**
     * `Escribir el código` con el cifrado detenido: el código vigente, para volver a guardarlo en
     * este teléfono. [problem] dice por qué no se guardó, sin borrar lo escrito.
     */
    class TypeExisting(
        val typed: List<String> = List(RECOVERY_CODE_GROUPS) { "" },
        val problem: ResealProblem? = null,
        val working: Boolean = false,
    ) : ActivationStepUi {
        override fun toString(): String = "TypeExisting(problem=$problem, working=$working)"
    }
}

/** Por qué no se volvió a guardar el código escrito. Nada se borró en ningún caso. */
enum class ResealProblem {
    /** No coincide: un error de escritura, o el código de otras copias. */
    MISMATCH,

    /** No se pudo guardar en este teléfono. */
    SAVE,
}

/** El kit de recuperación en el paso 2. */
enum class KitState { NONE, SAVING, SAVED, FAILED }

/** Por qué no se activó. En los dos casos el cifrado sigue apagado y nada cambió. */
enum class ActivationFailure {
    /** La prueba de ida y vuelta no pasó. */
    ROUND_TRIP,

    /** No se pudo guardar el código en este teléfono. */
    SAVE,
}

sealed interface EncryptionAction {
    data object Back : EncryptionAction

    /** `Activar cifrado`, ya con la verificación sensible pasada si el bloqueo la pide. */
    data object Start : EncryptionAction

    data class Accept(val accepted: Boolean) : EncryptionAction

    data object ContinueRisk : EncryptionAction

    /** `Guardar kit de recuperación`: la pantalla abre el selector del sistema. */
    data object SaveKit : EncryptionAction

    /** El selector creó el documento del kit. */
    class KitFileCreated(val uri: String) : EncryptionAction

    data object OnPaper : EncryptionAction

    data object ContinueCode : EncryptionAction

    class Typed(val groups: List<String>) : EncryptionAction

    data object CheckCode : EncryptionAction

    data object Retry : EncryptionAction

    data object Finish : EncryptionAction

    /** Salir de la activación: el código se descarta y el cifrado sigue como estaba. */
    data object Exit : EncryptionAction

    /** Un toque llegó tapado por otra app en un paso con el código. */
    data object ObscuredTouch : EncryptionAction

    /** `Cambiar el código`: pide confirmar. */
    data object AskChange : EncryptionAction

    data object CloseChange : EncryptionAction

    /** Confirmó cambiar el código, ya con la verificación sensible pasada si el bloqueo la pide. */
    data object ConfirmChange : EncryptionAction

    /**
     * `Crear otro código` con el cifrado detenido, ya con la verificación sensible. No necesita el
     * anterior: la base del teléfono está en claro.
     */
    data object CreateNewCode : EncryptionAction

    /** `Escribir el código` con el cifrado detenido. */
    data object TypeExistingCode : EncryptionAction

    class TypedExisting(val groups: List<String>) : EncryptionAction

    /** Guardar otra vez el código escrito. */
    data object Reseal : EncryptionAction
}
