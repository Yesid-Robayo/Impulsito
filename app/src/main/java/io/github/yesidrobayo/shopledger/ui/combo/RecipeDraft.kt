package io.github.yesidrobayo.shopledger.ui.combo

import androidx.lifecycle.SavedStateHandle

/**
 * La receta a medio armar: lo que la persona agregó y escribió, tal cual. Nada se escribe en la
 * base hasta guardar; vive en el estado guardado de la pantalla, que sobrevive a una llamada, a
 * cambiar de app y a que Android cierre el proceso.
 *
 * @param lines las filas, en el orden en que se armaron.
 * @param showMissing se intentó guardar: ya se marcan las cantidades que faltan.
 */
data class RecipeDraft(
    val lines: List<RecipeLineDraft> = emptyList(),
    val nextKey: Long = 1L,
    val showMissing: Boolean = false,
)

/** Una fila de la receta, tal como se escribió. */
data class RecipeLineDraft(val key: Long, val productId: Long, val quantityText: String)

/**
 * Guarda la receta en edición en el estado guardado, con tipos que caben en un `Bundle`: números
 * en un arreglo y lo escrito aparte. Si las partes no cuadran entre sí, se descarta y se vuelve a
 * la receta guardada.
 */
internal object RecipeDraftStore {
    private const val LINES = "recipe.lines"
    private const val TEXTS = "recipe.texts"
    private const val NEXT_KEY = "recipe.nextKey"
    private const val SHOW_MISSING = "recipe.showMissing"

    /** Clave y producto de cada fila. */
    private const val FIELDS = 2

    /** La receta guardada en [state], o `null` si no hay una que se pueda leer. */
    fun read(state: SavedStateHandle): RecipeDraft? {
        val numbers = state.get<LongArray>(LINES) ?: return null
        val texts = state.get<ArrayList<String>>(TEXTS) ?: return null
        val count = numbers.size / FIELDS
        if (numbers.size % FIELDS != 0 || texts.size != count) return null
        return RecipeDraft(
            lines = (0 until count).map { index ->
                RecipeLineDraft(
                    key = numbers[index * FIELDS],
                    productId = numbers[index * FIELDS + 1],
                    quantityText = texts[index],
                )
            },
            nextKey = state.get<Long>(NEXT_KEY) ?: (count + 1L),
            showMissing = state.get<Boolean>(SHOW_MISSING) ?: false,
        )
    }

    fun write(state: SavedStateHandle, draft: RecipeDraft) {
        val numbers = LongArray(draft.lines.size * FIELDS)
        draft.lines.forEachIndexed { index, line ->
            numbers[index * FIELDS] = line.key
            numbers[index * FIELDS + 1] = line.productId
        }
        state[LINES] = numbers
        state[TEXTS] = ArrayList(draft.lines.map { it.quantityText })
        state[NEXT_KEY] = draft.nextKey
        state[SHOW_MISSING] = draft.showMissing
    }
}
