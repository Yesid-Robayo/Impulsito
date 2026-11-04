package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una plantilla de mensaje que la persona edita, una por clave e idioma: el índice único de
 * `(template_key, language_tag)` lo asegura.
 *
 * - `template_key` es el nombre guardado de la plantilla, como `PAYMENT_REMINDER`.
 * - `language_tag` es la etiqueta del idioma de la plantilla, no un país.
 * - `body` es el texto de la plantilla, con sus marcadores.
 *
 * No tiene historia ni nada apunta a ella: se borra libremente.
 */
@Entity(
    tableName = "message_templates",
    indices = [Index(value = ["template_key", "language_tag"], unique = true)],
)
internal data class MessageTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "template_key") val templateKey: String,
    @ColumnInfo(name = "language_tag") val languageTag: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
