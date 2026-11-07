package io.github.yesidrobayo.shopledger.data.destination

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La huella del destino y la carpeta de copias son las mismas en cualquier idioma y región. */
class DestinationIdentityTest {
    private fun endpoint(url: String) =
        (WebDavEndpoint.parse(url) as EndpointParse.Accepted).endpoint

    private fun id(url: String, user: String) =
        DestinationIdentity.destinationId(endpoint(url), user)

    @Test
    fun theSameAddressAndUserGiveTheSameIdentity() {
        val id = id("https://dav.example.com/remote/", "persona")
        assertEquals(32, id.length)
        assertEquals(id, id("HTTPS://DAV.Example.com:443/remote", "persona"))
        assertEquals(id, id(" https://dav.example.com/remote/ ", "persona"))
        assertNotEquals(id, id("https://dav.example.com/remote/", "otra-persona"))
        assertNotEquals(id, id("https://dav.example.com/otra/", "persona"))
        assertNotEquals(id, id("https://dav.example.com:8443/remote/", "persona"))
        // El valor exacto: SHA-256 de "https://dav.example.com/remote/\npersona".
        assertEquals(
            newSha256().digest(
                "https://dav.example.com/remote/\npersona".toByteArray(),
            ).toLowerHex().take(32),
            id,
        )
    }

    @Test
    fun theFolderComesFromTheAppNameCleanedForAPath() {
        assertEquals("Tienda", DestinationIdentity.folderName("Tienda"))
        assertEquals("Mi Tienda", DestinationIdentity.folderName("  Mi/ Tienda: \u0007"))
        assertEquals("Ñandú Café", DestinationIdentity.folderName("Ñandú\\ Café"))
        assertNull(DestinationIdentity.folderName(" /:\\ "))
        assertNull(DestinationIdentity.folderName(".."))
    }

    @Test
    fun nothingDependsOnTheLanguageOfThePhone() {
        val original = Locale.getDefault()
        try {
            val names = listOf("Tienda Ilusión", "İstanbul Pazarı", "Ijsselmeer")
            val expected = names.map(DestinationIdentity::folderName)
            val ids = listOf("Iñaki", "IRMA").map { id("https://dav.example.com/", it) }
            for (tag in listOf("tr-TR", "ar-EG", "de-DE", "ja-JP")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, expected, names.map(DestinationIdentity::folderName))
                assertEquals(
                    tag,
                    ids,
                    listOf("Iñaki", "IRMA").map {
                        id("https://dav.example.com/", it)
                    },
                )
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
