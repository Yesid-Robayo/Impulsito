package io.github.yesidrobayo.shopledger.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import io.github.yesidrobayo.shopledger.R

/**
 * Las cuatro pestañas de la barra inferior. No crecen: ninguna función nueva agrega una pestaña, y
 * lo demás vive en Negocio o en Ajustes, a dos toques.
 */
enum class TopLevelDestination(
    val root: AppNavKey,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    HOME(AppNavKey.Home, R.string.nav_home, R.drawable.ic_home, R.drawable.ic_home_filled),
    RECORDS(
        AppNavKey.SaleHistory,
        R.string.nav_records,
        R.drawable.ic_receipt_long,
        R.drawable.ic_receipt_long_filled,
    ),
    PRODUCTS(
        AppNavKey.ProductList,
        R.string.nav_products,
        R.drawable.ic_inventory_2,
        R.drawable.ic_inventory_2_filled,
    ),
    NUMBERS(
        AppNavKey.Statistics,
        R.string.nav_numbers,
        R.drawable.ic_bar_chart,
        R.drawable.ic_bar_chart_filled,
    ),
    ;

    companion object {
        /** La pestaña cuya raíz es [key], o `null` si [key] no es una raíz. */
        fun ofRoot(key: AppNavKey): TopLevelDestination? = entries.firstOrNull { it.root == key }
    }
}

/**
 * Las reglas de la pila, sin nada de Compose: así se prueban en la JVM.
 *
 * La pila siempre empieza en Inicio. Una pestaña distinta de Inicio queda encima de ella, así que
 * atrás desde cualquier pestaña vuelve a Inicio, y atrás en Inicio sale de la app con la animación
 * del sistema. Los detalles se apilan encima de su pestaña.
 *
 * @param backStack la pila que dibuja la navegación. La modifica en el lugar: con la pila
 *   guardable de Navigation 3, cada cambio se ve en la pantalla y sobrevive al cierre del proceso.
 */
class AppNavigator(private val backStack: MutableList<NavKey>) {
    init {
        if (backStack.isEmpty()) backStack.add(AppNavKey.Home)
        require(backStack.first() == AppNavKey.Home) { "La pila empieza en Inicio" }
    }

    /** La pantalla visible. */
    val current: AppNavKey
        get() = requireNotNull(backStack.last() as? AppNavKey) {
            "La pila solo guarda claves de la app"
        }

    /**
     * La pestaña a la que pertenece lo visible: la de la segunda clave si es una raíz, o Inicio, de
     * la que cuelgan los ajustes, el respaldo y Negocio.
     */
    val currentTab: TopLevelDestination
        get() = (backStack.getOrNull(1) as? AppNavKey)?.let(TopLevelDestination::ofRoot)
            ?: TopLevelDestination.HOME

    /** La barra inferior solo se ve en las cuatro raíces: en detalles y formularios da espacio y foco. */
    val showsBottomBar: Boolean get() = TopLevelDestination.ofRoot(current) != null

    /**
     * Va a la raíz de [tab]. Desde un detalle de la misma pestaña, vuelve a su raíz: tocar la pestaña
     * activa es la forma de salir de lo profundo.
     */
    fun selectTab(tab: TopLevelDestination) {
        val target: List<NavKey> =
            if (tab ==
                TopLevelDestination.HOME
            ) {
                listOf(AppNavKey.Home)
            } else {
                listOf(AppNavKey.Home, tab.root)
            }
        if (backStack.toList() == target) return
        replaceWith(target)
    }

    /** Abre [key] encima de lo visible. Abrir una raíz es lo mismo que tocar su pestaña. */
    fun open(key: AppNavKey) {
        val tab = TopLevelDestination.ofRoot(key)
        when {
            tab != null -> selectTab(tab)

            // Un doble toque no apila dos veces la misma pantalla.
            current == key -> Unit

            else -> backStack.add(key)
        }
    }

    /** Abre una venta nueva encima de lo visible, desde cualquier raíz. */
    fun sell() = open(AppNavKey.NewSale())

    /** Lo que pasa entre la venta nueva y sus ventas en espera. */
    val heldSales: HeldSaleHandoff = HeldSaleHandoff()

    /** Si hay una venta nueva abierta en la pila, visible o debajo de lo visible. */
    val saleOpen: Boolean get() = backStack.any { it is AppNavKey.NewSale }

    /**
     * Retoma la espera [heldSaleId] en la venta nueva: vuelve a ella si ya está abierta, o abre una
     * encima de lo visible. La venta nueva la toma al verse.
     */
    fun resumeHeldSale(heldSaleId: Long) {
        heldSales.choose(heldSaleId)
        val sale = backStack.indexOfLast { it is AppNavKey.NewSale }
        if (sale < 0) {
            backStack.add(AppNavKey.NewSale())
            return
        }
        while (backStack.size > sale + 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Lo que pasa entre la pantalla de clientes, abierta para elegir, y el cobro. */
    val customerPick: CustomerPickHandoff = CustomerPickHandoff()

    /**
     * Deja [customerId] elegido para la venta y vuelve a la pantalla de debajo, el cobro, que lo
     * toma al verse.
     */
    fun pickCustomerForSale(customerId: Long) {
        customerPick.choose(customerId)
        back()
    }

    /**
     * Vuelve a la pantalla anterior. En Inicio no hace nada y devuelve `false`: ahí atrás le toca al
     * sistema, que cierra la app.
     */
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    private fun replaceWith(target: List<NavKey>) {
        // Se conserva el prefijo común para que Navigation 3 no recree la entrada de Inicio.
        val common = backStack.zip(target).takeWhile { (a, b) -> a == b }.size
        while (backStack.size > common) backStack.removeAt(backStack.lastIndex)
        backStack.addAll(target.drop(common))
    }
}
