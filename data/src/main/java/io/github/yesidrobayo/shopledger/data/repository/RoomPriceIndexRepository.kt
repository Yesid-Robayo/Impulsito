package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.report.PriceIndexEntryEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.port.PriceIndexRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

/** El índice de precios sobre `price_index_entries`, una fila por mes. */
internal class RoomPriceIndexRepository(database: AppDatabase) : PriceIndexRepository {
    private val entries = database.priceIndexEntryDao()
    private val writes = RepositoryWrites(database)

    override suspend fun months(): Set<String> =
        entries.observeAll().first().mapTo(mutableSetOf()) { it.month }

    override suspend fun insert(month: String, change: BasisPoints, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            entries.insert(
                PriceIndexEntryEntity(
                    month = month,
                    changeBp = change.bp,
                    sourceNote = null,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(month: String, change: BasisPoints, updatedAt: Instant) {
        writes.inTransaction {
            val current = checkNotNull(entries.findByMonth(month)) { "El mes no tiene variación" }
            requireOneRow(
                entries.update(month, change.bp, current.sourceNote, updatedAt.toStoredMillis()),
            )
        }
    }
}
