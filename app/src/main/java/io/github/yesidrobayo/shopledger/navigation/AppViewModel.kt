package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.di.AppContainer

/**
 * Lo que una pantalla recibe para armar su ViewModel: los puertos de la base ya abierta y el
 * contenedor de la app, con su reloj y sus hilos.
 *
 * El ViewModel recibe sus casos de uso armados con estos puertos, nunca el contenedor entero: así
 * sus pruebas lo construyen con dobles.
 */
class ScreenDependencies(val ports: DataPorts, val container: AppContainer)

/** En qué va el ViewModel de una pantalla, que depende de que la base abra. */
sealed interface ScreenModel<out VM : ViewModel> {
    /**
     * La base todavía se abre. Casi nunca se ve: la actividad no muestra la navegación hasta leer
     * los ajustes, así que la base suele estar abierta antes.
     */
    data object Opening : ScreenModel<Nothing>

    /**
     * La base no abre sin riesgo y la app va al rescate. La pantalla no lee nada: muestra lo que le
     * da la navegación para ese caso.
     */
    data object Unavailable : ScreenModel<Nothing>

    data class Ready<VM : ViewModel>(val viewModel: VM) : ScreenModel<VM>
}

/**
 * El ViewModel de la pantalla, armado por [create] la primera vez y conservado mientras su entrada
 * siga en la pila. Lo usa cada `XxxRoute`, que se lo pasa a su `XxxScreen` pura.
 *
 * La base se abre con la compuerta del arranque, que nunca deja a Room crear una base vacía encima
 * de un negocio: si manda al rescate, no se arma ningún ViewModel.
 *
 * Con [key], cada valor tiene su propio ViewModel en la misma entrada de la pila: así un panel de
 * detalle junto a su lista, como en una tableta, arma uno por cada elemento elegido. Sin [key], hay
 * uno por pantalla.
 */
@Composable
inline fun <reified VM : ViewModel> rememberAppViewModel(
    key: String? = null,
    noinline create: (ScreenDependencies) -> VM,
): ScreenModel<VM> {
    val container = (LocalContext.current.applicationContext as ShopLedgerApp).container
    val access by produceState<DataAccess?>(initialValue = null, container) {
        value = container.data.open()
    }
    return when (val current = access) {
        null -> ScreenModel.Opening

        is DataAccess.Rescue -> ScreenModel.Unavailable

        is DataAccess.Ready -> {
            val dependencies = ScreenDependencies(current.ports, container)
            ScreenModel.Ready(
                viewModel(
                    key = key,
                    factory = viewModelFactory { initializer { create(dependencies) } },
                ),
            )
        }
    }
}
