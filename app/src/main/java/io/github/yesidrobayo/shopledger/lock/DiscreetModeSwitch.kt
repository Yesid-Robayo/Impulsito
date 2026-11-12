package io.github.yesidrobayo.shopledger.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Cambia el modo discreto. Ocultar es inmediato. Mostrar de nuevo es una operación sensible: con el
 * bloqueo encendido, antes pide la verificación del sistema, y si se cancela las cifras siguen
 * ocultas.
 *
 * @param check la verificación de las operaciones sensibles.
 * @param save guarda la preferencia.
 * @param reason lo que dice el diálogo del sistema al pedir mostrar las cifras.
 */
class DiscreetModeSwitch(
    private val check: suspend (String) -> Boolean,
    private val save: suspend (PreferenceChange) -> ChangePreferenceResult,
    private val reason: String,
) {
    /** Deja el modo discreto en [enabled]. `false` si no cambió: verificación cancelada o falla al guardar. */
    suspend fun set(enabled: Boolean): Boolean {
        if (!enabled && !check(reason)) return false
        return when (save(PreferenceChange.DiscreetMode(enabled))) {
            ChangePreferenceResult.Saved -> true
            is ChangePreferenceResult.Failed -> false
        }
    }
}

/**
 * El interruptor del modo discreto para las pantallas, con la verificación de [sensitive] y el
 * guardado de [save]. Corre en el alcance de la actividad: rotar no lo corta a medias.
 */
@Composable
fun rememberDiscreetModeSwitch(
    sensitive: SensitiveCheck,
    save: suspend (PreferenceChange) -> ChangePreferenceResult,
    scope: CoroutineScope = rememberCoroutineScope(),
): (Boolean) -> Unit {
    val reason = stringResource(R.string.a11y_discreet_auth_reason)
    return remember(sensitive, save, scope, reason) {
        val switch = DiscreetModeSwitch(sensitive::confirm, save, reason)
        val toggle: (Boolean) -> Unit = { enabled -> scope.launch { switch.set(enabled) } }
        toggle
    }
}
