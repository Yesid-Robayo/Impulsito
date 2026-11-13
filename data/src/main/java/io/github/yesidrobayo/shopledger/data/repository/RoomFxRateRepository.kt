package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.pricing.FxRateEntity
import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.pricing.NewFxRate
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.FxRateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las cotizaciones manuales sobre Room. Solo agrega y lee: la tabla es un libro, y sus guardas
 * rechazan cualquier cambio o borrado.
 */
internal class RoomFxRateRepository(database: AppDatabase) : FxRateRepository {
    private val rates = database.fxRateDao()
    private val writes = RepositoryWrites(database)

    override fun observeLatest(currency: CurrencyCode): Flow<FxRate?> = rates
        .observeLatest(currency.code)
        .map { row -> row?.toRate() }
        .distinctUntilChanged()

    override fun observeHistory(currency: CurrencyCode): Flow<List<FxRate>> = rates
        .observeByCurrency(currency.code)
        .map { rows -> rows.map { it.toRate() } }
        .distinctUntilChanged()

    override suspend fun append(rate: NewFxRate): Long = writes.inTransaction {
        rates.insert(
            FxRateEntity(
                currencyCode = rate.currency.code,
                rateMicro = rate.rateMicro,
                note = rate.note,
                occurredAt = rate.occurredAt.toStoredMillis(),
                businessDate = rate.businessDate.toStored(),
                createdAt = rate.createdAt.toStoredMillis(),
            ),
        )
    }
}

private fun FxRateEntity.toRate(): FxRate = FxRate(
    id = id,
    content = NewFxRate(
        currency = CurrencyCode.of(currencyCode) ?: brokenRow(),
        rateMicro = rateMicro,
        note = note,
        occurredAt = occurredAt.toInstant(),
        businessDate = storedDate(businessDate),
        createdAt = createdAt.toInstant(),
    ),
)
