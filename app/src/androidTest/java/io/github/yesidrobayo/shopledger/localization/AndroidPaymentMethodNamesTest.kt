package io.github.yesidrobayo.shopledger.localization

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El nombre que una venta congela al cobrar con un método inicial que nadie renombró: el de su
 * clave, en el idioma de la interfaz. Los textos esperados son los de la guía de redacción,
 * escritos a mano.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPaymentMethodNamesTest {
    private fun namesIn(tag: String) = AndroidPaymentMethodNames { resourcesIn(tag) }

    @Test
    fun spanishNamesTheInitialMethods() {
        val names = namesIn("es")
        assertEquals("Efectivo", names.systemName("CASH"))
        assertEquals("A crédito", names.systemName("ON_ACCOUNT"))
    }

    @Test
    fun englishNamesTheInitialMethods() {
        val names = namesIn("en")
        assertEquals("Cash", names.systemName("CASH"))
        assertEquals("On credit", names.systemName("ON_ACCOUNT"))
    }

    @Test
    fun portugueseNamesTheInitialMethods() {
        val names = namesIn("pt")
        assertEquals("Dinheiro", names.systemName("CASH"))
        assertEquals("A prazo", names.systemName("ON_ACCOUNT"))
    }

    @Test
    fun everyInitialNameFitsTheFrozenSnapshot() {
        for (tag in listOf("es", "en", "pt")) {
            for (key in listOf("CASH", "CARD", "TRANSFER", "WALLET", "ON_ACCOUNT", "OTHER")) {
                val name = namesIn(tag).systemName(key)
                assertEquals("$tag $key", true, name.length in 1..MAX_SNAPSHOT_LENGTH)
            }
        }
    }

    @Test
    fun aKeyThisVersionDoesNotKnowIsKeptAsItIs() {
        assertEquals("FUTURE_KEY", namesIn("es").systemName("FUTURE_KEY"))
    }

    private companion object {
        /** El largo máximo del nombre congelado en un pago. */
        const val MAX_SNAPSHOT_LENGTH = 40
    }
}
