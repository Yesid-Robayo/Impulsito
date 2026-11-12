package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate

/**
 * Un código de recuperación recién creado, antes de activarlo, en su forma impresa: 28 caracteres en
 * 7 grupos de 4. Vive solo en memoria: nunca se guarda en el estado de una pantalla, un `Intent` ni
 * un trabajo en segundo plano, y su forma de texto no lo muestra.
 */
class RecoveryCodeDraft(val groups: List<String>) {
    init {
        require(groups.size == GROUPS && groups.all { it.length == GROUP_LENGTH }) {
            "Código con forma inválida"
        }
    }

    override fun toString(): String = "RecoveryCodeDraft(****)"

    companion object {
        const val GROUPS = 7
        const val GROUP_LENGTH = 4
    }
}

/** El estado del cifrado de las copias en este teléfono. */
sealed interface EncryptionStatus {
    /**
     * Apagado. [wasOn] si estuvo activo y se apagó a propósito: volver a activarlo crea otro
     * código.
     */
    data class Off(val wasOn: Boolean) : EncryptionStatus

    /** Las copias salen cifradas, con el código activado en [enabledAtUtcMillis]. */
    data class On(val enabledAtUtcMillis: Long?) : EncryptionStatus

    /**
     * Activo, pero el código sellado ya no abre: las copias están detenidas y nunca salen en claro.
     */
    data object KeyUnavailable : EncryptionStatus

    /** No se pudo leer el estado. La pantalla lo dice y no ofrece nada que cambie datos. */
    data object Unknown : EncryptionStatus
}

/** Lo que dio comparar el código escrito con el que se mostró. */
sealed interface TypedCodeCheck {
    data object Matches : TypedCodeCheck

    /** Le faltan o le sobran caracteres. */
    data object Incomplete : TypedCodeCheck

    /** El grupo [group], contado desde 1, no coincide. */
    data class GroupMismatch(val group: Int) : TypedCodeCheck
}

/** Lo que dio activar el cifrado. */
enum class ActivationOutcome {
    /** Quedó activo: la siguiente copia sale cifrada. */
    ACTIVATED,

    /** No se pudo guardar el código en este teléfono. El cifrado sigue apagado. */
    SAVE_FAILED,
}

/**
 * El nombre del kit de recuperación: `recovery-kit-AAAAMMDD.pdf`, igual en cualquier idioma y
 * región. La fecha solo orienta a quien lo busca.
 */
object RecoveryKitFileName {
    private const val PREFIX = "recovery-kit-"
    private const val SUFFIX = ".pdf"

    fun of(date: BusinessDate): String = PREFIX + date.toString().replace("-", "") + SUFFIX
}

/** Lo que dio escribir el código de las copias para volver a guardarlo en este teléfono. */
enum class ResealOutcome {
    /** Quedó guardado: las copias vuelven a salir, cifradas. */
    SEALED,

    /** Un error de escritura, detectado por los caracteres de control. Nada se borró. */
    TYPO,

    /** Es un código válido, pero de otras copias. Nada cambió. */
    OTHER_CODE,

    /** No se pudo guardar en este teléfono. Nada cambió. */
    SAVE_FAILED,
}
