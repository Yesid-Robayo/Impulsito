package io.github.yesidrobayo.shopledger.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Los nombres de la carpeta de preparación salen solo de la generación, con cifras ASCII. */
class BackupStagingTest {
    private val staging = BackupStaging(File("staging"))

    @Test
    fun `the snapshot name carries the generation with at least eight digits`() {
        assertEquals(File("staging", "snapshot-g00000001.db"), staging.snapshotFile(1))
        assertEquals(File("staging", "snapshot-g00000042.db"), staging.snapshotFile(42))
        assertEquals(File("staging", "snapshot-g99999999.db"), staging.snapshotFile(99_999_999))
        // Más de ocho cifras no se recortan: el nombre sigue siendo único.
        assertEquals(File("staging", "snapshot-g123456789.db"), staging.snapshotFile(123_456_789))
    }

    @Test
    fun `a generation starts at one`() {
        assertThrows(IllegalArgumentException::class.java) { staging.snapshotFile(0) }
        assertThrows(IllegalArgumentException::class.java) { staging.snapshotFile(-1) }
    }
}
