package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.tax.TaxRateEntity
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las tasas de impuesto sobre `tax_rates`.
 *
 * Las ventas y compras no apuntan a una tasa: cada línea guarda su copia del porcentaje y del
 * nombre. Por eso nada de lo que se escribe aquí cambia un documento pasado. Al eliminar una tasa,
 * las llaves de `products` y de `app_settings` la quitan en la misma sentencia.
 */
internal class RoomTaxRateRepository(database: AppDatabase) : TaxRateRepository {
    private val rates = database.taxRateDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de la tabla, aunque lo leído sea igual: una lista
    // igual a la anterior no se repite.
    override fun observe(filter: ArchiveFilter): Flow<List<TaxRate>> {
        val rows =
            when (filter) {
                ArchiveFilter.ACTIVE -> rates.observeActive()
                ArchiveFilter.ARCHIVED -> rates.observeArchived()
            }
        return rows.map { list -> list.map(::toTaxRate) }.distinctUntilChanged()
    }

    override fun observeProductCounts(): Flow<Map<Long, Int>> = rates.observeProductCounts()
        .map { rows -> rows.associate { it.taxRateId to it.productCount } }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): TaxRate? = rates.findById(id)?.let(::toTaxRate)

    override suspend fun findActiveByNormalizedName(normalizedName: String): TaxRate? =
        rates.findActiveByNormalizedName(normalizedName)?.let(::toTaxRate)

    override suspend fun countProducts(id: Long): Int = rates.countProducts(id)

    override suspend fun insert(details: TaxRateDetails, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            rates.insert(
                TaxRateEntity(
                    name = details.name,
                    normalizedName = details.normalizedName,
                    rateBp = details.rate.bp,
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(id: Long, details: TaxRateDetails, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                rates.update(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    rateBp = details.rate.bp,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                rates.setArchivedAt(
                    id = id,
                    archivedAt = archivedAt?.toStoredMillis(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(rates.delete(id)) }
    }

    private fun toTaxRate(row: TaxRateEntity): TaxRate = TaxRate(
        id = row.id,
        details =
            TaxRateDetails(
                name = row.name,
                normalizedName = row.normalizedName,
                rate = BasisPoints(row.rateBp),
            ),
        archivedAt = row.archivedAt?.toInstant(),
        createdAt = row.createdAt.toInstant(),
        updatedAt = row.updatedAt.toInstant(),
    )
}
