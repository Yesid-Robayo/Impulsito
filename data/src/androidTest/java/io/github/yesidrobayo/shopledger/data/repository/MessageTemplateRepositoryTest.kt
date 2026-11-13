package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La plantilla del recordatorio sobre la base real: una por idioma, guardar dos veces cambia la
 * misma fila sin `REPLACE`, y restablecer la quita para volver al texto de los recursos. Textos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class MessageTemplateRepositoryTest : RepositoryTest() {
    private val templates by lazy { RoomMessageTemplateRepository(database) }

    @Test
    fun eachLanguageKeepsItsOwnTemplateAndSavingAgainChangesTheSameRow() = runBlocking {
        assertNull(templates.find(KEY, "es"))

        commit { templates.save(KEY, "es", "Hola {customer}", AT) }
        commit { templates.save(KEY, "pt", "Olá {customer}", AT) }
        commit { templates.save(KEY, "es", "Buenas, {customer}", LATER) }

        assertEquals("Buenas, {customer}", templates.find(KEY, "es"))
        assertEquals("Olá {customer}", templates.find(KEY, "pt"))
        assertEquals(2, database.messageTemplateDao().observeAll().first().size)
    }

    @Test
    fun resettingRemovesOnlyThatLanguage() = runBlocking {
        commit { templates.save(KEY, "es", "Hola {customer}", AT) }
        commit { templates.save(KEY, "en", "Hello {customer}", AT) }

        commit { templates.reset(KEY, "es") }
        // Restablecer lo que no está guardado no hace nada.
        commit { templates.reset(KEY, "pt") }

        assertNull(templates.find(KEY, "es"))
        assertEquals("Hello {customer}", templates.find(KEY, "en"))
    }

    private companion object {
        const val KEY = "PAYMENT_REMINDER"
        val AT = Instant.parse("2026-11-13T12:00:00Z")
        val LATER = Instant.parse("2026-11-13T13:00:00Z")
    }
}
