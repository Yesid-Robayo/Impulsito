package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReviewReason
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryStatus
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing

/**
 * Los chips del inventario que existen hoy. `Revisar` aparece si hay algo que revisar, y `Por
 * vencer` si hay lotes en sus días de aviso; abrir con uno de ellos vacío muestra lo que se está
 * acabando.
 */
enum class InventoryChip {
    /** Por agotarse, en el orden de lo urgente. El de siempre. */
    LOW_STOCK,

    /** Solo el stock negativo: así llega desde el aviso de Inicio. */
    NEGATIVE_STOCK,

    /** Todo lo que lleva stock, por nombre. */
    ALL,

    /** Lo que pide decidir un precio: costo nuevo sin revisar, margen bajo y costo viejo. */
    REVIEW,

    /** Los lotes en sus días de aviso o ya vencidos, del que vence antes al que vence después. */
    EXPIRING,
}

/**
 * Una fila de `Por vencer`: el lote de un producto, con lo que tiene en todas las ubicaciones.
 * [daysLeft] cuenta desde hoy: cero vence hoy y negativo ya venció. [code] es `null` sin código.
 */
@Immutable
data class ExpiringLotItem(
    val lotId: Long,
    val productId: Long,
    val productName: String,
    val code: String?,
    val expiryDate: BusinessDate,
    val daysLeft: Int,
    val onHand: QuantityMilli,
    val unit: MeasureUnit?,
)

/**
 * Una fila de `Revisar`: el producto con su precio y su margen sin impuesto, como en su detalle, y
 * todos sus motivos en [reasons]. [costSince] es la fecha del costo viejo, si ese es un motivo.
 * Solo el costo nuevo se descarta: los otros dos salen solos al cambiar el precio o el costo.
 */
@Immutable
data class PriceReviewItem(
    val id: Long,
    val name: String,
    val trailing: ProductRowTrailing.Catalog,
    val reasons: List<PriceReviewReason> = listOf(PriceReviewReason.NEW_COST),
    val costSince: BusinessDate? = null,
) {
    /** Si `Descartar` tiene sentido: solo saca el producto de la cola de costo nuevo. */
    val dismissable: Boolean get() = PriceReviewReason.NEW_COST in reasons
}

/**
 * Una fila del inventario, ya leída. La unidad va como la guardó la base, sin texto. [usage] marca
 * los insumos con su etiqueta, para distinguirlos sin abrir el producto.
 */
@Immutable
data class InventoryItem(
    val id: Long,
    val name: String,
    val status: InventoryStatus,
    val onHand: QuantityMilli,
    val minStock: QuantityMilli,
    val unit: MeasureUnit?,
    val usage: ProductUsage = ProductUsage.SALE,
)

/**
 * La fila de [product], con su unidad de [units]. La usan el inventario y la tarjeta de Inicio,
 * para que las dos digan lo mismo.
 */
internal fun inventoryItemOf(product: Product, units: Map<Long, MeasureUnit>) = InventoryItem(
    id = product.id,
    name = product.details.name,
    // Solo llegan productos con libro, que siempre tienen estado.
    status = InventoryRules.statusOf(product) ?: InventoryStatus.NORMAL,
    onHand = product.onHand,
    minStock = product.details.minStock,
    unit = units[product.details.baseUnitId],
    usage = product.details.usage,
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
     *
     * Las filas ya pasaron por la búsqueda [query]; [showsReview] y [showsExpiring] dicen si sus
     * chips existen, que no dependen de lo que se busca: buscar no esconde un chip.
     */
    data class Ready(
        val lowStockCount: Int,
        val negativeCount: Int,
        val value: Money?,
        val archivedValue: Money?,
        val hasStockWithoutCost: Boolean,
        val items: List<InventoryItem>,
        val reviewItems: List<PriceReviewItem> = emptyList(),
        val expiringItems: List<ExpiringLotItem> = emptyList(),
        val query: String = "",
        val showsReview: Boolean = reviewItems.isNotEmpty(),
        val showsExpiring: Boolean = expiringItems.isNotEmpty(),
    ) : InventoryContent

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : InventoryContent
}

/**
 * El inventario en pantalla. [locations] son las ubicaciones vigentes, solo si hay más de una, y
 * [locationId] la que se mira, o `null` para todas. [query] es lo escrito en la búsqueda, tal
 * cual: busca por nombre o por código completo, dentro del chip y de la ubicación elegidos.
 */
@Immutable
data class InventoryUiState(
    val chip: InventoryChip = InventoryChip.LOW_STOCK,
    val content: InventoryContent = InventoryContent.Loading,
    val message: InventoryMessage? = null,
    val locations: List<Location> = emptyList(),
    val locationId: Long? = null,
    val query: String = "",
)

/** Un aviso breve del inventario. */
sealed interface InventoryMessage {
    /** No se pudo sacar un producto de `Revisar`; sigue en la cola. */
    data class DismissFailed(val outOfSpace: Boolean) : InventoryMessage
}

/** Lo que la persona puede hacer en el inventario. La navegación la resuelve la ruta. */
sealed interface InventoryAction {
    data class ChipChanged(val chip: InventoryChip) : InventoryAction

    /** Escribió en la búsqueda, o el escáner la llenó con un código. */
    data class QueryChange(val query: String) : InventoryAction

    /** Eligió una ubicación en el selector, o `null` para ver todas. */
    data class LocationChanged(val locationId: Long?) : InventoryAction

    /** Tocó la fila: se abre el detalle del producto. */
    data class OpenProduct(val productId: Long) : InventoryAction

    /** Tocó `Reponer`: se abre una compra con el producto ya agregado. */
    data class Restock(val productId: Long) : InventoryAction

    /** Eligió `Ajustar stock` en el menú de la fila. */
    data class Adjust(val productId: Long) : InventoryAction

    /** En `Revisar`, tocó `Cambiar precio`: se abre el detalle con la hoja del precio. */
    data class ChangePrice(val productId: Long) : InventoryAction

    /** En `Revisar`, eligió `Descartar`: sale de la cola sin cambiar el precio. */
    data class DismissReview(val productId: Long) : InventoryAction

    /** Tocó `Revisar todo`: se abre la actualización masiva con los de `Revisar`. */
    data object ReviewAll : InventoryAction

    data object MessageShown : InventoryAction

    /** Desde el inventario vacío, quiere crear el primer producto. */
    data object CreateProduct : InventoryAction

    /** Con los avisos apagados, quiere abrir los ajustes de avisos del teléfono. */
    data object OpenNotificationSettings : InventoryAction

    data object Retry : InventoryAction

    /** Abre la pantalla de ubicaciones desde el selector de ubicación. */
    data object ManageLocations : InventoryAction

    /** Abre la reposición sugerida desde el chip `Reponer`. */
    data object OpenReorder : InventoryAction
}
