package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Lo que las pantallas dicen del respaldo de las fotos, aparte del estado de la copia: las fotos
 * nunca entran en la copia, y nada de esto cambia `BackupStatus`, `change_seq` ni `verified_seq`.
 *
 * @property destination adónde podrían ir las fotos. Solo WebDAV las lleva.
 * @property pending fotos en uso, con su archivo en el teléfono, que el servicio todavía no tiene
 *   verificadas en ninguna forma. Solo tiene sentido con WebDAV.
 * @property pendingOverADay de esas, las que llevan más de un día, contado desde que se guardó la
 *   foto. Inicio las repite en una línea aparte.
 * @property stoppedForSpace si `media-sync` se detuvo porque en el servicio caben menos de 3 copias.
 * @property totalBytes lo que pesan las fotos en uso, para el aviso de 1 GiB.
 * @property exportable fotos en uso con su archivo en el teléfono: las que `Exportar fotos` lleva.
 */
data class PhotoBackupFacts(
    val destination: PhotoDestination,
    val pending: Int,
    val pendingOverADay: Int,
    val stoppedForSpace: Boolean,
    val totalBytes: Long,
    val exportable: Int,
) {
    /** Si las fotos llegan a 1 GiB: un aviso que nunca bloquea. */
    val overOneGiB: Boolean get() = totalBytes >= ONE_GIB

    /** Si Inicio muestra la línea de fotos pendientes: solo con WebDAV y más de un día. */
    val showsHomeLine: Boolean
        get() = destination == PhotoDestination.WEBDAV && pendingOverADay > 0

    companion object {
        /** 1 GiB, el peso de fotos que pide un aviso. */
        const val ONE_GIB: Long = 1024L * 1024 * 1024
    }
}

/** Adónde podrían ir las fotos, según el destino conectado. */
enum class PhotoDestination {
    /** WebDAV: `media-sync` las sube aparte de la copia. */
    WEBDAV,

    /** Ranuras: tres archivos fijos no sirven para fotos. No salen del teléfono. */
    SLOTS,

    /** Sin destino conectado. */
    NONE,
}

/** Cómo terminó `Exportar fotos`. Ninguno cambia los datos ni el estado del respaldo. */
sealed interface PhotoExport {
    /** El archivo quedó escrito y releído igual, con [count] fotos. */
    data class Saved(val count: Int) : PhotoExport

    /** No hay fotos en el teléfono para exportar. */
    data object NoPhotos : PhotoExport

    /** El cifrado está activo y falta su clave: nada sale en claro. */
    data object KeyUnavailable : PhotoExport

    /** Otra falla. El archivo elegido se borró si alcanzó a escribirse. */
    data object Failed : PhotoExport
}

/**
 * El nombre que se sugiere al exportar las fotos: `media-AAAAMMDD.zip`, con la fecha del negocio. No
 * se traduce, como el de las copias.
 */
object PhotoExportFileName {
    private const val PREFIX = "media-"
    private const val SUFFIX = ".zip"
    private const val YEAR_DIGITS = 4

    /** El nombre para [at]. Sin zona del negocio, la fecha va en UTC: el nombre solo orienta. */
    fun of(at: Instant, zone: BusinessTimeZone?): String {
        val date = at.toLocalDateTime(zone?.timeZone ?: TimeZone.UTC)
        return buildString {
            append(PREFIX)
            append(date.year.toString().padStart(YEAR_DIGITS, '0'))
            append(date.month.number.toString().padStart(2, '0'))
            append(date.day.toString().padStart(2, '0'))
            append(SUFFIX)
        }
    }
}
