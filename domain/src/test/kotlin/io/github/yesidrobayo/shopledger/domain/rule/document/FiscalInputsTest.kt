package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Lo que la persona escribe en el puente fiscal: la referencia hasta 60 caracteres y el enlace
 * solo `https` con servidor, hasta 300, contados como la base. Direcciones inventadas de
 * `example.com`.
 */
class FiscalInputsTest {
    @Test
    fun `the reference is trimmed, empty means none, and sixty characters is the limit`() {
        assertEquals(ReferenceInput.Valid("A-1043"), FiscalInputs.reference("  A-1043 "))
        assertEquals(ReferenceInput.Valid(null), FiscalInputs.reference("   "))
        assertEquals(ReferenceInput.Valid("R".repeat(60)), FiscalInputs.reference("R".repeat(60)))
        assertEquals(ReferenceInput.TooLong, FiscalInputs.reference("R".repeat(61)))
    }

    @Test
    fun `an emoji counts as one character, as the database counts it`() {
        val sixty = "📦".repeat(60)

        assertEquals(ReferenceInput.Valid(sixty), FiscalInputs.reference(sixty))
    }

    @Test
    fun `only https links with a host are accepted`() {
        assertEquals(
            PortalUrlInput.Valid("https://example.com"),
            FiscalInputs.portalUrl(" https://example.com "),
        )
        assertEquals(
            PortalUrlInput.Valid("HTTPS://example.com/a?b=1#c"),
            FiscalInputs.portalUrl("HTTPS://example.com/a?b=1#c"),
        )
        assertEquals(PortalUrlInput.Valid(null), FiscalInputs.portalUrl(""))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("http://example.com"))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("example.com"))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("https://"))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("https:///path"))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("https://?q"))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("https://#x"))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("https://exa mple.com"))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("https://example.com/\u0000"))
        assertEquals(PortalUrlInput.NotHttps, FiscalInputs.portalUrl("javascript:alert(1)"))
    }

    @Test
    fun `a link of three hundred characters is the limit`() {
        val base = "https://example.com/"
        val limit = base + "a".repeat(300 - base.length)

        assertEquals(PortalUrlInput.Valid(limit), FiscalInputs.portalUrl(limit))
        assertEquals(PortalUrlInput.TooLong, FiscalInputs.portalUrl(limit + "a"))
    }

    @Test
    fun `only a recorded mark carries a recording date`() {
        val at = Instant.fromEpochMilliseconds(1_793_000_000_000)

        assertEquals(at, SaleFiscalMark(1L, FiscalMarkStatus.RECORDED, null, at).recordedAt)
        assertThrows(IllegalArgumentException::class.java) {
            SaleFiscalMark(1L, FiscalMarkStatus.RECORDED, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SaleFiscalMark(1L, FiscalMarkStatus.PENDING, null, at)
        }
    }
}
