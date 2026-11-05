package io.github.yesidrobayo.shopledger.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El contenido del archivo de idioma: una etiqueta o `system`. Lo que no se entiende no es un
 * idioma, y quien lee lo trata como el del teléfono.
 */
class UiLanguageTest {
    @Test
    fun systemIsReadInAnyCase() {
        assertEquals(UiLanguage.System, UiLanguage.parse("system"))
        assertEquals(UiLanguage.System, UiLanguage.parse("SYSTEM"))
        assertEquals(UiLanguage.System, UiLanguage.parse("System\n"))
    }

    @Test
    fun anEmptyFileHoldsNoChoice() {
        assertEquals(UiLanguage.System, UiLanguage.parse(""))
        assertEquals(UiLanguage.System, UiLanguage.parse(" \n"))
    }

    @Test
    fun aTagIsKeptAsWritten() {
        assertEquals("es", (UiLanguage.parse("es") as UiLanguage.Chosen).tag)
        assertEquals("pt-BR", (UiLanguage.parse("pt-BR\n") as UiLanguage.Chosen).tag)
        assertEquals("es-419", (UiLanguage.parse("es-419") as UiLanguage.Chosen).tag)
        assertEquals("zh-Hant-TW", (UiLanguage.parse("zh-Hant-TW") as UiLanguage.Chosen).tag)
    }

    @Test
    fun aLanguageWithoutResourcesIsStillAccepted() {
        // Sin carpeta propia, Android lo muestra en inglés: la lista de idiomas no vive en el código.
        assertEquals("fr", (UiLanguage.parse("fr") as UiLanguage.Chosen).tag)
    }

    @Test
    fun spacesAndTheByteOrderMarkAroundTheTagAreIgnored() {
        val byteOrderMark = Char(0xFEFF)

        assertEquals("pt", (UiLanguage.parse("$byteOrderMark pt \r\n") as UiLanguage.Chosen).tag)
        assertEquals(UiLanguage.System, UiLanguage.parse("${byteOrderMark}system"))
    }

    @Test
    fun anythingThatIsNotATagIsRejected() {
        assertNull(UiLanguage.parse("e"))
        assertNull(UiLanguage.parse("es_AR"))
        assertNull(UiLanguage.parse("es AR"))
        assertNull(UiLanguage.parse("es-"))
        assertNull(UiLanguage.parse("-es"))
        assertNull(UiLanguage.parse("../es"))
        assertNull(UiLanguage.parse("es\npt"))
        assertNull(UiLanguage.parse("1a"))
        assertNull(UiLanguage.parse("abcdefghi"))
        assertNull(UiLanguage.parse("es-abcdefghi"))
    }

    @Test
    fun aTagLongerThanAnyRealOneIsRejected() {
        val atLimit = "es-abcdefgh-abcdefgh-abcdefgh-abcde"

        assertEquals(35, atLimit.length)
        assertEquals(atLimit, (UiLanguage.of(atLimit) as UiLanguage.Chosen).tag)
        assertNull(UiLanguage.of(atLimit + "e"))
    }

    @Test
    fun anEmptyTagFromAndroidFollowsThePhone() {
        assertEquals(UiLanguage.System, UiLanguage.of(""))
    }

    @Test
    fun theFileValueReadsBackAsTheSameLanguage() {
        val chosen = UiLanguage.of("pt-BR") as UiLanguage.Chosen

        assertEquals("system", UiLanguage.System.fileValue)
        assertEquals("pt-BR", chosen.fileValue)
        assertEquals(chosen, UiLanguage.parse(chosen.fileValue))
        assertEquals(UiLanguage.System, UiLanguage.parse(UiLanguage.System.fileValue))
    }
}
