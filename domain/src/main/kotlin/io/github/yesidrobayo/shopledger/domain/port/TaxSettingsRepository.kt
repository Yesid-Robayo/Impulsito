package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Cómo trata el negocio sus impuestos: el modo, la tasa por defecto y si el impuesto de compra es
 * recuperable. Viven en la única fila de los ajustes, y cambiarlos es un cambio del negocio.
 */
interface TaxSettingsRepository {
    /** Los ajustes de impuestos, de nuevo cada vez que cambian. */
    fun observe(): Flow<TaxSettings>

    suspend fun get(): TaxSettings

    /**
     * Guarda [taxes] entero. Solo dentro de [TransactionRunner.write]: cuenta como un cambio sin
     * respaldar. La tasa por defecto, si hay, debe existir y estar vigente.
     */
    suspend fun update(taxes: TaxSettings, updatedAt: Instant)
}
