package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.pricing.NewFxRate
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import kotlinx.coroutines.flow.Flow

/**
 * Las cotizaciones manuales de la moneda de referencia. Solo agrega y lee: no hay forma de editar
 * ni de borrar una, porque el historial dice con qué cotización se repreció cada vez. Ninguna sale
 * de internet.
 */
interface FxRateRepository {
    /**
     * La vigente de [currency]: la de instante más reciente y, a igual instante, la última. Vuelve
     * a emitir cuando la persona escribe otra.
     */
    fun observeLatest(currency: CurrencyCode): Flow<FxRate?>

    /** Las de [currency], de la más reciente a la más vieja, de nuevo con cada una nueva. */
    fun observeHistory(currency: CurrencyCode): Flow<List<FxRate>>

    /** Agrega [rate] y devuelve su id. Solo dentro de [TransactionRunner.write]. */
    suspend fun append(rate: NewFxRate): Long
}
