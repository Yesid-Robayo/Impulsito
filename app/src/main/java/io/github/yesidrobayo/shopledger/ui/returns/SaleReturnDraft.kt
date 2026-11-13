package io.github.yesidrobayo.shopledger.ui.returns

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution

/**
 * La devolución a medio elegir: lo que la persona tocó y escribió, tal cual. Nada se escribe en la
 * base hasta confirmar; vive en el estado guardado de la pantalla, que sobrevive a una llamada, a
 * cambiar de app y a que Android cierre el proceso.
 *
 * @param quantities lo que se devuelve de cada línea vendida, en milésimas, por id de línea. Una
 *   línea en cero no aparece.
 * @param lost las líneas que no vuelven al inventario. Por defecto todo vuelve.
 * @param resolution la que eligió la persona, o `null` para la inicial de la venta: saldo a favor
 *   si se cobró a crédito, y devolver dinero si no.
 * @param accountId la cuenta de la que sale el dinero, si la persona eligió; [accountChosen] dice
 *   si eligió, porque `null` también es una elección: `Sin cuenta`.
 * @param customerId el cliente elegido para el saldo a favor de una venta sin cliente.
 * @param showMissing se intentó registrar: ya se marca lo que falta.
 */
data class SaleReturnDraft(
    val quantities: Map<Long, Long> = emptyMap(),
    val lost: Set<Long> = emptySet(),
    val resolution: ReturnResolution? = null,
    val accountId: Long? = null,
    val accountChosen: Boolean = false,
    val customerId: Long? = null,
    val note: String = "",
    val showMissing: Boolean = false,
)

/**
 * Guarda la devolución en edición en el estado guardado, con tipos que caben en un `Bundle`. Si las
 * partes no cuadran entre sí, las cantidades se descartan.
 */
internal object SaleReturnDraftStore {
    private const val LINES = "saleReturn.lines"
    private const val QUANTITIES = "saleReturn.quantities"
    private const val LOST = "saleReturn.lost"
    private const val RESOLUTION = "saleReturn.resolution"
    private const val ACCOUNT = "saleReturn.account"
    private const val ACCOUNT_CHOSEN = "saleReturn.accountChosen"
    private const val CUSTOMER = "saleReturn.customer"
    private const val NOTE = "saleReturn.note"
    private const val SHOW_MISSING = "saleReturn.showMissing"

    fun read(state: SavedStateHandle): SaleReturnDraft {
        val lines = state.get<LongArray>(LINES)
        val quantities = state.get<LongArray>(QUANTITIES)
        return SaleReturnDraft(
            quantities = if (lines != null && quantities != null && lines.size == quantities.size) {
                lines.indices.associate { lines[it] to quantities[it] }
            } else {
                emptyMap()
            },
            lost = state.get<LongArray>(LOST)?.toSet().orEmpty(),
            resolution = ReturnResolution.entries.firstOrNull {
                it.name == state.get<String>(RESOLUTION)
            },
            accountId = state.get<Long>(ACCOUNT),
            accountChosen = state.get<Boolean>(ACCOUNT_CHOSEN) ?: false,
            customerId = state.get<Long>(CUSTOMER),
            note = state.get<String>(NOTE).orEmpty(),
            showMissing = state.get<Boolean>(SHOW_MISSING) ?: false,
        )
    }

    fun write(state: SavedStateHandle, draft: SaleReturnDraft) {
        val entries = draft.quantities.entries.toList()
        state[LINES] = LongArray(entries.size) { entries[it].key }
        state[QUANTITIES] = LongArray(entries.size) { entries[it].value }
        state[LOST] = draft.lost.toLongArray()
        state[RESOLUTION] = draft.resolution?.name
        state[ACCOUNT] = draft.accountId
        state[ACCOUNT_CHOSEN] = draft.accountChosen
        state[CUSTOMER] = draft.customerId
        state[NOTE] = draft.note
        state[SHOW_MISSING] = draft.showMissing
    }
}
