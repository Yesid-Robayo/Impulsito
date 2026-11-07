package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupFileNameTest {
    private val stamp = requireNotNull(CopyInstant.parse("20261101T203012Z"))

    @Test
    fun writesTheDocumentedNameForEachFormat() {
        val gzip = BackupFileName(42, 1, stamp, "7f3a9c1e", BackupFileFormat.GZIP)
        assertEquals(
            "shopledger-backup-g00000042-s001-20261101T203012Z-7f3a9c1e.db.gz",
            gzip.fileName,
        )
        assertEquals(gzip.fileName + ".slmeta", gzip.metaFileName)
        val encrypted = gzip.copy(format = BackupFileFormat.ENCRYPTED)
        assertEquals(
            "shopledger-backup-g00000042-s001-20261101T203012Z-7f3a9c1e.slbk",
            encrypted.fileName,
        )
    }

    @Test
    fun readsBackWhatItWrites() {
        val name =
            BackupFileName(123_456_789_012, 12, stamp, "00ff00ff", BackupFileFormat.ENCRYPTED)
        assertEquals(name, BackupFileName.parse(name.fileName))
        assertEquals(name, BackupFileName.parseMeta(name.metaFileName))
    }

    @Test
    fun ignoresNamesThatAreOnlySimilar() {
        val valid = "shopledger-backup-g00000042-s001-20261101T203012Z-7f3a9c1e.db.gz"
        val similar =
            listOf(
                // Ceros de más, que la app nunca escribiría.
                "shopledger-backup-g000000042-s001-20261101T203012Z-7f3a9c1e.db.gz",
                "shopledger-backup-g00000042-s0001-20261101T203012Z-7f3a9c1e.db.gz",
                // Versión de esquema cero, fecha imposible, mayúsculas y otras cifras.
                "shopledger-backup-g00000042-s000-20261101T203012Z-7f3a9c1e.db.gz",
                "shopledger-backup-g00000042-s001-20260230T203012Z-7f3a9c1e.db.gz",
                "shopledger-backup-g00000042-s001-20261101T203012Z-7F3A9C1E.db.gz",
                "shopledger-backup-g0000004٢-s001-20261101T203012Z-7f3a9c1e.db.gz",
                // Otra extensión, un temporal y un prefijo distinto.
                "shopledger-backup-g00000042-s001-20261101T203012Z-7f3a9c1e.db",
                "$valid.sltmp",
                "other-backup-g00000042-s001-20261101T203012Z-7f3a9c1e.db.gz",
                // Una generación que no cabe en un entero de 64 bits.
                "shopledger-backup-g9999999999999999999-s001-20261101T203012Z-7f3a9c1e.db.gz",
            )
        similar.forEach { assertNull(it, BackupFileName.parse(it)) }
        assertNull(BackupFileName.parseMeta(valid))
        assertNull(BackupFileName.parseMeta("$valid.slmeta.sltmp"))
    }
}
