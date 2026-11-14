package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.port.PriceIndexRepository
import kotlin.time.Instant

/** El índice de precios en la base en memoria, un mes por fila como la tabla real. */
internal class FakePriceIndexRepository(private val database: InMemoryDatabase) :
    PriceIndexRepository {
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

    private companion object {
        const val TABLE = "price_index_entries"
    }
}
