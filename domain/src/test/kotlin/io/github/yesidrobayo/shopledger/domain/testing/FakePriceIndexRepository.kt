package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.port.PriceIndexRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.YearMonth

/**
 * El índice de precios en la base en memoria, un mes por fila como la tabla real. La fuente de
 * cada mes vive aparte, en el doble: ninguna prueba de transacciones la mira.
 */
internal class FakePriceIndexRepository(private val database: InMemoryDatabase) :
    PriceIndexRepository {
    private val notes = MutableStateFlow(emptyMap<String, String>())

    override suspend fun months(): Set<String> = database.read().priceIndex.keys

    override suspend fun insert(month: String, change: BasisPoints, createdAt: Instant): Long {
        check(month !in database.read().priceIndex) { "El mes ya tiene variación" }
        val id = database.nextId(TABLE)
        database.write { it.copy(priceIndex = it.priceIndex + (month to change.bp)) }
        return id
    }

    override suspend fun update(month: String, change: BasisPoints, updatedAt: Instant) {
        check(month in database.read().priceIndex) { "El mes no tiene variación" }
        database.write { it.copy(priceIndex = it.priceIndex + (month to change.bp)) }
    }

    override fun observeEntries(): Flow<List<PriceIndexEntry>> =
        combine(database.tables, notes) { tables, notes ->
            tables.priceIndex.toSortedMap().map { (month, bp) ->
                PriceIndexEntry(YearMonth.parse(month), BasisPoints(bp), notes[month])
            }
        }

    override suspend fun insertEntry(entry: PriceIndexEntry, createdAt: Instant): Long {
        val id = insert(entry.month.toString(), entry.change, createdAt)
        keepNote(entry)
        return id
    }

    override suspend fun updateEntry(entry: PriceIndexEntry, updatedAt: Instant) {
        update(entry.month.toString(), entry.change, updatedAt)
        keepNote(entry)
    }

    override suspend fun delete(month: String): Boolean {
        if (month !in database.read().priceIndex) return false
        database.write { it.copy(priceIndex = it.priceIndex - month) }
        notes.value -= month
        return true
    }

    private fun keepNote(entry: PriceIndexEntry) {
        val month = entry.month.toString()
        val note = entry.sourceNote
        notes.value = if (note == null) notes.value - month else notes.value + (month to note)
    }

    private companion object {
        const val TABLE = "price_index_entries"
    }
}
