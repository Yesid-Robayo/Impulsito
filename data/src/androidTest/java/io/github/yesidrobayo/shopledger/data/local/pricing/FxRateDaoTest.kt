package io.github.yesidrobayo.shopledger.data.local.pricing

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las cotizaciones manuales: cada columna vuelve como se guardó, la vigente es la de instante más
 * reciente, la cotización es siempre mayor que cero, y una cotización nunca se edita ni se borra.
 */
@RunWith(AndroidJUnit4::class)
internal class FxRateDaoTest : CatalogDatabaseTest() {
    private val rates get() = database.fxRateDao()

    @Test
    fun aNewDatabaseHasNoRatesAndARateReadsBackWithEveryColumn() = runBlocking {
        assertEquals(0L, count("SELECT COUNT(*) FROM fx_rates"))
        val rate = fxRate(rateMicro = 2_560_000).copy(note = "Cotización de la semana")

        val id = rates.insert(rate)

        assertEquals(rate.copy(id = id), rates.findLatest(TEST_CURRENCY))
        assertNull(rates.findLatest("XXX"))
    }

    @Test
    fun theLatestIsTheOneWithTheMostRecentInstantThenTheLastSaved() = runBlocking {
        val older = rates.insert(fxRate(rateMicro = 2_500_000, occurredAt = LATER))
        // Escrita después, pero vale para antes: no es la vigente.
        val backdated = rates.insert(fxRate(rateMicro = 2_400_000))
        val tie = rates.insert(fxRate(rateMicro = 2_600_000, occurredAt = LATER))

        assertEquals(tie, rates.findLatest(TEST_CURRENCY)?.id)

        val latest = Emissions(this, rates.observeByCurrency(TEST_CURRENCY).ids { it.id })
        try {
            latest.awaitValue(listOf(tie, older, backdated))
            val newest = rates.insert(fxRate(rateMicro = 2_700_000, occurredAt = MUCH_LATER))
            latest.awaitValue(listOf(newest, tie, older, backdated))
        } finally {
            latest.stop()
        }
    }

    @Test
    fun theLatestEmitsAgainWhenThePersonWritesAnother() = runBlocking {
        val emissions = Emissions(this, rates.observeLatest(TEST_CURRENCY))
        try {
            emissions.awaitValue(null)
            val first = fxRate(rateMicro = 2_500_000)
            val firstId = rates.insert(first)
            emissions.awaitValue(first.copy(id = firstId))
            val second = fxRate(rateMicro = 2_550_000, occurredAt = LATER)
            val secondId = rates.insert(second)
            emissions.awaitValue(second.copy(id = secondId))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun theRateIsAlwaysAboveZeroAndTheCodeHasThreeCapitals() = runBlocking {
        rates.insert(fxRate(rateMicro = 1))
        rates.insert(fxRate(rateMicro = Limit.DOCUMENT_TOTAL.max))

        guardFailure(GUARD) { rates.insert(fxRate(rateMicro = 0)) }
        guardFailure(GUARD) { rates.insert(fxRate(rateMicro = -1)) }
        guardFailure(GUARD) { rates.insert(fxRate(rateMicro = Limit.DOCUMENT_TOTAL.max + 1)) }
        guardFailure(GUARD) { rates.insert(fxRate(rateMicro = 1).copy(currencyCode = "xts")) }
        guardFailure(GUARD) { rates.insert(fxRate(rateMicro = 1).copy(currencyCode = "XTSX")) }
        guardFailure(GUARD) {
            rates.insert(fxRate(rateMicro = 1).copy(businessDate = "2026-13-01"))
        }
        guardFailure(GUARD) { rates.insert(fxRate(rateMicro = 1).copy(note = "")) }
        assertEquals(2L, count("SELECT COUNT(*) FROM fx_rates"))
    }

    @Test
    fun aRateIsNeverEditedNorDeleted() = runBlocking {
        val id = rates.insert(fxRate(rateMicro = 2_500_000))

        guardFailure(APPEND_ONLY) { execute("UPDATE fx_rates SET rate_micro = 1 WHERE id = $id") }
        guardFailure(APPEND_ONLY) { execute("DELETE FROM fx_rates WHERE id = $id") }

        assertEquals(2_500_000L, rates.findLatest(TEST_CURRENCY)?.rateMicro)
        // A propósito, el DAO no tiene con qué editar ni borrar una cotización.
        assertEquals(
            setOf("findLatest", "observeLatest", "observeByCurrency", "insert"),
            functionsOf(FxRateDao::class.java),
        )
        assertEquals(
            listOf("index_fx_rates_currency_code_occurred_at (currency_code, occurred_at)"),
            database.indexesOf("fx_rates"),
        )
    }

    private companion object {
        const val GUARD = "guard:fx_rates"
        const val APPEND_ONLY = "guard:fx_rates_append_only"
    }
}
