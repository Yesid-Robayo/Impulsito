package io.github.yesidrobayo.shopledger.ui.lot

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.ui.components.LotExpiry
import kotlinx.datetime.DayOfWeek

/** Lo que tiene un lote en una ubicación, con la ubicación para nombrarla. */
@Immutable
data class LotPlace(val location: Location, val onHand: QuantityMilli)

/**
 * Un lote como se ve en la lista y en su hoja: su producto, su código, cuándo vence, lo que tiene
 * en total y en cada ubicación. [code] es `null` en el lote sin asignar, que se nombra como tal.
 */
@Immutable
data class LotItem(
    val lotId: Long,
    val productId: Long,
    val productName: String,
    val code: String?,
    val unassigned: Boolean,
    val expiryDate: BusinessDate?,
    val expiry: LotExpiry,
    val expired: Boolean,
    val onHand: QuantityMilli,
    val unit: MeasureUnit?,
    val places: List<LotPlace>,
)

/** Los lotes de un producto, ya en el orden de salida. */
@Immutable
data class LotGroupItem(val productId: Long, val productName: String, val lots: List<LotItem>)

/**
 * Lo listo para mostrar. [hasLots] dice si existe algún lote con código: sin ninguno, la pantalla
 * explica cómo empezar. [expiringValue] es `null` si no se pudo calcular. [showsLocations] es
 * verdadero con más de una ubicación vigente. [today] y [weekStartDay] sirven a la hoja de la
 * fecha, y faltan si el teléfono no conoce la zona del negocio. [lots] son todos los lotes leídos,
 * sin filtrar: la hoja de un lote lo encuentra aunque el filtro no lo muestre.
 */
@Immutable
data class LotsReady(
    val hasLots: Boolean,
    val expiringCount: Int,
    val expiringValue: Money?,
    val groups: List<LotGroupItem>,
    val noticeDays: Int,
    val showsLocations: Boolean,
    val today: BusinessDate?,
    val weekStartDay: DayOfWeek?,
    val lots: List<LotItem> = emptyList(),
)

/** Lo que muestra la pantalla debajo de su barra. */
sealed interface LotsContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : LotsContent

    /** La lectura falló: se dice, con `Reintentar`. */
    data object ReadError : LotsContent

    data class Ready(val ready: LotsReady) : LotsContent
}

/** Un movimiento del lote en su hoja: qué fue, cuándo y cuánto, con signo. */
@Immutable
data class LotMovementItem(
    val id: Long,
    val type: StockMovementType,
    val date: BusinessDate,
    val quantity: QuantityMilli,
    val reason: StockAdjustmentReason? = null,
)

/** Por qué no se guardó un código. */
enum class LotCodeError { BLANK, TOO_LONG, REPEATED }

/** La hoja abierta sobre la lista. Una a la vez. */
sealed interface LotSheet {
    /**
     * El detalle de un lote, con sus últimos movimientos y su compra de origen, la fecha de la
     * primera compra que lo trajo, si entró así.
     */
    data class Detail(
        val lotId: Long,
        val movements: List<LotMovementItem> = emptyList(),
        val originDate: BusinessDate? = null,
    ) : LotSheet

    /** Corregir el vencimiento: [date] es la fecha elegida, `null` sin vencimiento. */
    data class FixExpiry(
        val lotId: Long,
        val date: BusinessDate?,
        val saving: Boolean = false,
        val failed: Boolean = false,
    ) : LotSheet

    /** Corregir el código: [text] es lo escrito, que un error nunca borra. */
    data class FixCode(
        val lotId: Long,
        val text: String,
        val error: LotCodeError? = null,
        val repeatedIn: String? = null,
        val saving: Boolean = false,
        val failed: Boolean = false,
    ) : LotSheet

    /** Los días de aviso de los ajustes: [text] es lo escrito. */
    data class Notice(
        val text: String,
        val invalid: Boolean = false,
        val saving: Boolean = false,
        val failed: Boolean = false,
    ) : LotSheet
}

/** Un mensaje breve tras guardar. */
enum class LotsMessage { LOT_SAVED, NOTICE_SAVED }

/** El estado de la pantalla de lotes y vencimientos. */
@Immutable
data class LotsUiState(
    val filter: LotFilter = LotFilter.EXPIRING,
    val content: LotsContent = LotsContent.Loading,
    val sheet: LotSheet? = null,
    val message: LotsMessage? = null,
) {
    /** El lote de la hoja abierta, si sigue en la lista leída. */
    fun lot(lotId: Long): LotItem? =
        (content as? LotsContent.Ready)?.ready?.lots?.firstOrNull { it.lotId == lotId }
}

/** Lo que la persona puede hacer en la pantalla de lotes. */
sealed interface LotsAction {
    data object Back : LotsAction

    data object Retry : LotsAction

    data class SelectFilter(val filter: LotFilter) : LotsAction

    data class OpenLot(val lotId: Long) : LotsAction

    data object CloseSheet : LotsAction

    /** `Dar salida`: abre el ajuste con el motivo `Vencido` y este lote. Lo resuelve la ruta. */
    data class TakeOut(val productId: Long, val lotId: Long) : LotsAction

    data class StartFixExpiry(val lotId: Long) : LotsAction

    data class ExpiryChange(val date: BusinessDate?) : LotsAction

    data class StartFixCode(val lotId: Long) : LotsAction

    data class CodeChange(val text: String) : LotsAction

    data object OpenNotice : LotsAction

    data class NoticeChange(val text: String) : LotsAction

    /** Guarda la hoja abierta: vencimiento, código o días de aviso. */
    data object SaveSheet : LotsAction

    data object MessageShown : LotsAction

    /** Sin lotes: ir a los productos para activarlos. Lo resuelve la ruta. */
    data object OpenProducts : LotsAction

    /** Desde el lote sin asignar. Los resuelve la ruta. */
    data class RegisterPurchase(val productId: Long) : LotsAction

    data class AdjustStock(val productId: Long) : LotsAction
}
