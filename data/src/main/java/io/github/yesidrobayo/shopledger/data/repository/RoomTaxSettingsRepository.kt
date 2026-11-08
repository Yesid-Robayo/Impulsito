package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los ajustes de impuestos, sobre la única fila de `app_settings`.
 *
 * Se leen de [settings], que ya convierte la fila entera, y se escriben solo en sus tres columnas:
 * así un cambio de impuestos nunca pisa otro ajuste con un valor viejo.
 */
internal class RoomTaxSettingsRepository(
    database: AppDatabase,
    private val settings: AppSettingsRepository,
) : TaxSettingsRepository {
    private val rows = database.appSettingsDao()
    private val writes = RepositoryWrites(database)

    override fun observe(): Flow<TaxSettings> =
        settings.observe().map { it.taxes }.distinctUntilChanged()

    override suspend fun get(): TaxSettings = settings.get().taxes

    override suspend fun update(taxes: TaxSettings, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                rows.updateTaxSettings(
                    taxMode = taxes.mode.name,
                    defaultTaxRateId = taxes.defaultTaxRateId,
                    purchaseTaxRecoverable = taxes.purchaseTaxRecoverable.toStoredFlag(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }
}
