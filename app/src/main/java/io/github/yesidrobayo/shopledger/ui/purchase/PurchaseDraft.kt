package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate

/**
 * La compra a medio escribir: lo que la persona eligió y escribió, tal cual. No se escribe en la
 * base hasta confirmar; vive en el estado guardado de la pantalla, que sobrevive a una llamada, a
 * cambiar de app y a que Android cierre el proceso.
 *
 * @param supplierId el proveedor elegido, o `null` sin proveedor.
 * @param supplierTouched si la persona eligió proveedor, aunque sea ninguno: entonces agregar un
 *   producto ya no preselecciona su proveedor habitual.
 * @param date la fecha elegida, o `null` para hoy.
 * @param awaitingProductAfter con un producto creado desde la búsqueda, el mayor id que había al
 *   salir a crearlo: el primero que aparezca con un id mayor se agrega al volver.
 * @param prefilled si ya se agregó el producto con que se abrió la compra.
 * @param nextKey la identidad que recibe la próxima línea.
 * @param onCredit si se compra a plazo: lo que no se paga ahora queda en el libro del proveedor.
 * @param dueDate el vencimiento que eligió la persona, o `null` para proponerlo con el plazo habitual
 *   del proveedor.
 * @param paidNowText lo que se paga al registrar una compra a plazo, tal como se escribió; vacío es
 *   nada.
 */
data class PurchaseDraft(
    val lines: List<PurchaseLineDraft> = emptyList(),
    val supplierId: Long? = null,
    val supplierTouched: Boolean = false,
    val date: BusinessDate? = null,
    val reference: String = "",
    val awaitingProductAfter: Long? = null,
    val prefilled: Boolean = false,
    val nextKey: Long = 1L,
    val onCredit: Boolean = false,
    val dueDate: BusinessDate? = null,
    val paidNowText: String = "",
)

/**
 * Una línea tal como se escribió: los textos se leen al calcular, con el formato regional.
 * [unitId] es la unidad en que se compra, o `null` para la base.
 */
data class PurchaseLineDraft(
    val key: Long,
    val productId: Long,
    val quantityText: String,
    val amountText: String,
    val byTotal: Boolean,
    val unitId: Long? = null,
)

/**
 * Guarda la compra en edición en el estado guardado, con tipos que caben en un `Bundle`: tres
 * números por línea y sus dos textos aparte. Solo números y lo que la persona escribió.
 */
internal object PurchaseDraftStore {
    private const val LINES = "purchase.lines"
    private const val QUANTITIES = "purchase.quantities"
    private const val AMOUNTS = "purchase.amounts"
    private const val SUPPLIER = "purchase.supplier"
    private const val SUPPLIER_TOUCHED = "purchase.supplierTouched"
    private const val DATE = "purchase.date"
    private const val REFERENCE = "purchase.reference"
    private const val AWAITING = "purchase.awaiting"
    private const val PREFILLED = "purchase.prefilled"
    private const val NEXT_KEY = "purchase.nextKey"
    private const val ON_CREDIT = "purchase.onCredit"
    private const val DUE_DATE = "purchase.dueDate"
    private const val PAID_NOW = "purchase.paidNow"

    private const val FIELDS = 4

    /** La unidad base en el arreglo: ningún id real es cero. */
    private const val BASE_UNIT = 0L

    fun read(state: SavedStateHandle): PurchaseDraft = PurchaseDraft(
        lines = decode(
            state.get<LongArray>(LINES),
            state.get<ArrayList<String>>(QUANTITIES),
            state.get<ArrayList<String>>(AMOUNTS),
        ),
        supplierId = state.get<Long>(SUPPLIER),
        supplierTouched = state.get<Boolean>(SUPPLIER_TOUCHED) ?: false,
        date = state.get<String>(DATE)?.let(BusinessDate::parse),
        reference = state.get<String>(REFERENCE).orEmpty(),
        awaitingProductAfter = state.get<Long>(AWAITING),
        prefilled = state.get<Boolean>(PREFILLED) ?: false,
        nextKey = state.get<Long>(NEXT_KEY) ?: 1L,
        onCredit = state.get<Boolean>(ON_CREDIT) ?: false,
        dueDate = state.get<String>(DUE_DATE)?.let(BusinessDate::parse),
        paidNowText = state.get<String>(PAID_NOW).orEmpty(),
    )

    fun write(state: SavedStateHandle, draft: PurchaseDraft) {
        val numbers = LongArray(draft.lines.size * FIELDS)
        draft.lines.forEachIndexed { index, line ->
            val base = index * FIELDS
            numbers[base] = line.key
            numbers[base + 1] = line.productId
            numbers[base + 2] = if (line.byTotal) 1L else 0L
            numbers[base + 3] = line.unitId ?: BASE_UNIT
        }
        state[LINES] = numbers
        state[QUANTITIES] = ArrayList(draft.lines.map { it.quantityText })
        state[AMOUNTS] = ArrayList(draft.lines.map { it.amountText })
        state[SUPPLIER] = draft.supplierId
        state[SUPPLIER_TOUCHED] = draft.supplierTouched
        state[DATE] = draft.date?.toString()
        state[REFERENCE] = draft.reference
        state[AWAITING] = draft.awaitingProductAfter
        state[PREFILLED] = draft.prefilled
        state[NEXT_KEY] = draft.nextKey
        state[ON_CREDIT] = draft.onCredit
        state[DUE_DATE] = draft.dueDate?.toString()
        state[PAID_NOW] = draft.paidNowText
    }

    /** Lo guardado; si las tres partes no cuadran entre sí, se descarta entero. */
    private fun decode(
        numbers: LongArray?,
        quantities: List<String>?,
        amounts: List<String>?,
    ): List<PurchaseLineDraft> {
        if (numbers == null || quantities == null || amounts == null) return emptyList()
        val count = numbers.size / FIELDS
        val fits = numbers.size % FIELDS == 0 && quantities.size == count && amounts.size == count
        if (!fits) return emptyList()
        return (0 until count).map { index ->
            val base = index * FIELDS
            PurchaseLineDraft(
                key = numbers[base],
                productId = numbers[base + 1],
                quantityText = quantities[index],
                amountText = amounts[index],
                byTotal = numbers[base + 2] == 1L,
                unitId = numbers[base + 3].takeIf { it != BASE_UNIT },
            )
        }
    }
}
