package io.github.yesidrobayo.shopledger.data.destination.slots

import io.github.yesidrobayo.shopledger.data.destination.SHA256_HEX
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotContent
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotTable
import kotlin.time.Instant

/**
 * Lo que la app sabe de las tres ranuras: el contenido de `slots.json`.
 *
 * Los documentos de la nube del teléfono no tienen metadatos al lado ni se pueden listar, así que
 * esto es lo único que dice qué copia hay en cada uno. Por eso nunca se da por vacío: si falta o no
 * se lee, la app no escribe en ninguna ranura.
 *
 * @property slots las tres ranuras, siempre.
 * @property firstHandoffAt cuándo se entregó la primera copia, en milisegundos UTC, o nulo si
 *   todavía ninguna. Cuenta los días para revisar la nube mientras nadie lo hizo.
 * @property writeMode el modo con que se abren los documentos: `rwt` si el proveedor rechazó `wt`.
 * @property sessionRequired si la otra app pidió iniciar sesión: la salida es abrir esa app, no
 *   crear los documentos de nuevo.
 */
internal data class SlotState(
    val slots: Map<BackupSlot, SlotEntry>,
    val firstHandoffAt: Long?,
    val writeMode: WriteMode,
    val sessionRequired: Boolean,
) {
    init {
        require(slots.keys == BackupSlot.entries.toSet()) { "El estado lleva las tres ranuras" }
        val uris = slots.values.map { it.uri }
        require(uris.distinct().size == uris.size) {
            "Dos ranuras no pueden ser el mismo documento"
        }
    }

    operator fun get(slot: BackupSlot): SlotEntry = requireNotNull(slots[slot]) {
        "Falta una ranura"
    }

    /** El mismo estado con [entry] en [slot]. */
    fun with(slot: BackupSlot, entry: SlotEntry): SlotState = copy(slots = slots + (slot to entry))

    /** Lo que necesita la elección de ranura. */
    fun toTable(): SlotTable = SlotTable(
        get(BackupSlot.A).content(),
        get(BackupSlot.B).content(),
        get(BackupSlot.C).content(),
    )
}

/**
 * Una ranura: dónde está su documento y qué tiene.
 *
 * @property uri el documento, en texto. Es un dato, nunca va a un log.
 * @property providerLabel el nombre de la nube, sacado de la lista fija de proveedores, nunca del
 *   proveedor.
 * @property displayName el nombre visible del documento, texto no confiable que la persona puede
 *   cambiar.
 * @property copy la copia confirmada que guarda, o nula.
 * @property writing la marca de una escritura sin confirmar. Con ella la ranura está a medias, y
 *   [copy] es nula: abrir el documento ya pudo vaciarlo.
 */
internal data class SlotEntry(
    val uri: String,
    val providerLabel: String,
    val displayName: String?,
    val copy: SlotRecord?,
    val writing: WritingMark?,
) {
    init {
        require(uri.isNotEmpty()) { "Una ranura sin documento" }
        require(copy == null || writing == null) { "Una ranura a medias no guarda copia" }
    }

    /** El tamaño que debe tener el documento, o nulo si está a medias y no se sabe. */
    val expectedSize: Long?
        get() = when {
            writing != null -> null
            copy != null -> copy.sizeBytes
            else -> 0
        }

    fun content(): SlotContent = when {
        writing != null -> SlotContent.HalfWritten(writing.generation)
        copy != null -> SlotContent.Holding(copy.toSlotCopy())
        else -> SlotContent.Empty
    }
}

/**
 * Lo que dijo el validador de una copia, lo único que la elección de ranura necesita de ella. Es lo
 * mismo para una copia confirmada y para la marca de una escritura, que lo guarda antes de abrir:
 * si la escritura se confirma al recuperar, sale de ahí, nunca se supone.
 *
 * El esquema, el formato y la clave de la copia van en sus metadatos codificados, que se guardan
 * enteros junto a esto.
 *
 * @property valid si el validador la dejó restaurable sin reparar.
 * @property records cuántos registros del negocio tiene.
 * @property catalogCount cuántos productos tiene.
 */
internal data class SlotCopyFacts(val valid: Boolean, val records: Long, val catalogCount: Long) {
    init {
        require(records >= 0 && catalogCount >= 0) { "Conteos negativos" }
    }
}

/**
 * Una copia confirmada en una ranura.
 *
 * @property generation su generación.
 * @property writtenAt cuándo se creó, en milisegundos UTC, según el reloj del teléfono.
 * @property sizeBytes su tamaño, que el documento debe seguir teniendo.
 * @property sha256 su suma, en hexadecimal en minúscula.
 * @property facts lo que dijo de ella el validador.
 * @property metaBytes sus metadatos codificados, los mismos que llevaría al lado con WebDAV.
 */
internal class SlotRecord(
    val generation: Long,
    val writtenAt: Long,
    val sizeBytes: Long,
    val sha256: String,
    val facts: SlotCopyFacts,
    val metaBytes: ByteArray,
) {
    init {
        require(generation > 0) { "La generación de una copia en una ranura es positiva" }
        require(sizeBytes >= 0) { "El tamaño no puede ser negativo" }
        require(SHA256_HEX.matches(sha256)) { "La suma debe ser SHA-256 en hexadecimal" }
    }

    fun toSlotCopy(): SlotCopy = SlotCopy(
        generation = generation,
        writtenAt = Instant.fromEpochMilliseconds(writtenAt),
        valid = facts.valid,
        records = facts.records,
        catalogCount = facts.catalogCount,
    )

    override fun equals(other: Any?): Boolean = other is SlotRecord &&
        generation == other.generation && writtenAt == other.writtenAt &&
        sizeBytes == other.sizeBytes && sha256 == other.sha256 && facts == other.facts &&
        metaBytes.contentEquals(other.metaBytes)

    override fun hashCode(): Int = sha256.hashCode()

    override fun toString(): String = "SlotRecord(g$generation, $sizeBytes bytes)"
}

/**
 * La marca de una escritura que todavía no se confirmó.
 *
 * @property generation la generación que se estaba escribiendo.
 * @property meta los datos de ese archivo, o nulo si faltan: entonces la marca no describe ningún
 *   archivo y nunca se confirma con ella.
 */
internal data class WritingMark(val generation: Long, val meta: SlotRecord?) {
    init {
        require(generation > 0) { "La generación en escritura es positiva" }
        require(meta == null || meta.generation == generation) {
            "La marca y sus datos no coinciden"
        }
    }
}
