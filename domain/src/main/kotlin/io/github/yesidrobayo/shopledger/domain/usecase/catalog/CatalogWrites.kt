package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockTransition
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceEntry
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import kotlin.time.Instant

/**
 * Corre [block] y devuelve como resultado propio una falla de la base, que ya revirtió todo. Así la
 * pantalla dice qué puede hacer la persona, en vez de recibir una excepción.
 */
internal suspend inline fun <T> storageSafe(onFailure: (StorageFailure) -> T, block: () -> T): T =
    try {
        block()
    } catch (failure: StorageFailureException) {
        onFailure(failure.failure)
    }

/**
 * El id de una fila inicial. Falta solo en una base dañada, porque la base nunca las borra: es un
 * error de programación o de datos, y deshace la transacción.
 */
internal suspend fun SystemRowRepository.requireId(row: SystemRow): Long =
    checkNotNull(findId(row)) { "Falta una fila inicial de la base" }

/**
 * El instante [now] con su fecha de negocio, o `null` si la zona guardada no se reconoce. Se lee
 * dentro de la transacción, justo antes de escribir, para usar la zona vigente.
 */
internal suspend fun BusinessCalendarSource.stamp(now: Instant): RecordTime? =
    when (val result = current()) {
        is BusinessCalendarResult.Ready -> result.calendar.stamp(now)
        is BusinessCalendarResult.UnknownZone -> null
    }

/**
 * Confirma que la moneda del negocio permite guardar un monto y, si es el primero, la bloquea en
 * [at], dentro de la misma transacción. `false` si la moneda sigue sin confirmar.
 */
internal suspend fun CurrencyLock.lock(at: Instant): Boolean = when (lockForMoneyRecord(at)) {
    is CurrencyLockResult.Locked -> true
    CurrencyLockResult.Unconfirmed -> false
}

/**
 * Crea callada o resuelve la alerta de [productId] según [transition], la que da un cambio sin
 * movimiento de stock. Nunca avisa: la persona está viendo la pantalla. [activeAlertId] es la
 * alerta activa del producto, si tiene una, y [time] es el instante y la fecha de la operación.
 */
internal suspend fun LowStockAlertRepository.applyWithoutNotice(
    productId: Long,
    transition: LowStockTransition,
    activeAlertId: Long?,
    time: RecordTime,
) {
    when (transition) {
        is LowStockTransition.Crossed ->
            insert(
                NewLowStockAlert(
                    productId = productId,
                    onHandAtTrigger = transition.onHand,
                    minStockAtTrigger = transition.minStock,
                    notificationStatus = LowStockNotificationStatus.SUPPRESSED,
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                ),
            )

        LowStockTransition.Rearmed ->
            resolve(
                checkNotNull(activeAlertId) { "Resolver una alerta que no existe" },
                time.occurredAt,
            )

        LowStockTransition.Unchanged, LowStockTransition.NotWatched -> Unit
    }
}

/**
 * La fila del inventario inicial: entra [quantity] a [unitCost] por unidad entera en la ubicación
 * [locationId], y deja al producto como dice [entry]. No sale de ningún documento.
 */
internal fun openingMovement(
    productId: Long,
    locationId: Long,
    quantity: QuantityMilli,
    unitCost: Money,
    entry: OpeningBalanceEntry,
    time: RecordTime,
): NewStockMovement = NewStockMovement(
    movement = StockMovementDraft(
        key = StockKey(productId, locationId, lotId = null),
        type = StockMovementType.OPENING_BALANCE,
        quantity = quantity,
        reversesMovementId = null,
    ),
    reason = null,
    unitCost = unitCost,
    totalCost = entry.totalCost,
    balanceAfter = entry.after.onHand,
    averageCostAfter = entry.after.averageCost,
    stockValueAfter = entry.after.stockValue,
    source = StockMovementSource.NONE,
    note = null,
    occurredAt = time.occurredAt,
    businessDate = time.businessDate,
    createdAt = time.occurredAt,
)
