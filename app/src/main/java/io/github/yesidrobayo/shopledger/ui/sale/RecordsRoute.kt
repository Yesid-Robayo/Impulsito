package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesRoute
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseHistoryRoute

/** Los tres segmentos de la raíz de Registros, en su orden. */
enum class RecordsTab {
    SALES,
    PURCHASES,
    EXPENSES,
}

/**
 * La raíz de Registros: dibuja en el mismo lugar el segmento elegido, que sobrevive al giro, a
 * abrir un detalle y volver, y a que Android cierre el proceso. Cambiar de segmento no apila nada
 * ni abre otra pantalla: la barra de arriba, el banner y los segmentos quedan quietos, la barra
 * inferior sigue a la vista y atrás sigue llevando a Inicio.
 *
 * Cada segmento conserva su ViewModel mientras Registros esté abierto: volver a uno ya visto lo
 * muestra con los filtros que tenía.
 */
@Composable
fun RecordsRoute(navigator: AppNavigator) {
    var tab by rememberSaveable { mutableStateOf(RecordsTab.SALES) }
    val select: (RecordsTab) -> Unit = { tab = it }
    when (tab) {
        RecordsTab.SALES -> SaleHistoryRoute(navigator, select)

        RecordsTab.PURCHASES ->
            PurchaseHistoryRoute(AppNavKey.PurchaseHistory(), navigator, onSegment = select)

        RecordsTab.EXPENSES -> ExpensesRoute(AppNavKey.Expenses(), navigator, onSegment = select)
    }
}
