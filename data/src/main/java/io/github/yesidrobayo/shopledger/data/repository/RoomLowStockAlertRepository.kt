package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.stock.LowStockAlertEntity
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las alertas de stock bajo sobre `low_stock_alerts`, que también son la bandeja de salida de sus
 * avisos. Que haya a lo sumo una activa por producto lo impone la guarda de la tabla.
 */
internal class RoomLowStockAlertRepository(database: AppDatabase) : LowStockAlertRepository {
    private val alerts = database.lowStockAlertDao()
    private val writes = RepositoryWrites(database)

    override fun observeActive(): Flow<List<LowStockAlert>> =
        alerts.observeActive().map { rows -> rows.map(::toAlert) }.distinctUntilChanged()

    override suspend fun findActiveByProducts(productIds: Collection<Long>): List<LowStockAlert> =
        if (productIds.isEmpty()) {
            emptyList()
        } else {
            alerts.findActiveByProducts(productIds.distinct()).map(::toAlert)
        }

    override suspend fun findPendingNotifications(): List<LowStockAlert> =
        alerts.findPendingNotifications().map(::toAlert)

    override suspend fun insert(alert: NewLowStockAlert): Long = writes.inTransaction {
        alerts.insert(
            LowStockAlertEntity(
                productId = alert.productId,
                onHandMilliAtTrigger = alert.onHandAtTrigger.milli,
                minStockMilliAtTrigger = alert.minStockAtTrigger.milli,
                notificationStatus = alert.notificationStatus.name,
                occurredAt = alert.occurredAt.toStoredMillis(),
                businessDate = alert.businessDate.toStored(),
                resolvedAt = null,
            ),
        )
    }

    /** Una alerta ya resuelta no se vuelve a resolver: su instante no se reescribe. */
    override suspend fun resolve(id: Long, resolvedAt: Instant) {
        writes.inTransaction { requireOneRow(alerts.resolve(id, resolvedAt.toStoredMillis())) }
    }

    /**
     * Si la alerta ya no existe, no hay nada que guardar y no falla: se borra en cascada con un
     * producto eliminado, y eso puede pasar entre lanzar el aviso y guardar qué pasó con él.
     */
    override suspend fun setNotificationStatus(id: Long, status: LowStockNotificationStatus) {
        writes.standalone { alerts.setNotificationStatus(id, status.name) }
    }

    private fun toAlert(row: LowStockAlertEntity): LowStockAlert = LowStockAlert(
        id = row.id,
        productId = row.productId,
        onHandAtTrigger = QuantityMilli(row.onHandMilliAtTrigger),
        minStockAtTrigger = QuantityMilli(row.minStockMilliAtTrigger),
        notificationStatus = storedEnum<LowStockNotificationStatus>(row.notificationStatus),
        occurredAt = row.occurredAt.toInstant(),
        businessDate = storedDate(row.businessDate),
        resolvedAt = row.resolvedAt?.toInstant(),
    )
}
