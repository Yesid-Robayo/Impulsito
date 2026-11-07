package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Con el servicio lleno, cuándo una copia automática sale sin generar nada: si se sabe que no cabe,
 * o, sin espacio informado, hasta un día después del intento anterior. `Respaldar ahora` siempre
 * sigue, y sin el fallo de cuota nada se detiene.
 */
class QuotaGateTest {
    private val full = BackupFailureCode.QUOTA_EXCEEDED.name
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun aKnownQuotaHoldsOnlyACopyThatDoesNotFit() {
        assertTrue(holds(quota = 999, expected = 1_000))
        assertFalse(holds(quota = 1_000, expected = 1_000))
        // Sin tamaño esperado no hay con qué comparar: sigue.
        assertFalse(holds(quota = 0, expected = null))
    }

    @Test
    fun anUnknownQuotaTriesOnceADay() {
        assertTrue(holds(quota = null, last = now - 23 * hour))
        assertFalse(holds(quota = null, last = now - 24 * hour))
        // Un espacio negativo no es un tope conocido.
        assertTrue(holds(quota = -1, expected = 1, last = now - hour))
        // Sin intento anterior, o con el reloj atrasado, lo intenta.
        assertFalse(holds(quota = null, last = null))
        assertFalse(holds(quota = null, last = now + hour))
    }

    @Test
    fun backupNowAndAnyOtherStateAlwaysGoOn() {
        assertFalse(
            QuotaGate.holds(full, backupNow = true, 0, 1_000, now - hour, now),
        )
        for (code in listOf(null, BackupFailureCode.VERIFY_MISMATCH.name)) {
            assertFalse(QuotaGate.holds(code, backupNow = false, 0, 1_000, now - hour, now))
        }
    }

    private fun holds(quota: Long?, expected: Long? = 1_000, last: Long? = now - hour) =
        QuotaGate.holds(full, backupNow = false, quota, expected, last, now)
}
