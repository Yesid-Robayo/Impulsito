package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.domain.rule.backup.RecoveryCodeCheckRule
import kotlin.time.Instant

/**
 * El sobre del código y el estado del cifrado, juntos: lo que la app consulta para saber si las
 * copias salen cifradas y con qué clave.
 *
 * Al activar o cambiar el código, el sobre se escribe antes que el estado. Por eso, al arrancar,
 * el sobre manda: si abre y el estado no lo conoce, el estado se alinea con él. La única excepción
 * es el `key_id` que la persona apagó a propósito, que sigue apagado.
 */
internal class RecoveryCustody(
    val keys: RecoveryKeyStore,
    val state: RecoveryStateStore,
    private val nowUtcMillis: () -> Long,
) {
    /**
     * Alinea el estado con el sobre. Si el sobre no está o no abre, no cambia nada: sin el código a
     * la vista no hay nada que alinear, y el respaldo lo informa.
     */
    suspend fun alignAtStartup(): RecoveryState = when (val read = keys.open()) {
        is RecoveryKeyRead.Available -> state.alignWith(read.key.keyIdHex, nowUtcMillis())

        RecoveryKeyRead.Absent,
        is RecoveryKeyRead.Unavailable,
        is RecoveryKeyRead.RetryLater,
        -> state.read()
    }

    /**
     * Anota que la persona escribió bien el código de [keyIdHex], si sigue siendo el vigente y el
     * cifrado está activo. Una prueba con otro código, o con el cifrado apagado, no anota nada.
     * Devuelve si quedó anotada; lanza `IOException` si el disco falla.
     */
    suspend fun recordCodeCheck(keyIdHex: String): Boolean {
        val now = nowUtcMillis()
        val result =
            state.update { current ->
                if (current.enabled && current.keyIdHex == keyIdHex) {
                    current.copy(lastCodeCheckAtUtcMillis = now)
                } else {
                    current
                }
            }
        return result.lastCodeCheckAtUtcMillis == now && result.keyIdHex == keyIdHex
    }

    /**
     * Si toca la prueba periódica del código, para `Salud de mis datos` y el mantenimiento diario.
     * Solo lee `state.json`: no abre el sobre ni toca Keystore.
     */
    suspend fun checkFacts(): RecoveryCheckFacts = state.read().checkFacts(nowUtcMillis())
}

/**
 * La prueba periódica del código, vista desde el estado del cifrado.
 *
 * @property active si el cifrado está activo: sin él no hay prueba.
 * @property due si toca pedir la prueba.
 * @property lastCheckAt la última prueba correcta con el código vigente.
 */
internal data class RecoveryCheckFacts(
    val active: Boolean,
    val due: Boolean,
    val lastCheckAt: Instant?,
) {
    companion object {
        val NONE = RecoveryCheckFacts(active = false, due = false, lastCheckAt = null)
    }
}

/**
 * Si toca la prueba periódica, con los plazos de [RecoveryCodeCheckRule]. Sin fecha de activación,
 * que solo falta en un estado escrito a medias por otra versión, la prueba se pide: un dato raro nunca
 * esconde el pedido.
 */
internal fun RecoveryState.checkFacts(nowUtcMillis: Long): RecoveryCheckFacts {
    if (!enabled) return RecoveryCheckFacts.NONE
    val lastCheck = lastCodeCheckAtUtcMillis?.let(Instant::fromEpochMilliseconds)
    val enabledAt = enabledAtUtcMillis?.let(Instant::fromEpochMilliseconds)
    val due =
        enabledAt == null ||
            RecoveryCodeCheckRule.isDue(
                encryptionEnabled = true,
                enabledAt = enabledAt,
                lastCorrectCheckAt = lastCheck,
                now = Instant.fromEpochMilliseconds(nowUtcMillis),
            )
    return RecoveryCheckFacts(active = true, due = due, lastCheckAt = lastCheck)
}
