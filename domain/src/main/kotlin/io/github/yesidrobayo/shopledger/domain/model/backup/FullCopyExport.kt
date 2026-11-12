package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Cómo terminó `Guardar en un archivo`: una copia completa en un archivo que eligió la persona. Ninguno
 * cambia los datos, el estado del respaldo ni el destino conectado.
 */
sealed interface FullCopyExport {
    /**
     * El archivo quedó escrito y releído con el mismo tamaño y la misma suma.
     *
     * @property restorable si la app la podría restaurar. Una copia con un fallo grave se exporta
     *   igual, porque es la salida cuando todo lo demás falla, pero la pantalla lo dice.
     * @property sizeBytes lo que ocupa el archivo.
     */
    data class Saved(val restorable: Boolean, val sizeBytes: Long) : FullCopyExport

    /** No hubo espacio, en el teléfono para prepararla o en el lugar elegido. Nada quedó a medias. */
    data object NoSpace : FullCopyExport

    /** La base no pasó la revisión de integridad: una base dañada no se exporta. */
    data object Damaged : FullCopyExport

    /** Otra falla. El archivo elegido se borró si alcanzó a escribirse; los datos no cambiaron. */
    data object Failed : FullCopyExport
}

/**
 * El nombre que se sugiere al guardar una copia exportada: `shopledger-AAAAMMDD-HHmm.db.gz`, con la
 * hora del negocio, o `.slbk` si sale cifrada. No se traduce: es el mismo en cualquier idioma, como
 * el de las copias.
 */
object ExportFileName {
    private const val PREFIX = "shopledger-"
    private const val SUFFIX = ".db.gz"
    private const val ENCRYPTED_SUFFIX = ".slbk"

    /**
     * El nombre para una copia exportada en [at]. Sin zona del negocio, que solo falta antes de
     * configurar la región, la hora va en UTC: el nombre solo orienta, nunca decide nada.
     */
    fun of(at: Instant, zone: BusinessTimeZone?, encrypted: Boolean = false): String {
        val time = at.toLocalDateTime(zone?.timeZone ?: TimeZone.UTC)
        return buildString {
            append(PREFIX)
            append(time.year.toString().padStart(YEAR_DIGITS, '0'))
            append(time.month.number.toString().padStart(2, '0'))
            append(time.day.toString().padStart(2, '0'))
            append('-')
            append(time.hour.toString().padStart(2, '0'))
            append(time.minute.toString().padStart(2, '0'))
            append(if (encrypted) ENCRYPTED_SUFFIX else SUFFIX)
        }
    }

    private const val YEAR_DIGITS = 4
}
