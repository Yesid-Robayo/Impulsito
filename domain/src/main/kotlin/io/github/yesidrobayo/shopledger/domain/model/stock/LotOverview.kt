package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product

/** Lo que tiene el lote [lotId] en la ubicación [locationId]. Nunca sale una parte en cero. */
data class LotHolding(val lotId: Long, val locationId: Long, val onHand: QuantityMilli)

/**
 * Cuándo vence un lote, visto desde hoy. [daysLeft] son los días que faltan: cero vence hoy, y
 * negativo ya venció. Es `null` si no se sabe qué día es hoy, porque el teléfono no conoce la zona
 * del negocio: entonces el lote no se marca. [soon] dice si ya está en sus días de aviso.
 */
data class LotDue(val date: BusinessDate, val daysLeft: Int?, val soon: Boolean) {
    val isExpired: Boolean get() = daysLeft != null && daysLeft < 0
}

/**
 * Un lote con todo lo que muestra la pantalla de lotes: su producto, lo que tiene sumado en todas
 * las ubicaciones, lo que tiene en cada una y cuándo vence. [due] es `null` sin vencimiento.
 */
data class LotEntry(
    val lot: StockLot,
    val product: Product,
    val onHand: QuantityMilli,
    val byLocation: List<LotHolding>,
    val due: LotDue?,
) {
    /** Un lote con stock: el que sale en las listas por defecto. Agotado, solo en `Todos`. */
    val hasStock: Boolean get() = onHand > QuantityMilli.ZERO
}

/**
 * Lo que muestra la lista de lotes: los que vencen pronto, los vencidos, todos o el sin asignar.
 */
enum class LotFilter {
    /** Con stock, en sus días de aviso y sin vencer todavía. Es el filtro de entrada. */
    EXPIRING,

    /** Con stock y ya vencidos: hay que sacarlos. */
    EXPIRED,

    /** Todos los lotes con código, también los agotados y los que no vencen. */
    ALL,

    /** El lote sin asignar de cada producto que tiene uno con saldo. */
    UNASSIGNED,
}

/**
 * Los lotes de todos los productos que se controlan por lotes, sin archivar, y los días de aviso
 * de los ajustes. [entries] va en el orden en que entraron los lotes.
 */
data class LotsOverview(val entries: List<LotEntry>, val noticeDays: Int)

/**
 * Los lotes que se pueden elegir para sacar un producto de una ubicación, en el orden de salida:
 * primero el que vence antes. [unassigned] es lo que tiene el lote sin asignar en esa ubicación, o
 * `null` si el producto todavía no tiene uno.
 */
data class LotChoices(val lots: List<LotChoice>, val unassigned: LotStock?) {
    /** El lote que saldría sin que la persona elija: el primero del orden de salida. */
    val proposed: LotChoice? get() = lots.firstOrNull()
}

/** Un lote que se puede elegir, con lo que tiene en la ubicación y cuándo vence. */
data class LotChoice(val stock: LotStock, val due: LotDue?)
