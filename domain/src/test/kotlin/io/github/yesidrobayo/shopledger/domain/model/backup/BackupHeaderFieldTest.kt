package io.github.yesidrobayo.shopledger.domain.model.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La cabecera de una copia cifrada tiene sus campos en orden, sin huecos y con su lista cerrada. */
class BackupHeaderFieldTest {
    @Test
    fun `the fixed fields follow each other without gaps up to the fixed length`() {
        var next = 0
        for (field in BackupHeaderField.entries.dropLast(1)) {
            assertEquals(field.wireName, next, field.offset)
            next += requireNotNull(field.size) { "Solo las extensiones tienen largo variable" }
        }

        assertEquals(BackupHeaderField.FIXED_LENGTH, next)
    }

    @Test
    fun `the extensions are the only variable field and they come last`() {
        val variable = BackupHeaderField.entries.filter { it.size == null }

        assertEquals(listOf(BackupHeaderField.EXTENSIONS), variable)
        assertEquals(BackupHeaderField.FIXED_LENGTH, BackupHeaderField.EXTENSIONS.offset)
        assertTrue(BackupHeaderField.FIXED_LENGTH < BackupHeaderField.MAX_LENGTH)
    }

    @Test
    fun `only closed codes have a closed list and every closed code has one`() {
        for (field in BackupHeaderField.entries) {
            val isClosedCode = field.kind == HeaderValueKind.CLOSED_CODE
            assertEquals(field.wireName, isClosedCode, field.closedValues.isNotEmpty())
        }
    }

    @Test
    fun `the closed codes are the ones of the format`() {
        val closed =
            BackupHeaderField.entries.filter { it.closedValues.isNotEmpty() }
                .associate { it.wireName to it.closedValues }

        assertEquals(
            mapOf(
                "format_version" to listOf(2),
                "aead_id" to listOf(1),
                "key_source" to listOf(1),
                "payload_codec" to listOf(1),
                "reserved" to listOf(0),
            ),
            closed,
        )
    }
}
