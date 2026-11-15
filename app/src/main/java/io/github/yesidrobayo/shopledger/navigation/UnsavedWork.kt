package io.github.yesidrobayo.shopledger.navigation

import androidx.navigation3.runtime.NavKey

/**
 * La pregunta de descartar de una pantalla, con los mismos textos que muestra su atrás.
 *
 * @param dismiss el texto de seguir, o `null` si la pantalla usa el de siempre del diálogo.
 * @param destructive si la pantalla la muestra como acción destructiva, como salir de una venta.
 */
data class DiscardTexts(
    val title: String,
    val body: String,
    val confirm: String,
    val dismiss: String? = null,
    val destructive: Boolean = false,
)

/**
 * Las pantallas de la pila con trabajo sin guardar: un formulario con cambios, una venta con
 * productos, una compra con líneas. Cada una se marca con lo mismo que enciende su atrás de
 * descartar, y el riel lo consulta antes de cambiar de sección.
 *
 * La marca vive por la clave de la pantalla y no por su composición: una venta tapada por el cobro
 * deja de verse, pero sigue en la pila con sus productos. Las de pantallas que ya salieron de la
 * pila se olvidan al consultar. Todo pasa en el hilo principal, sin nada de Compose: se prueba en
 * la JVM.
 */
class UnsavedWork {
    private val marks = LinkedHashMap<NavKey, DiscardTexts>()

    /** Si ninguna pantalla tiene trabajo sin guardar. */
    val isEmpty: Boolean get() = marks.isEmpty()

    /** [key] tiene trabajo sin guardar, y se pregunta con [texts] antes de quitarla. */
    fun mark(key: NavKey, texts: DiscardTexts) {
        marks[key] = texts
    }

    /** [key] ya no tiene nada sin guardar. */
    fun clear(key: NavKey) {
        marks.remove(key)
    }

    /**
     * La pregunta antes de quitar [dropped] de [stack]: la de la pantalla más arriba con trabajo
     * sin guardar, o `null` si ninguna lo tiene y se puede seguir sin preguntar.
     */
    fun questionFor(stack: List<NavKey>, dropped: List<NavKey>): DiscardTexts? {
        marks.keys.retainAll(stack.toSet())
        return dropped.asReversed().firstNotNullOfOrNull { marks[it] }
    }

    /** Lo de [dropped] se descartó: sus marcas se olvidan. */
    fun discard(dropped: List<NavKey>) {
        dropped.forEach { marks.remove(it) }
    }
}
