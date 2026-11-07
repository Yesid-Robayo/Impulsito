package io.github.yesidrobayo.shopledger.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sigue a la app entera, no a una pantalla, para el respaldo: se registra en el ciclo de vida del
 * proceso, que dice cuándo la app se abre y cuándo pasa a segundo plano.
 *
 * - **Al abrirse**, pregunta al estado del respaldo qué hacer y lo encola: con la app a la vista no
 *   aplican los ahorros de batería, y un teléfono que mata los trabajos en segundo plano recupera
 *   aquí la copia que no salió.
 * - **Al pasar a segundo plano**, la copia de los cambios pendientes sale sin esperar, salvo el
 *   intervalo que pida la red.
 *
 * Nada corre en el hilo principal ni espera a la red: cada evento se atiende aparte, en [scope].
 *
 * @param appOpenUpload lo que decide el estado del respaldo al abrir. Abre la base si hace falta.
 * @param onAppOpened encola lo que decidió [appOpenUpload].
 * @param onAppBackgrounded programa la copia al pasar a segundo plano.
 */
class AppLifecycleObserver(
    private val scope: CoroutineScope,
    private val appOpenUpload: suspend () -> AppOpenUpload,
    private val onAppOpened: suspend (AppOpenUpload) -> Unit,
    private val onAppBackgrounded: suspend () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        scope.launch { onAppOpened(appOpenUpload()) }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch { onAppBackgrounded() }
    }
}
