package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import io.github.yesidrobayo.shopledger.domain.model.backup.DeviceStorage
import io.github.yesidrobayo.shopledger.domain.model.backup.ServiceSpace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los avisos de espacio: poco espacio en el teléfono, con el mayor entre 200 MiB y tres veces la
 * base, y la copia cerca del límite, con el 80 % del archivo más grande que se sube o de la base
 * más grande que se restaura. Cada borde se prueba de un lado y del otro.
 */
class StorageNoticeRuleTest {
    private val mib = 1024L * 1024

    @Test
    fun aSmallBaseNeedsTwoHundredMebibytesFree() {
        val base = 10 * mib

        assertTrue(StorageNoticeRule.lowDeviceStorage(DeviceStorage(200 * mib - 1, base)))
        assertFalse(StorageNoticeRule.lowDeviceStorage(DeviceStorage(200 * mib, base)))
    }

    @Test
    fun aLargeBaseNeedsThreeTimesItsSizeFree() {
        val base = 150 * mib

        assertTrue(StorageNoticeRule.lowDeviceStorage(DeviceStorage(450 * mib - 1, base)))
        assertFalse(StorageNoticeRule.lowDeviceStorage(DeviceStorage(450 * mib, base)))
    }

    @Test
    fun aBaseTooLargeToTripleAlwaysWarnsAndANegativeSizeCountsAsNone() {
        assertTrue(
            StorageNoticeRule.lowDeviceStorage(
                DeviceStorage(Long.MAX_VALUE, Long.MAX_VALUE / 3 + 1),
            ),
        )
        assertFalse(StorageNoticeRule.lowDeviceStorage(DeviceStorage(200 * mib, -1)))
    }

    @Test
    fun theHeadWarnsWhenItReachesEightyPercentOfTheDownloadLimit() {
        // El 80 % de 512 MiB no es entero: llegar es pasar de 429.496.729 bytes.
        val limit = BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES

        assertFalse(StorageNoticeRule.nearCopyLimit(limit * 4 / 5, databaseBytes = null))
        assertTrue(StorageNoticeRule.nearCopyLimit(limit * 4 / 5 + 1, databaseBytes = null))
    }

    @Test
    fun theBaseWarnsWhenItReachesEightyPercentOfTheRestoreLimit() {
        val limit = BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES
        val threshold = (limit * 4 + 4) / 5

        assertFalse(StorageNoticeRule.nearCopyLimit(headFileBytes = null, threshold - 1))
        assertTrue(StorageNoticeRule.nearCopyLimit(headFileBytes = null, threshold))
        // La cabeza sola no llegaría: la base sí.
        assertTrue(StorageNoticeRule.nearCopyLimit(headFileBytes = 1, threshold))
    }

    @Test
    fun withoutAnySizeThereIsNoWarning() {
        assertFalse(StorageNoticeRule.nearCopyLimit(headFileBytes = null, databaseBytes = null))
    }

    @Test
    fun theServiceWarnsWhenLessThanThreeCopiesFit() {
        val copy = 50 * mib

        assertTrue(StorageNoticeRule.serviceNearlyFull(ServiceSpace(150 * mib - 1, copy)))
        assertFalse(StorageNoticeRule.serviceNearlyFull(ServiceSpace(150 * mib, copy)))
    }

    @Test
    fun withoutACopyThereIsNothingToMeasureAndAHugeCopyAlwaysWarns() {
        assertFalse(StorageNoticeRule.serviceNearlyFull(ServiceSpace(0, 0)))
        assertTrue(
            StorageNoticeRule.serviceNearlyFull(
                ServiceSpace(
                    Long.MAX_VALUE,
                    Long.MAX_VALUE / 3 + 1,
                ),
            ),
        )
    }
}
