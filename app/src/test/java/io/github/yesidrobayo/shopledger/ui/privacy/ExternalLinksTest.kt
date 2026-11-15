package io.github.yesidrobayo.shopledger.ui.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un enlace solo sale al navegador si es `https` con su servidor: ningún otro esquema, ni una
 * dirección con usuario o contraseña, ni un texto que no es una dirección.
 */
class ExternalLinksTest {
    @Test
    fun `an https address with its host opens`() {
        assertTrue(isOpenableLink("https://example.com/privacy"))
        assertTrue(isOpenableLink("  https://example.com  "))
    }

    @Test
    fun `other schemes never open`() {
        for (url in listOf(
            "http://example.com",
            "intent://example.com#Intent;end",
            "file:///sdcard/a.html",
            "javascript:alert(1)",
            "content://example/a",
            "HTTPS://example.com",
        )) {
            assertFalse(url, isOpenableLink(url))
        }
    }

    @Test
    fun `an address without host, with credentials or broken never opens`() {
        assertFalse(isOpenableLink("https:///path"))
        assertFalse(isOpenableLink("https://user:secret@example.com"))
        assertFalse(isOpenableLink("https://exa mple.com"))
        assertFalse(isOpenableLink(""))
    }
}
