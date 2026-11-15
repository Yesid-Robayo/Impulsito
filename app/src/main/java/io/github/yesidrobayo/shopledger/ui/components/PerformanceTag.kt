package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Las marcas fijas que buscan las mediciones de rendimiento desde fuera de la app, con
 * UiAutomator: Inicio con sus cifras leídas, cada pestaña de la barra inferior y la lista del
 * historial de ventas.
 *
 * Son identificadores en inglés y nunca un texto visible: no cambian con el idioma, TalkBack no
 * los lee y no llevan ningún dato del negocio. El módulo de las mediciones repite estos valores,
 * porque no ve el código de la app: si uno cambia aquí, cambia también allá.
 */
object PerformanceTags {
    /** Inicio, solo cuando ya muestra las cifras leídas de la base. */
    const val HOME_READY = "home_ready"

    /** La lista del historial de ventas, en Registros. */
    const val SALE_HISTORY_LIST = "sale_history_list"

    /** La pestaña de la barra inferior con ese destino, por su nombre en inglés: `tab_records`. */
    fun tab(destination: String): String = "tab_" + destination.lowercase()
}

/**
 * Marca este nodo con [tag] y lo expone como identificador de recurso, que es lo único de un nodo
 * de Compose que UiAutomator encuentra sin depender del texto ni del idioma.
 */
fun Modifier.performanceTag(tag: String): Modifier =
    semantics { testTagsAsResourceId = true }.testTag(tag)
