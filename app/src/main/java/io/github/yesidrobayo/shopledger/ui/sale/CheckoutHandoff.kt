package io.github.yesidrobayo.shopledger.ui.sale

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * La venta que pasa de la venta nueva al cobro. Vive en la actividad, no en una pantalla: el cobro es
 * otra entrada de la pila y no tiene argumentos, así que lee de aquí lo que la persona eligió.
 *
 * La venta nueva deja una copia al abrir el cobro, y el cobro la toma una sola vez y la guarda en su
 * propio estado. Atrás no devuelve nada: la venta nueva conserva lo suyo intacto, y al volver a
 * abrir el cobro empieza de nuevo con la venta como esté entonces.
 *
 * Guarda solo lo que la persona escribió, en el estado guardado de la actividad, para que sobreviva a
 * que Android cierre el proceso entre las dos pantallas.
 */
class CheckoutHandoff(private val state: SavedStateHandle) : ViewModel() {
    /** Deja [draft] para el cobro. Con [qrOnly], el cobro abre directo la imagen de cobro. */
    fun offer(draft: SaleDraft, qrOnly: Boolean) {
        SaleDraftStore.write(state, draft)
        state[QR_ONLY] = qrOnly
        state[OFFERED] = true
    }

    /** La venta dejada para el cobro, una sola vez, o `null` si no hay ninguna. */
    fun take(): Offered? {
        if (state.get<Boolean>(OFFERED) != true) return null
        val offered = Offered(SaleDraftStore.read(state), state.get<Boolean>(QR_ONLY) ?: false)
        SaleDraftStore.write(state, SaleDraft())
        state[OFFERED] = false
        return offered
    }

    /** Lo que la venta nueva dejó: la venta y si el cobro abre en la imagen de cobro. */
    data class Offered(val draft: SaleDraft, val qrOnly: Boolean)

    private companion object {
        const val OFFERED = "checkout.offered"
        const val QR_ONLY = "checkout.qrOnly"
    }
}

/** El traspaso de la actividad: el mismo para la venta nueva y para el cobro. */
@Composable
fun rememberCheckoutHandoff(): CheckoutHandoff {
    val activity = requireNotNull(LocalActivity.current as? ComponentActivity) {
        "La navegación vive en una actividad de Compose"
    }
    return viewModel(
        viewModelStoreOwner = activity,
        factory = viewModelFactory { initializer { CheckoutHandoff(createSavedStateHandle()) } },
    )
}
