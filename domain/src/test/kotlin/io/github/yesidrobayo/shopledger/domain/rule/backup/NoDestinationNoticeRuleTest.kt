package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationNotice.COMPACT
import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationNotice.FULL
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El aviso sin destino: entero hasta que se acepta, reducido después, y entero otra vez a los 7
 * días o con 50 ventas nuevas. Cada borde se prueba de un lado y del otro, con fechas y ventas
 * escritas a mano.
 */
class NoDestinationNoticeRuleTest {
    // 2026-11-13T15:00:00Z.
    private val acceptedAt = Instant.fromEpochMilliseconds(1_794_582_000_000L)
    private val accepted = NoDestinationAcknowledgement(acceptedAt, lastSaleId = 120L)

    private fun notice(after: kotlin.time.Duration, lastSaleId: Long = 120L) =
        NoDestinationNoticeRule.noticeOf(accepted, acceptedAt + after, lastSaleId)

    @Test
    fun withoutAcceptingItIsFull() {
        assertEquals(FULL, NoDestinationNoticeRule.noticeOf(null, acceptedAt, 0L))
    }

    @Test
    fun rightAfterAcceptingItIsCompact() {
        assertEquals(COMPACT, notice(0.milliseconds))
        assertEquals(COMPACT, notice(10.minutes))
    }

    @Test
    fun itComesBackFullAfterSevenDays() {
        assertEquals(COMPACT, notice(7.days - 1.milliseconds))
        assertEquals(FULL, notice(7.days))
        assertEquals(FULL, notice(30.days))
    }

    @Test
    fun itComesBackFullAfterFiftyNewSales() {
        // De la venta 120 a la 169 son 49 nuevas; a la 170, 50.
        assertEquals(COMPACT, notice(1.days, lastSaleId = 169L))
        assertEquals(FULL, notice(1.days, lastSaleId = 170L))
    }

    @Test
    fun aClockBeforeTheAcceptanceShowsItFull() {
        assertEquals(FULL, notice((-1).milliseconds))
    }

    @Test
    fun anOlderLastSaleThanWhenAcceptedShowsItFull() {
        // Como tras restaurar una base con menos ventas: el registro ya no describe estos datos.
        assertEquals(FULL, notice(1.days, lastSaleId = 119L))
    }

    @Test
    fun acceptingWithoutSalesCountsFromZero() {
        val none = NoDestinationAcknowledgement(acceptedAt, lastSaleId = 0L)

        assertEquals(COMPACT, NoDestinationNoticeRule.noticeOf(none, acceptedAt, 49L))
        assertEquals(FULL, NoDestinationNoticeRule.noticeOf(none, acceptedAt, 50L))
    }
}
