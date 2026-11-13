package io.github.yesidrobayo.shopledger.ui.supplierreturn

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution

/**
 * La devolución a medio armar: lo que la persona eligió y escribió, tal cual. Nada se escribe en la
 * base hasta confirmar; vive en el estado guardado de la pantalla, que sobrevive a una llamada, a
 * cambiar de app y a que Android cierre el proceso.
 *
 * @param purchaseQuantities con compra de origen, lo escrito para cada línea de compra, por su id.
 * @param loose sin compra, las líneas que se agregaron, en orden.
 * @param showMissing se intentó registrar: ya se marcan los campos que faltan.
 */
data class SupplierReturnDraft(
    val supplierId: Long? = null,
    val purchaseId: Long? = null,
    val resolution: PurchaseReturnResolution = PurchaseReturnResolution.CASH_REFUND,
    val accountId: Long? = null,
    val note: String = "",
    val purchaseQuantities: Map<Long, String> = emptyMap(),
    val loose: List<LooseLineDraft> = emptyList(),
    val nextKey: Long = 1L,
    val showMissing: Boolean = false,
)

/** Una línea sin compra, tal como se escribió. */
data class LooseLineDraft(val key: Long, val productId: Long, val quantityText: String)

/**
 * Guarda la devolución en edición en el estado guardado, con tipos que caben en un `Bundle`:
 * números en arreglos y lo escrito aparte. Si las partes no cuadran entre sí, se descartan.
 */
internal object SupplierReturnDraftStore {
    private const val SUPPLIER = "supplierReturn.supplier"
    private const val PURCHASE = "supplierReturn.purchase"
    private const val RESOLUTION = "supplierReturn.resolution"
    private const val ACCOUNT = "supplierReturn.account"
    private const val NOTE = "supplierReturn.note"
    private const val PURCHASE_LINES = "supplierReturn.purchaseLines"
    private const val PURCHASE_TEXTS = "supplierReturn.purchaseTexts"
    private const val LOOSE_LINES = "supplierReturn.looseLines"
    private const val LOOSE_TEXTS = "supplierReturn.looseTexts"
    private const val NEXT_KEY = "supplierReturn.nextKey"
    private const val SHOW_MISSING = "supplierReturn.showMissing"

    /** Clave y producto de cada línea suelta. */
    private const val LOOSE_FIELDS = 2

    fun read(state: SavedStateHandle, initial: SupplierReturnDraft): SupplierReturnDraft {
        if (!state.contains(RESOLUTION)) return initial
        return SupplierReturnDraft(
            supplierId = state.get<Long>(SUPPLIER),
            purchaseId = state.get<Long>(PURCHASE),
            resolution = PurchaseReturnResolution.entries
                .firstOrNull { it.name == state.get<String>(RESOLUTION) }
                ?: PurchaseReturnResolution.CASH_REFUND,
            accountId = state.get<Long>(ACCOUNT),
            note = state.get<String>(NOTE).orEmpty(),
            purchaseQuantities = purchaseQuantities(
                state.get<LongArray>(PURCHASE_LINES),
                state.get<ArrayList<String>>(PURCHASE_TEXTS),
            ),
            loose = loose(
                state.get<LongArray>(LOOSE_LINES),
                state.get<ArrayList<String>>(LOOSE_TEXTS),
            ),
            nextKey = state.get<Long>(NEXT_KEY) ?: 1L,
            showMissing = state.get<Boolean>(SHOW_MISSING) ?: false,
        )
    }

    fun write(state: SavedStateHandle, draft: SupplierReturnDraft) {
        state[SUPPLIER] = draft.supplierId
        state[PURCHASE] = draft.purchaseId
        state[RESOLUTION] = draft.resolution.name
        state[ACCOUNT] = draft.accountId
        state[NOTE] = draft.note
        val entries = draft.purchaseQuantities.entries.toList()
        state[PURCHASE_LINES] = LongArray(entries.size) { entries[it].key }
        state[PURCHASE_TEXTS] = ArrayList(entries.map { it.value })
        val numbers = LongArray(draft.loose.size * LOOSE_FIELDS)
        draft.loose.forEachIndexed { index, line ->
            numbers[index * LOOSE_FIELDS] = line.key
            numbers[index * LOOSE_FIELDS + 1] = line.productId
        }
        state[LOOSE_LINES] = numbers
        state[LOOSE_TEXTS] = ArrayList(draft.loose.map { it.quantityText })
        state[NEXT_KEY] = draft.nextKey
        state[SHOW_MISSING] = draft.showMissing
    }

    private fun purchaseQuantities(ids: LongArray?, texts: List<String>?): Map<Long, String> {
        if (ids == null || texts == null || ids.size != texts.size) return emptyMap()
        return ids.indices.associate { ids[it] to texts[it] }
    }

    private fun loose(numbers: LongArray?, texts: List<String>?): List<LooseLineDraft> {
        if (numbers == null || texts == null) return emptyList()
        val count = numbers.size / LOOSE_FIELDS
        if (numbers.size % LOOSE_FIELDS != 0 || texts.size != count) return emptyList()
        return (0 until count).map { index ->
            LooseLineDraft(
                key = numbers[index * LOOSE_FIELDS],
                productId = numbers[index * LOOSE_FIELDS + 1],
                quantityText = texts[index],
            )
        }
    }
}
