package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.ids
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las plantillas de mensaje: una por clave e idioma, se encuentran por las dos, cambiar el texto
 * no toca nada más, y se borran sin historia.
 */
@RunWith(AndroidJUnit4::class)
internal class MessageTemplateDaoTest : CatalogDatabaseTest() {
    private val templates get() = database.messageTemplateDao()

    @Test
    fun aTemplateIsFoundByKeyAndLanguageAndOnlyItsBodyChanges() = runBlocking {
        val spanish = template("es")
        val spanishId = templates.insert(spanish)
        val portugueseId = templates.insert(template("pt-BR", body = "Olá {customer}"))

        assertEquals(spanish.copy(id = spanishId), templates.find("PAYMENT_REMINDER", "es"))
        assertNull(templates.find("PAYMENT_REMINDER", "en"))

        assertEquals(1, templates.updateBody(spanishId, "Hola {customer}, debes {balance}", LATER))
        assertEquals(
            spanish.copy(
                id = spanishId,
                body = "Hola {customer}, debes {balance}",
                updatedAt = LATER,
            ),
            templates.find("PAYMENT_REMINDER", "es"),
        )
        assertEquals("Olá {customer}", templates.find("PAYMENT_REMINDER", "pt-BR")?.body)
        assertEquals(portugueseId, templates.find("PAYMENT_REMINDER", "pt-BR")?.id)
    }

    @Test
    fun theListIsInCreationOrderAndADeletedTemplateLeavesIt() = runBlocking {
        val portugueseId = templates.insert(template("pt-BR"))
        val spanishId = templates.insert(template("es"))

        val emissions = Emissions(this, templates.observeAll().ids { it.id })
        try {
            emissions.awaitValue(listOf(portugueseId, spanishId))

            assertEquals(1, templates.delete(portugueseId))
            emissions.awaitValue(listOf(spanishId))
        } finally {
            emissions.stop()
        }
        assertEquals(0, templates.delete(portugueseId))
    }
}
