package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.uuid.Uuid

/** Un precio que vuelve a su valor anterior al deshacer: de [from], el de ahora, a [to]. */
data class BulkUndoRevert(val productId: Long, val from: Money, val to: Money)

/**
 * Lo que haría deshacer el lote [batchId]: los precios que vuelven ([reverts]) y cuántos se dejan
 * como están ([kept]) porque se cambiaron después, se archivaron o ya no existen.
 */
data class BulkUndoPlan(val batchId: Uuid, val reverts: List<BulkUndoRevert>, val kept: Int) {
    /** Si hay algo que deshacer: con todos cambiados después, la opción no se ofrece. */
    val canUndo: Boolean get() = reverts.isNotEmpty()
}
