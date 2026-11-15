package io.github.yesidrobayo.shopledger.surface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.yesidrobayo.shopledger.domain.model.surface.DeviceLockState
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Escucha, mientras el proceso vive, cuándo se apaga o se enciende la pantalla y cuándo la persona
 * desbloquea. En cada cambio publica el estado y pide redibujar el widget, para que un monto no
 * quede dibujado con el teléfono bloqueado, y vuelva al desbloquear si la persona lo pidió.
 *
 * Android solo entrega estas difusiones a un receptor registrado en tiempo de ejecución. Con el
 * proceso muerto no llegan, y el widget conserva su último dibujo: por eso el monto es opcional,
 * apagado por defecto, y el widget no se ofrece en la pantalla de bloqueo.
 *
 * @param scope el alcance de la aplicación: el redibujo sigue aunque el receptor ya respondió.
 * @param onChange lo que redibuja el widget. Nunca lanza fuera: una falla queda en el registro.
 */
class LockStateWatcher(
    private val scope: CoroutineScope,
    private val log: EventLog,
    private val onChange: suspend () -> Unit,
) {
    private val mutableState = MutableStateFlow(DeviceLockState.fullyLocked)
    private var registered = false

    /** El último estado conocido. Quien dibuja lo combina con una lectura propia, la más estricta. */
    val state: StateFlow<DeviceLockState> = mutableState.asStateFlow()

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // No lee extras: solo la acción, y solo una de las tres que escucha.
                val action = intent.action ?: return
                if (action !in WATCHED_ACTIONS) return
                val reading = LockStateReader.read(context)
                // Con la pantalla apagada se oculta enseguida, aunque el bloqueo tarde en armarse.
                val screenOff = action == Intent.ACTION_SCREEN_OFF
                mutableState.value = if (screenOff) reading.copy(screenOn = false) else reading
                val pending = goAsync()
                scope.launch {
                    try {
                        log.contain(SurfaceEvent.WIDGET_REFRESH_FAILED) { onChange() }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }

    /** Empieza a escuchar. Se llama una vez por proceso, al crear la aplicación. */
    @Synchronized
    fun register(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        mutableState.value = LockStateReader.read(appContext)
        val filter = IntentFilter().apply { WATCHED_ACTIONS.forEach { addAction(it) } }
        // Exportado a propósito, y es seguro: las tres acciones son difusiones protegidas, que solo
        // envían el sistema y SystemUI, y el receptor no lee extras ni otra acción. Sin exportar
        // falla: antes de Android 13, ContextCompat exige al emisor un permiso de firma de la app,
        // y SystemUI, que envía el desbloqueo, no lo tiene.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
    }

    private companion object {
        val WATCHED_ACTIONS =
            setOf(Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT)
    }
}
