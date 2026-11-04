package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `message_templates`. Sin `REPLACE`: guardar otra vez la misma clave e
 * idioma falla por su índice único, y se cambia con [updateBody].
 */
@Dao
internal interface MessageTemplateDao {
    /** Todas, en el orden en que se crearon. */
    @Query("SELECT * FROM message_templates ORDER BY id")
    fun observeAll(): Flow<List<MessageTemplateEntity>>

    /** La plantilla de esa clave en ese idioma, si la persona escribió una. */
    @Query(
        "SELECT * FROM message_templates WHERE template_key = :templateKey " +
            "AND language_tag = :languageTag",
    )
    suspend fun find(templateKey: String, languageTag: String): MessageTemplateEntity?

    /** Devuelve el id nuevo. Falla si ya hay una de esa clave en ese idioma. */
    @Insert
    suspend fun insert(template: MessageTemplateEntity): Long

    /** Cambia solo el texto. Devuelve las filas cambiadas. */
    @Query("UPDATE message_templates SET body = :body, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateBody(id: Long, body: String, updatedAt: Long): Int

    /** Devuelve las filas borradas. */
    @Query("DELETE FROM message_templates WHERE id = :id")
    suspend fun delete(id: Long): Int
}
