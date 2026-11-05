package io.github.yesidrobayo.shopledger.data.seed

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals

// Apoyo de las pruebas del generador de datos. Todo lo que generan es inventado.

/**
 * La configuración del generador para una fila de la matriz, o `null` en la fila que toma la
 * región del teléfono y no tiene moneda confirmada: con ella no hay negocio que inventar.
 */
internal fun seedConfigOf(row: RegionalMatrixRow): SeedConfig? {
    val region = row.region ?: return null
    if (!row.currencyCode.isConfirmed) return null
    return SeedConfig(
        currencyCode = row.currencyCode,
        currencyDecimals = row.currencyDecimals,
        formatLocaleTag = region.formatLocaleTag,
        businessTimeZoneId = region.businessTimeZoneId,
        weekStartDay = region.weekStartDay,
        csvSeparator = region.csvSeparator,
        language = seedLanguageOf(row.uiLanguageTag),
    )
}

/** El idioma de los nombres inventados para el idioma de la interfaz de una fila. */
internal fun seedLanguageOf(tag: String?): SeedLanguage = when (tag) {
    "es" -> SeedLanguage.SPANISH
    "pt" -> SeedLanguage.PORTUGUESE
    else -> SeedLanguage.ENGLISH
}

/** La fila de la matriz con coma decimal y la interfaz en español: el euro con dos decimales. */
internal val SPANISH_ROW: RegionalMatrixRow = RegionalMatrix.ROWS[3]

/** Falla con el nombre de cada comprobación que no dio cero, y cuántas filas rompió. */
internal fun assertInvariantsHold(case: String, report: SeedReport) {
    val broken = report.invariantFailures.filterValues { it != 0L }
    assertEquals("$case: invariantes rotos", emptyMap<String, Long>(), broken)
}

/**
 * Una huella de todas las filas de la base en [file], tabla por tabla y en el orden de sus ids,
 * con el tipo y el valor de cada columna. Dos bases con la misma huella tienen las mismas filas.
 */
internal fun rowDigest(file: File): String = inspectWithoutRoom(file) { connection ->
    val digest = MessageDigest.getInstance("SHA-256")
    val tables =
        connection.texts(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
        )
    for (table in tables) {
        digest.update(table.toByteArray())
        connection.prepare("SELECT * FROM \"$table\" ORDER BY rowid").use { statement ->
            while (statement.step()) {
                for (column in 0 until statement.getColumnCount()) {
                    val type = statement.getColumnType(column)
                    digest.update(type.toByte())
                    val value =
                        when (type) {
                            SQLITE_DATA_INTEGER -> statement.getLong(column).toString()
                            SQLITE_DATA_FLOAT -> statement.getDouble(column).toString()
                            SQLITE_DATA_TEXT -> statement.getText(column)
                            SQLITE_DATA_BLOB -> statement.getBlob(column).contentToString()
                            SQLITE_DATA_NULL -> ""
                            else -> error("Tipo de columna desconocido")
                        }
                    digest.update(value.toByteArray())
                    digest.update(0)
                }
            }
        }
    }
    hex(digest.digest())
}

/** La huella del archivo entero, byte por byte. */
internal fun fileDigest(file: File): String =
    hex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

/** Hexadecimal sin pasar por el formato del idioma: una prueba cambia el idioma del proceso. */
private fun hex(bytes: ByteArray): String = buildString {
    for (byte in bytes) {
        val value = byte.toInt() and BYTE_MASK
        append(Character.forDigit(value shr NIBBLE_BITS, HEX_RADIX))
        append(Character.forDigit(value and NIBBLE_MASK, HEX_RADIX))
    }
}

private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
private const val NIBBLE_BITS = 4
private const val HEX_RADIX = 16
