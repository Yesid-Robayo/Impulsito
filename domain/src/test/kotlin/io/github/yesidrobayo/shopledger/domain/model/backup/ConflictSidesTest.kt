package io.github.yesidrobayo.shopledger.domain.model.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que el diálogo del conflicto deduce de los dos lados: si conservar este teléfono deja menos
 * datos, cuántos registros faltan y si los dos lados cambiaron.
 */
class ConflictSidesTest {
    private val sides =
        ConflictSides(
            kind = BackupConflictKind.OTHER_DEVICE,
            destinationKind = BackupDestinationKind.WEBDAV,
            localRecords = 120,
            localCatalogCount = 30,
            pendingChanges = 0,
            cloudCopyKey = "copia",
            cloudCreatedAt = null,
            cloudDeviceLabel = null,
            cloudFromThisPhone = false,
            cloudRecords = 100,
            cloudCatalogCount = 30,
        )

    @Test
    fun `with more records and the same catalog this phone does not have fewer`() {
        assertFalse(sides.localHasFewer)
        assertEquals(0L, sides.missingRecords)
    }

    @Test
    fun `fewer records ask for the reinforced confirmation and count the difference`() {
        val fewer = sides.copy(localRecords = 95)

        assertTrue(fewer.localHasFewer)
        assertEquals(5L, fewer.missingRecords)
    }

    @Test
    fun `a smaller catalog alone also asks for the reinforced confirmation`() {
        val fewer = sides.copy(localCatalogCount = 29)

        assertTrue(fewer.localHasFewer)
        assertEquals(0L, fewer.missingRecords)
    }

    @Test
    fun `without a head in the cloud nothing is missing`() {
        val empty = sides.copy(cloudRecords = null, cloudCatalogCount = null)

        assertFalse(empty.localHasFewer)
        assertEquals(0L, empty.missingRecords)
        assertFalse(empty.copy(pendingChanges = 3).bothSidesChanged)
    }

    @Test
    fun `both sides changed only with local changes and a head in the cloud`() {
        assertFalse(sides.bothSidesChanged)
        assertTrue(sides.copy(pendingChanges = 1).bothSidesChanged)
    }

    @Test
    fun `the phone cloud never prints the address of its file`() {
        val info = BackupDestinationInfo.Slots("Nube", true, "content://proveedor/documento/1")

        assertEquals("Slots(Nube, true)", info.toString())
    }
}
