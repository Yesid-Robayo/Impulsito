package io.github.yesidrobayo.shopledger.ui.webdav

import org.junit.Assert.assertFalse
import org.junit.Test

/** El estado y la acción de conectar WebDAV nunca imprimen la credencial. Valores inventados. */
class WebDavConnectRedactionTest {
    @Test
    fun theStateNeverPrintsTheAddressTheUserOrThePassword() {
        val state =
            WebDavConnectUiState(
                url = "https://dav.example.com/carpeta",
                username = "persona-inventada",
                password = "clave-inventada",
            )

        val text = state.toString()

        assertFalse("clave-inventada" in text)
        assertFalse("persona-inventada" in text)
        assertFalse("dav.example.com" in text)
    }

    @Test
    fun thePasswordChangeNeverPrintsThePassword() {
        val action = WebDavConnectAction.PasswordChange("clave-inventada")

        assertFalse("clave-inventada" in action.toString())
    }
}
