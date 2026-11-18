package io.github.yesidrobayo.shopledger.ui.phrase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El idioma que se le pide al servicio de voz lleva siempre país: el de la región del negocio si es
 * del mismo idioma, y si no, el de respaldo de ese idioma.
 */
class SpeechLanguageTest {
    @Test
    fun theInstalledLanguageWithTheSameCountryIsKept() {
        assertEquals("es-CO", SpeechLanguage.pick("es-CO", listOf("es-US", "es-CO"), emptyList()))
    }

    @Test
    fun anInstalledLanguageWithAnotherCountryReplacesTheMissingOne() {
        // El caso del teléfono real: español descargado como es-US, sin es-CO.
        assertEquals("es-US", SpeechLanguage.pick("es-CO", listOf("en-US", "es-US"), emptyList()))
        assertEquals("es_US", SpeechLanguage.pick("es-CO", listOf("es_US"), emptyList()))
    }

    @Test
    fun theInstalledLanguageGoesBeforeTheOnlineOne() {
        assertEquals("es-ES", SpeechLanguage.pick("es-CO", listOf("es-ES"), listOf("es-CO")))
    }

    @Test
    fun withoutInstalledLanguageTheOnlineOneIsUsed() {
        assertEquals(
            "es-CO",
            SpeechLanguage.pick("es-CO", listOf("en-US"), listOf("es-MX", "es-CO")),
        )
        assertEquals("es-MX", SpeechLanguage.pick("es-CO", emptyList(), listOf("en-US", "es-MX")))
    }

    @Test
    fun withoutTheLanguageTheWantedTagIsAskedAsIs() {
        assertEquals(
            "pt-BR",
            SpeechLanguage.pick("pt-BR", listOf("en-US", "es-US"), listOf("en-GB")),
        )
        assertEquals("es-CO", SpeechLanguage.pick("es-CO", emptyList(), emptyList()))
    }

    @Test
    fun theBusinessRegionOfTheSameLanguageGivesTheCountry() {
        assertEquals("es-CO", SpeechLanguage.tagFor("es", "es-CO"))
        assertEquals("en-GB", SpeechLanguage.tagFor("en", "en-GB"))
    }

    @Test
    fun withoutCountryTheLanguageFallbackIsUsed() {
        assertEquals("es-US", SpeechLanguage.tagFor("es", "es"))
        assertEquals("en-US", SpeechLanguage.tagFor("en", "en"))
        assertEquals("pt-BR", SpeechLanguage.tagFor("pt", "pt"))
    }

    @Test
    fun aRegionOfAnotherLanguageIsNotUsed() {
        assertEquals("pt-BR", SpeechLanguage.tagFor("pt", "es-CO"))
        assertEquals("en-US", SpeechLanguage.tagFor("en", "es-CO"))
    }

    @Test
    fun aNumericRegionOrAScriptIsNotACountry() {
        assertEquals("es-US", SpeechLanguage.tagFor("es", "es-419"))
        assertEquals("pt-BR", SpeechLanguage.tagFor("pt", "pt-Latn"))
        assertEquals("pt-PT", SpeechLanguage.tagFor("pt", "pt-Latn-PT"))
    }
}
