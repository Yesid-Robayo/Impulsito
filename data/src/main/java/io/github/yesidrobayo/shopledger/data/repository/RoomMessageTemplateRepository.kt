package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.customer.MessageTemplateEntity
import io.github.yesidrobayo.shopledger.domain.port.MessageTemplateRepository
import kotlin.time.Instant

/**
 * Las plantillas de mensajes sobre `message_templates`. Guardar crea la fila o cambia solo su texto,
 * sin `REPLACE`; restablecer la borra, porque sin fila vale el texto de los recursos.
 */
internal class RoomMessageTemplateRepository(database: AppDatabase) : MessageTemplateRepository {
    private val templates = database.messageTemplateDao()

    override suspend fun find(key: String, languageTag: String): String? =
        templates.find(key, languageTag)?.body

    override suspend fun save(key: String, languageTag: String, body: String, updatedAt: Instant) {
        val at = updatedAt.toStoredMillis()
        val current = templates.find(key, languageTag)
        if (current == null) {
            templates.insert(
                MessageTemplateEntity(
                    templateKey = key,
                    languageTag = languageTag,
                    body = body,
                    updatedAt = at,
                ),
            )
        } else {
            templates.updateBody(current.id, body, at)
        }
    }

    override suspend fun reset(key: String, languageTag: String) {
        templates.find(key, languageTag)?.let { templates.delete(it.id) }
    }
}
