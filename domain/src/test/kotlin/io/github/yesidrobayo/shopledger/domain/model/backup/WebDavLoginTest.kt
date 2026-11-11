package io.github.yesidrobayo.shopledger.domain.model.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Lo que la persona escribe para conectar nunca sale en un texto que pueda ir a un registro. */
class WebDavLoginTest {
    @Test
    fun `its text carries neither the password nor the address`() {
        val login = WebDavLogin("https://dav.example.com/files", "copias", "inventada-123")

        val text = login.toString()

        assertEquals("WebDavLogin", text)
        assertFalse(text.contains("inventada"))
        assertFalse(text.contains("example"))
    }
}
