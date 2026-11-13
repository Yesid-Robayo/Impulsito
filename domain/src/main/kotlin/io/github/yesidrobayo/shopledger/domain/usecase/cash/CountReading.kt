package io.github.yesidrobayo.shopledger.domain.usecase.cash

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCounted
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.port.CashDenominationRepository
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashCount

/** Lo que salió de leer un conteo: el monto contado y, si fue por denominación, sus piezas. */
internal sealed interface CountReading {
    data class Read(val amount: Money, val counts: List<DenominationCount>) : CountReading

    /** El monto es negativo, pasa del máximo, o una cantidad está fuera de rango. */
    data object OutOfRange : CountReading

    /** Una denominación no existe, está inactiva o se repite. */
    data object UnknownDenomination : CountReading
}

/**
 * Lee lo que contó la persona dentro de la transacción: un monto escrito tal cual, o el total de
 * las piezas por denominación, con las denominaciones vigentes. Las piezas en cero no se guardan.
 */
internal suspend fun readCount(
    counted: CashCounted,
    denominations: CashDenominationRepository?,
): CountReading = when (counted) {
    is CashCounted.Amount ->
        if (counted.amount < Money.ZERO ||
            !Limit.DOCUMENT_TOTAL.allows(counted.amount.minor)
        ) {
            CountReading.OutOfRange
        } else {
            CountReading.Read(counted.amount, emptyList())
        }

    is CashCounted.ByDenomination -> readPieces(
        counted.counts,
        requireNotNull(denominations) { "Contar por denominación pide las denominaciones" },
    )
}

private suspend fun readPieces(
    counts: List<DenominationCount>,
    denominations: CashDenominationRepository,
): CountReading {
    if (counts.map { it.denominationId }.toSet().size != counts.size) {
        return CountReading.UnknownDenomination
    }
    val values = ArrayList<Pair<Money, Long>>(counts.size)
    for (count in counts) {
        if (count.count !in 0..Limit.LINE_QUANTITY_MILLI.max) return CountReading.OutOfRange
        val denomination = denominations.findById(count.denominationId)
        if (denomination == null || !denomination.isActive) return CountReading.UnknownDenomination
        values += denomination.value to count.count
    }
    val total = CashCount.countedOf(values)
    return if (total == null || !Limit.DOCUMENT_TOTAL.allows(total.minor)) {
        CountReading.OutOfRange
    } else {
        CountReading.Read(total, counts.filter { it.count > 0 })
    }
}
