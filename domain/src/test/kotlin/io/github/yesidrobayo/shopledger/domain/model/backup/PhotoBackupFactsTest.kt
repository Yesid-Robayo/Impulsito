package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoBackupFactsTest {
    private val base =
        PhotoBackupFacts(
            destination = PhotoDestination.WEBDAV,
            pending = 3,
            pendingOverADay = 1,
            stoppedForSpace = false,
            totalBytes = 0,
            exportable = 3,
        )

    @Test
    fun theWarningStartsAtExactlyOneGibibyte() {
        assertFalse(base.copy(totalBytes = PhotoBackupFacts.ONE_GIB - 1).overOneGiB)
        assertTrue(base.copy(totalBytes = PhotoBackupFacts.ONE_GIB).overOneGiB)
    }

    @Test
    fun homeShowsTheLineOnlyWithWebDavAndAPhotoPendingForMoreThanADay() {
        assertTrue(base.showsHomeLine)
        assertFalse(base.copy(pendingOverADay = 0).showsHomeLine)
        assertFalse(base.copy(destination = PhotoDestination.SLOTS).showsHomeLine)
        assertFalse(base.copy(destination = PhotoDestination.NONE).showsHomeLine)
    }

    @Test
    fun theExportNameUsesTheBusinessDateOrUtcWithoutAZone() {
        // 2026-11-14 02:30 UTC es todavía el 13 a tres horas al oeste.
        val at = Instant.parse("2026-11-14T02:30:00Z")

        assertEquals("media-20261114.zip", PhotoExportFileName.of(at, null))
        assertEquals(
            "media-20261113.zip",
            PhotoExportFileName.of(at, requireNotNull(BusinessTimeZone.of("Etc/GMT+3"))),
        )
    }
}
