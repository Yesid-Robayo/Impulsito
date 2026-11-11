package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant

/**
 * Una copia del destino, tal como la muestra la lista de la pantalla del respaldo: lo justo para
 * reconocerla, nunca su contenido.
 *
 * @property key una clave estable para la lista: la referencia de la copia en su destino.
 * @property createdAt cuándo se hizo la copia, o con ranuras cuándo se escribió; nulo si no se sabe.
 * @property sizeBytes lo que ocupa en el destino.
 * @property origin de qué teléfono salió.
 * @property slot con la nube del teléfono, la ranura donde está.
 * @property records cuántos registros trae, o nulo si sus metadatos no lo dicen: nunca un cero por
 *   falta del dato.
 * @property newest si es la más reciente de la lista.
 * @property pinned si está fijada: nunca se poda.
 * @property withDifferences si se hizo con diferencias, o su tamaño no es el esperado.
 * @property restorable si la app la podría restaurar. Una copia que el motor marcó como imposible de
 *   restaurar se ve, pero sin acción.
 */
data class BackupCopySummary(
    val key: String,
    val createdAt: Instant?,
    val sizeBytes: Long,
    val origin: CopyOrigin,
    val slot: BackupSlot?,
    val records: Long?,
    val newest: Boolean,
    val pinned: Boolean,
    val withDifferences: Boolean,
    val restorable: Boolean,
)

/** De qué teléfono salió una copia. */
enum class CopyOrigin {
    THIS_PHONE,
    OTHER_PHONE,

    /** La copia no dice de dónde salió: sus metadatos faltan o no se pudieron leer. */
    UNKNOWN,
}

/** La lista de copias del destino, o por qué no se pudo ver. */
sealed interface BackupCopyList {
    /** Las copias, de la más nueva a la más vieja. Vacía si el destino no tiene ninguna. */
    data class Listed(val copies: List<BackupCopySummary>) : BackupCopyList

    /** No hay destino conectado: no hay lista que ver. */
    data object NotConnected : BackupCopyList

    /** Con WebDAV, ver las copias pide internet, y no hay. */
    data object NoInternet : BackupCopyList

    /**
     * El destino no respondió o la lista no llegó entera. Nunca se muestra como una lista vacía:
     * que no se vean no significa que no existan.
     */
    data object Failed : BackupCopyList
}
