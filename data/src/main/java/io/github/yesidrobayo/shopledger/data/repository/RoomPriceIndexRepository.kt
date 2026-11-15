package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.report.PriceIndexEntryEntity
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.port.PriceIndexRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.YearMonth

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

    override fun observeEntries(): Flow<List<PriceIndexEntry>> =
        entries.observeAll()
            .map { rows ->
                rows.map { row ->
                    PriceIndexEntry(
                        month = YearMonth.parse(row.month),
                        change = BasisPoints(row.changeBp),
                        sourceNote = row.sourceNote,
                    )
                }
            }
            .distinctUntilChanged()

    override suspend fun insertEntry(entry: PriceIndexEntry, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            entries.insert(
                PriceIndexEntryEntity(
                    month = entry.month.toString(),
                    changeBp = entry.change.bp,
                    sourceNote = entry.sourceNote,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun updateEntry(entry: PriceIndexEntry, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                entries.update(
                    entry.month.toString(),
                    entry.change.bp,
                    entry.sourceNote,
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun delete(month: String): Boolean =
        writes.inTransaction { entries.delete(month) > 0 }
}
