package io.github.yesidrobayo.shopledger.domain.port

import kotlin.time.Instant

/**
 * Las plantillas de mensajes que la persona edita, una por clave e idioma. Sin plantilla guardada,
 * vale el texto de los recursos de ese idioma. Las escrituras solo se llaman dentro de
 * [TransactionRunner.write].
 */
interface MessageTemplateRepository {
    /** El texto guardado de [key] en [languageTag], o `null` si la persona no escribió uno. */
    suspend fun find(key: String, languageTag: String): String?

    /** Guarda [body] para [key] en [languageTag]: lo crea o cambia solo su texto. */
    suspend fun save(key: String, languageTag: String, body: String, updatedAt: Instant)

    /** Quita la plantilla guardada, para volver al texto de los recursos. */
    suspend fun reset(key: String, languageTag: String)
}
