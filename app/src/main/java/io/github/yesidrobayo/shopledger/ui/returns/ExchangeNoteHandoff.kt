package io.github.yesidrobayo.shopledger.ui.returns

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
 * La nota de la devolución de un cambio, de la devolución a la venta nueva. La pila guardada solo
 * lleva números y banderas, nunca textos de la persona, así que la nota viaja aquí: en el estado
 * guardado de la actividad, que sobrevive a que Android cierre el proceso entre las dos pantallas.
 *
 * Cada nota va con la venta de origen: la venta nueva de otro cambio no la toma.
 */
class ExchangeNoteHandoff(private val state: SavedStateHandle) : ViewModel() {
    /** Deja [note] para el cambio de la venta [saleId], o nada si está en blanco. */
    fun offer(saleId: Long, note: String?) {
        state[SALE] = saleId
        state[NOTE] = note
    }

    /** La nota que dejó la devolución de [saleId], o `null`. Se puede leer más de una vez. */
    fun noteFor(saleId: Long): String? =
        state.get<String>(NOTE).takeIf { state.get<Long>(SALE) == saleId }

    private companion object {
        const val SALE = "exchange.sale"
        const val NOTE = "exchange.note"
    }
}

/** El traspaso de la actividad: el mismo para la devolución y para la venta nueva. */
@Composable
fun rememberExchangeNoteHandoff(): ExchangeNoteHandoff {
    val activity = requireNotNull(LocalActivity.current as? ComponentActivity) {
        "La navegación vive en una actividad de Compose"
    }
    return viewModel(
        viewModelStoreOwner = activity,
        factory = viewModelFactory {
            initializer { ExchangeNoteHandoff(createSavedStateHandle()) }
        },
    )
}
