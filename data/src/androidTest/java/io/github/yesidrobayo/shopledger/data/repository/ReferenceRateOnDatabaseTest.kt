package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ObserveReferenceRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.RecordReferenceRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.RecordReferenceRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetReferenceCurrencyResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetReferenceCurrencyUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La moneda de referencia y su cotización manual sobre la base real: elegirla queda en los ajustes,
 * cada cotización es una fila nueva del libro, la vigente es la última, quitar la moneda conserva
 * sus cotizaciones y ningún precio cambia. Código de moneda y cotizaciones inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ReferenceRateOnDatabaseTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val rates by lazy { RoomFxRateRepository(database) }
    private val clock = Clock { LATER_AT }
    private val reference = ReferenceCurrency(requireNotNull(CurrencyCode.of("XXX")), 2)

    private suspend fun prices(): Long =
        count("SELECT COALESCE(SUM(sale_price), 0) + COUNT(*) FROM products")

    @Test
    fun aRateIsAnAppendedRowAndNoPriceChanges() = runBlocking {
        val pricesBefore = prices()
        val historyBefore = count("SELECT COUNT(*) FROM product_price_history")
        val set = SetReferenceCurrencyUseCase(runner, settings, clock)
        val record = RecordReferenceRateUseCase(runner, settings, rates, clock)

        assertEquals(SetReferenceCurrencyResult.Saved, set(reference))
        assertEquals(reference, settings.get().regional.referenceCurrency)
        val first = record(4_150_500_000, "Casa de cambio") as RecordReferenceRateResult.Recorded
        val second = record(4_200_000_000, null) as RecordReferenceRateResult.Recorded

        val view = ObserveReferenceRateUseCase(settings, rates)().first()
        assertEquals(second.rateId, view.latest?.id)
        assertEquals(listOf(second.rateId, first.rateId), view.history.map { it.id })
        assertEquals("Casa de cambio", view.history.last().content.note)
        assertEquals(2L, count("SELECT COUNT(*) FROM fx_rates WHERE currency_code = 'XXX'"))
        assertEquals(pricesBefore, prices())
        assertEquals(historyBefore, count("SELECT COUNT(*) FROM product_price_history"))
    }

    @Test
    fun clearingTheReferenceKeepsItsRatesForWhenItComesBack() = runBlocking {
        val set = SetReferenceCurrencyUseCase(runner, settings, clock)
        set(reference)
        RecordReferenceRateUseCase(runner, settings, rates, clock)(4_150_500_000, null)

        assertEquals(SetReferenceCurrencyResult.Saved, set(null))
        assertNull(settings.get().regional.referenceCurrency)
        assertNull(ObserveReferenceRateUseCase(settings, rates)().first().latest)
        assertEquals(1L, count("SELECT COUNT(*) FROM fx_rates"))

        set(reference)
        assertEquals(
            4_150_500_000,
            ObserveReferenceRateUseCase(settings, rates)().first().latest?.content?.rateMicro,
        )
    }
}
