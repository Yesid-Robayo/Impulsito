package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryStatus

/**
 * Los chips del inventario que existen hoy. `Revisar` y `Por vencer` aparecen cuando sus módulos
 * estén en uso; mientras tanto, abrir con ellos muestra lo que se está acabando.
 */
enum class InventoryChip {
    /** Por agotarse, en el orden de lo urgente. El de siempre. */
    LOW_STOCK,

    /** Solo el stock negativo: así llega desde el aviso de Inicio. */
    NEGATIVE_STOCK,

    /** Todo lo que lleva stock, por nombre. */
    ALL,
}

/** Una fila del inventario, ya leída. La unidad va como la guardó la base, sin texto. */
@Immutable
data class InventoryItem(
    val id: Long,
    val name: String,
    val status: InventoryStatus,
    val onHand: QuantityMilli,
    val minStock: QuantityMilli,
    val unit: MeasureUnit?,
)

/** Lo que muestra el inventario debajo de sus pestañas. */
sealed interface InventoryContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : InventoryContent

    /** No hay ningún producto: el estado vacío invita a crear el primero. */
    data object EmptyCatalog : InventoryContent

    /**
     * El inventario: cuántos se están acabando y cuántos están en negativo, el valor de lo que hay
     * y la parte de los archivados, si hay stock sin costo, y las filas del chip elegido.
     */
    data class Ready(
        val lowStockCount: Int,
        val negativeCount: Int,
        val value: Money?,
        val archivedValue: Money?,
        val hasStockWithoutCost: Boolean,
        val items: List<InventoryItem>,
    ) : InventoryContent

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : InventoryContent
}

@Immutable
data class InventoryUiState(
    val chip: InventoryChip = InventoryChip.LOW_STOCK,
    val content: InventoryContent = InventoryContent.Loading,
)

/** Lo que la persona puede hacer en el inventario. La navegación la resuelve la ruta. */
sealed interface InventoryAction {
    data class ChipChanged(val chip: InventoryChip) : InventoryAction

    /** Tocó la fila: se abre el detalle del producto. */
    data class OpenProduct(val productId: Long) : InventoryAction

    /** Tocó `Reponer`: se abre una compra con el producto ya agregado. */
    data class Restock(val productId: Long) : InventoryAction

    /** Eligió `Ajustar stock` en el menú de la fila. */
    data class Adjust(val productId: Long) : InventoryAction

    /** Desde el inventario vacío, quiere crear el primer producto. */
    data object CreateProduct : InventoryAction

    data object Retry : InventoryAction
}
