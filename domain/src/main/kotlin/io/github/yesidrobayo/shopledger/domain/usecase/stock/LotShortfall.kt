package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository

/** Lo que falta en el lote [lotId] del producto [productId] para sacar de él lo que se pide. */
internal data class LotShortfall(val productId: Long, val lotId: Long, val missing: QuantityMilli)

/**
 * Lo que falta en un lote con código para anular [rows], filas que metieron stock, o `null` si
 * alcanza. Anular saca lo que entró del mismo lote y la misma ubicación; si parte ya salió, el
 * lote quedaría negativo, y solo el lote sin asignar puede quedar así. Una fila ya compensada no
 * cuenta. Sin [lots] no se sabe cuál es el sin asignar, así que todo lote se revisa.
 */
internal suspend fun StockLedgerRepository.lotShortfallOf(
    rows: List<StockMovementRecord>,
    lots: StockLotRepository?,
): LotShortfall? {
    val pending = rows.filter { it.movement.key.lotId != null && !isCompensated(it.id) }
    for ((key, keyRows) in pending.groupBy { it.movement.key }) {
        val lotId = checkNotNull(key.lotId) { "Solo quedan filas con lote" }
        if (lots?.findById(lotId)?.isUnassigned == true) continue
        val needed = keyRows.fold(QuantityMilli.ZERO) { sum, row -> sum + row.movement.quantity }
        val available = onHandAt(key)
        if (available < needed) return LotShortfall(key.productId, lotId, needed - available)
    }
    return null
}
