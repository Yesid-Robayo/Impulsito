package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.FlatJson
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Lo que guardaba la base al hacerse una copia de seguridad.
 *
 * @property changeSeq y [verifiedSeq] los contadores de cambios y de lo confirmado en el destino.
 *   Si el primero es mayor, la copia guarda cambios que no llegaron al destino.
 * @property createdAt cuándo se hizo, por el reloj del teléfono.
 */
internal data class SafetyCopyFacts(
    val changeSeq: Long,
    val verifiedSeq: Long,
    val createdAt: Instant,
) {
    /** Si la copia guarda cambios que no llegaron al destino: es el único respaldo de ellos. */
    val hasUnsyncedChanges: Boolean get() = changeSeq > verifiedSeq
}

/** Una copia de seguridad que se puede ofrecer para deshacer una restauración. */
internal data class SafetyCopy(val files: SafetyCopyFiles, val facts: SafetyCopyFacts?)

/** Lo que dio guardar la copia de seguridad del paso previo al reemplazo. */
internal sealed interface SafetyCopyResult {
    /** La copia quedó entera y comprobada como la más reciente. */
    data object Saved : SafetyCopyResult

    /** No hay base activa, así que no hay nada que proteger. */
    data object NoActiveDatabase : SafetyCopyResult

    /**
     * La base activa está dañada y no se pudo copiar. Si la persona confirma, la restauración sigue
     * sin copia, y la base original se aparta 30 días en vez de borrarse.
     */
    data object ActiveDatabaseDamaged : SafetyCopyResult

    /**
     * Las dos copias guardan cambios que no llegaron al destino. La app nunca descarta una sola: la
     * persona elige cuál, y después se vuelve a pedir la copia.
     */
    data class NeedsChoice(val copies: List<SafetyCopy>) : SafetyCopyResult

    /** No hubo espacio para la copia. Nada cambió. */
    data object StorageFull : SafetyCopyResult

    /** Otra falla del disco. Nada cambió. Solo la clase de [cause] puede ir al registro. */
    class Failed(val cause: Exception) : SafetyCopyResult
}

/**
 * Las copias locales de seguridad de la base activa, que se hacen justo antes de reemplazarla y
 * permiten deshacer la restauración durante 30 días. Nunca se suben.
 *
 * - **Hecha y comprobada antes de tener nombre.** `VACUUM INTO` escribe un temporal, se le pasa
 *   `integrity_check` y solo entonces se renombra. Una muerte a mitad deja un temporal, nunca una
 *   copia a medias con nombre final.
 * - **Nunca destruye el único respaldo de algo.** Si la copia actual guarda cambios que no llegaron
 *   al destino, pasa a ser la anterior en vez de reemplazarse: una segunda restauración no puede
 *   borrar el único respaldo del estado original. Se conservan como máximo dos, y si las dos guardan
 *   cambios sin subir, la persona elige cuál descartar.
 * - **30 días.** Pasado ese tiempo, la limpieza diaria las borra. Un reloj atrasado nunca borra una:
 *   una copia del futuro no está vencida.
 *
 * @param snapshotOf hace la instantánea de la base activa en un archivo.
 */
internal class SafetyCopyStore(
    private val files: RestoreFiles,
    private val ops: DurableFileOps,
    private val ioContext: CoroutineContext,
    private val snapshotOf: SnapshotCreator = SnapshotCreator(files.database, ioContext),
) {
    private val newest = files.safetyCopies[0]
    private val older = files.safetyCopies[1]

    /**
     * Guarda la copia de la base activa, que tiene [changeSeq] cambios y [verifiedSeq] confirmados,
     * con el instante [now].
     */
    suspend fun save(changeSeq: Long, verifiedSeq: Long, now: Instant): SafetyCopyResult {
        if (!files.database.exists()) return SafetyCopyResult.NoActiveDatabase
        val current = withContext(ioContext) { copies() }
        val newestCopy = current.firstOrNull { it.files === newest }
        val olderCopy = current.firstOrNull { it.files === older }
        val keepNewest = newestCopy != null && newestCopy.keepsUnsyncedChanges()
        if (keepNewest && olderCopy != null && olderCopy.keepsUnsyncedChanges()) {
            return SafetyCopyResult.NeedsChoice(current)
        }
        val temporary = files.safetyCopyTemporary
        return when (val snapshot = snapshotOf.create(temporary)) {
            SnapshotResult.DatabaseMissing -> SafetyCopyResult.NoActiveDatabase

            SnapshotResult.Corrupt -> SafetyCopyResult.ActiveDatabaseDamaged

            SnapshotResult.StorageFull -> SafetyCopyResult.StorageFull

            is SnapshotResult.Failed -> SafetyCopyResult.Failed(snapshot.cause)

            is SnapshotResult.Created -> when (
                val integrity = snapshotOf.checkIntegrity(
                    temporary,
                )
            ) {
                IntegrityResult.Intact -> withContext(ioContext) {
                    install(keepNewest, SafetyCopyFacts(changeSeq, verifiedSeq, now))
                }

                IntegrityResult.Damaged -> {
                    withContext(ioContext) { temporary.delete() }
                    SafetyCopyResult.ActiveDatabaseDamaged
                }

                is IntegrityResult.Failed -> {
                    withContext(ioContext) { temporary.delete() }
                    SafetyCopyResult.Failed(integrity.cause)
                }
            }
        }
    }

    /** Las copias que hay, de la más reciente a la anterior. Lee el disco. */
    fun copies(): List<SafetyCopy> = files.safetyCopies
        .filter { it.database.isFile }
        .map { SafetyCopy(it, readFacts(it)) }

    /** Las copias que se pueden ofrecer para deshacer en [now]: las de menos de 30 días. */
    fun available(now: Instant): List<SafetyCopy> = copies().filterNot { it.isExpired(now) }

    /** Descarta [copy], que eligió la persona. */
    suspend fun discard(copy: SafetyCopy): Unit = withContext(ioContext) {
        ops.delete(copy.files.database)
        ops.delete(copy.files.facts)
    }

    /** Borra las copias vencidas en [now]. La llama la limpieza diaria. */
    suspend fun pruneExpired(now: Instant): Unit = withContext(ioContext) {
        for (copy in copies()) if (copy.isExpired(now)) discard(copy)
    }

    private fun install(keepNewest: Boolean, facts: SafetyCopyFacts): SafetyCopyResult = try {
        if (keepNewest) {
            ops.delete(older.facts)
            ops.move(newest.database, older.database, replace = true)
            ops.move(newest.facts, older.facts, replace = true)
        }
        // Primero sale lo que decía la copia vieja: una base nueva nunca queda con datos ajenos.
        ops.delete(newest.facts)
        ops.move(files.safetyCopyTemporary, newest.database, replace = true)
        ops.writeAtomically(newest.facts, newest.factsTemporary, encode(facts))
        SafetyCopyResult.Saved
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: IOException) {
        files.safetyCopyTemporary.delete()
        SafetyCopyResult.Failed(failure)
    }

    /**
     * Si conservarla importa: guarda cambios sin subir, o no se sabe porque sus datos no se leen. En
     * la duda, una copia se trata como el único respaldo de algo.
     */
    private fun SafetyCopy.keepsUnsyncedChanges(): Boolean = facts?.hasUnsyncedChanges ?: true

    private fun SafetyCopy.isExpired(now: Instant): Boolean {
        val createdAt =
            facts?.createdAt ?: Instant.fromEpochMilliseconds(files.database.lastModified())
        val age = now - createdAt
        return !age.isNegative() && age >= RETENTION
    }

    private fun readFacts(copy: SafetyCopyFiles): SafetyCopyFacts? {
        val text = try {
            if (!copy.facts.isFile || copy.facts.length() > MAX_FACTS_BYTES) return null
            copy.facts.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return null
        }
        val members = FlatJson.parse(text) ?: return null
        if (members.keys != KEYS) return null
        fun long(key: String): Long? = (members[key] as? FlatJson.Value.Number)?.text
            ?.takeIf { DECIMAL.matches(it) }?.toLongOrNull()
        val changeSeq = long(CHANGE_SEQ) ?: return null
        val verifiedSeq = long(VERIFIED_SEQ) ?: return null
        val createdAt = long(CREATED_AT) ?: return null
        return SafetyCopyFacts(changeSeq, verifiedSeq, Instant.fromEpochMilliseconds(createdAt))
    }

    private fun encode(facts: SafetyCopyFacts): ByteArray = FlatJson.write(
        listOf(
            CHANGE_SEQ to FlatJson.Value.Number(facts.changeSeq.toString()),
            VERIFIED_SEQ to FlatJson.Value.Number(facts.verifiedSeq.toString()),
            CREATED_AT to FlatJson.Value.Number(facts.createdAt.toEpochMilliseconds().toString()),
        ),
    ).toByteArray(Charsets.UTF_8)

    companion object {
        /** Cuánto se ofrece deshacer una restauración. */
        val RETENTION = 30.days

        private const val CHANGE_SEQ = "change_seq"
        private const val VERIFIED_SEQ = "verified_seq"
        private const val CREATED_AT = "created_at"
        private val KEYS = setOf(CHANGE_SEQ, VERIFIED_SEQ, CREATED_AT)
        private const val MAX_FACTS_BYTES = 1024L
        private val DECIMAL = Regex("0|[1-9][0-9]*")
    }
}

/**
 * Prepara deshacer una restauración con [copy]: la aparta como origen del deshacer y deja una copia
 * de ella como candidata, sin consumirla. El origen vuelve a su nombre si la preparación se cancela
 * o muere, o si el reemplazo se revierte, y solo se borra cuando el reemplazo queda verificado.
 */
internal fun prepareUndo(files: RestoreFiles, ops: DurableFileOps, copy: SafetyCopy) {
    ops.move(copy.files.database, files.undoSource.database)
    ops.copyAtomically(files.undoSource.database, files.candidate, files.candidateTemporary)
}

/** Cancela un deshacer: borra la candidata y devuelve el origen a su nombre. */
internal fun cancelUndo(files: RestoreFiles, ops: DurableFileOps) {
    ops.delete(files.candidateTemporary)
    ops.delete(files.candidate)
    returnUndoSource(files, ops)
}

/**
 * Devuelve el origen de un deshacer a su nombre: el de la copia de seguridad que conserva sus datos
 * y perdió su base. Si las dos copias están en su sitio, el origen se queda donde está, sin pisar
 * ninguna.
 */
internal fun returnUndoSource(files: RestoreFiles, ops: DurableFileOps) {
    if (!files.undoSource.database.exists()) return
    val slot = files.safetyCopies.firstOrNull { it.facts.exists() && !it.database.exists() }
        ?: files.safetyCopies.firstOrNull { !it.database.exists() }
        ?: return
    ops.move(files.undoSource.database, slot.database)
}
