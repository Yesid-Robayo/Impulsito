package io.github.yesidrobayo.shopledger.domain.model.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cada forma de valor de los metadatos acepta solo su texto exacto. Los metadatos vienen de un
 * servicio no confiable, así que cada caso rechazado es una entrada que alguien podría escribir.
 */
class MetaValueFormTest {
    @Test
    fun `a count is a canonical non negative decimal that fits a long`() {
        assertEquals(0L, MetaValueForm.Count.parse("0"))
        assertEquals(42L, MetaValueForm.Count.parse("42"))
        assertEquals(485_000L, MetaValueForm.Count.parse("485000"))
        assertEquals(Long.MAX_VALUE, MetaValueForm.Count.parse("9223372036854775807"))
        assertEquals(1_000_000_000_000_000_000L, MetaValueForm.Count.parse("1000000000000000000"))
        assertTrue(MetaValueForm.Count.accepts("1"))
    }

    @Test
    fun `a count rejects signs leading zeros separators spaces and overflow`() {
        val rejected =
            listOf(
                "", "-1", "+1", "007", "00", "1a", "a1", "1,000", "1.000", "1 000", " 1", "1e3",
                "9223372036854775808", "9999999999999999999", "10000000000000000000",
            )

        for (text in rejected) assertNull(text, MetaValueForm.Count.parse(text))
        assertFalse(MetaValueForm.Count.accepts("-1"))
    }

    @Test
    fun `a count rejects digits of other numbering systems`() {
        // Arábigos orientales, devanagari y de ancho completo: son cifras para el teléfono, no para
        // la copia.
        for (text in listOf("٤٢", "४२", "４２", "4٢")) {
            assertNull(text, MetaValueForm.Count.parse(text))
        }
    }

    @Test
    fun `lower hex needs its exact length in lowercase`() {
        val form = MetaValueForm.LowerHex(4)

        assertTrue(form.accepts("09af"))
        assertFalse(form.accepts("09a"))
        assertFalse(form.accepts("09afe"))
        assertFalse(form.accepts("09AF"))
        assertFalse(form.accepts("09ag"))
    }

    @Test
    fun `lower hex needs a positive length`() {
        assertThrows(IllegalArgumentException::class.java) { MetaValueForm.LowerHex(0) }
    }

    @Test
    fun `a uuid is the canonical lowercase text`() {
        assertTrue(MetaValueForm.Uuid.accepts("7f3a9c1e-0b2d-4c5e-8f60-123456789abc"))
        assertFalse(MetaValueForm.Uuid.accepts("7F3A9C1E-0B2D-4C5E-8F60-123456789ABC"))
        assertFalse(MetaValueForm.Uuid.accepts("7f3a9c1e0b2d4c5e8f60123456789abc"))
        assertFalse(MetaValueForm.Uuid.accepts("7f3a9c1e-0b2d-4c5e-8f60-123456789ab"))
        assertFalse(MetaValueForm.Uuid.accepts("7f3a9c1e_0b2d-4c5e-8f60-123456789abc"))
        assertFalse(MetaValueForm.Uuid.accepts("7f3a9c1e-0b2d-4c5e-8f60-123456789a-c"))
    }

    @Test
    fun `one of accepts only its literals exactly`() {
        val form = MetaValueForm.OneOf(listOf("backup", "media"))

        assertTrue(form.accepts("backup"))
        assertTrue(form.accepts("media"))
        assertFalse(form.accepts("Backup"))
        assertFalse(form.accepts("backup "))
        assertFalse(form.accepts("backup_root"))
    }

    @Test
    fun `one of needs at least one literal`() {
        assertThrows(IllegalArgumentException::class.java) { MetaValueForm.OneOf(emptyList()) }
    }

    @Test
    fun `an app version is major minor patch with an optional lowercase suffix`() {
        val accepted = listOf("1.4.0", "0.0.0", "1.0.0-dev", "1.0.0-dev-debug", "2.10.3-rc.1")

        for (text in accepted) assertTrue(text, MetaValueForm.AppVersion.accepts(text))
    }

    @Test
    fun `an app version rejects other shapes`() {
        val rejected =
            listOf(
                "", "1.4", "1.4.0.0", "1.x.0", "01.4.0", "v1.4.0", "1.4.0-", "1.4.0-DEV",
                "1.4.0-dev_1", "1.4.0-dév", "1,4,0", "1.4.0 ",
            )

        for (text in rejected) assertFalse(text, MetaValueForm.AppVersion.accepts(text))
    }

    @Test
    fun `a device label is printable text within its byte limit`() {
        val form = MetaValueForm.DeviceLabel(124)

        assertTrue(form.accepts("Fabricante Modelo X1"))
        // Dos bytes por letra: 62 letras son 124 bytes, y 63 son 126.
        assertTrue(form.accepts("é".repeat(62)))
        assertFalse(form.accepts("é".repeat(63)))
        assertFalse(form.accepts(""))
        assertFalse(form.accepts("Modelo\nX"))
        assertFalse(form.accepts("Modelo\u0085X"))
        assertFalse(form.accepts("Modelo \uD800"))
    }

    @Test
    fun `a device label needs a positive limit`() {
        assertThrows(IllegalArgumentException::class.java) { MetaValueForm.DeviceLabel(0) }
    }
}
