package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationProblem
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.backup.SecondDestinationRule
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Escribe en la carpeta del segundo destino, con o sin destino conectado. Quien llama tiene el
 * cerrojo del respaldo: el trabajo del segundo destino lo toma entero, de generar la copia a podar,
 * y el paso 9b de la copia principal ya lo tiene.
 *
 * 1. Anota en `second-destination.json` el nombre que va a escribir.
 * 2. Crea `<nombre>.tmp` en la carpeta, lo escribe, lo sincroniza y lo relee: tamaño y SHA-256 deben
 *    coincidir. Si no, lo borra.
 * 3. Lo renombra al nombre final y solo entonces anota `last_copy_at`, `last_copy_seq` y la copia.
 * 4. Poda sus propias copias: 3, o 5 como máximo con `Mantener al día esta carpeta`.
 *
 * **Nunca queda una copia parcial con nombre final:** el nombre final llega por un renombrado de un
 * archivo ya releído. Tras una muerte del proceso, la ejecución siguiente borra el temporal que dejó,
 * y adopta la copia ya renombrada que no alcanzó a anotar.
 *
 * **Nunca toca un archivo ajeno:** solo borra lo que anotó como suyo, por nombre exacto.
 *
 * **Un problema aquí no toca el respaldo:** no escribe `failure_code` ni cambia el estado; queda en
 * `second-destination.json` y se muestra aparte.
 *
 * @param store `second-destination.json`.
 * @param folderFor la carpeta guardada.
 * @param build arma la copia propia en la carpeta dada, con [LocalCopyBuilder].
 * @param staging la carpeta de preparación, `backup-staging/second/`.
 * @param state la fila del estado del respaldo, para `change_seq` y la cabeza.
 * @param encryption el cifrado de las copias en este teléfono.
 * @param deviceId el identificador de este teléfono, para el nombre de la copia.
 * @param listener se entera de cada paso terminado: las pruebas matan ahí el proceso.
 */
internal class SecondDestinationWriter(
    private val store: SecondDestinationStore,
    private val folderFor: (SecondDestinationState) -> SecondDestinationFolder,
    private val build: suspend (File) -> LocalCopy,
    private val staging: () -> File,
    private val state: suspend () -> BackupStateEntity?,
    private val encryption: suspend () -> LocalEncryption,
    private val schemaVersion: Int,
    private val deviceId: suspend () -> String,
    private val clock: Clock,
    private val ioContext: CoroutineContext,
    private val listener: OperationListener = OperationListener.NONE,
) {
    /**
     * La copia propia del segundo destino: la genera con los pasos del respaldo y la escribe, si hay
     * carpeta y hubo cambios desde la última. Con cualquier `link_state`.
     */
    suspend fun writeOwnCopy(): SecondDestinationRun {
        val saved = loaded() ?: return SecondDestinationRun.NoFolder
        if (blockedByEncryption()) return problem(saved, SecondDestinationProblem.KEY_UNAVAILABLE)
        val row = state() ?: return SecondDestinationRun.Retry
        if (saved.lastCopySeq == row.changeSeq) return SecondDestinationRun.NoChanges
        val directory = withContext(ioContext) { staging().also(::emptyDirectory) }
        try {
            val copy =
                when (val built = build(directory)) {
                    is LocalCopy.Built -> built

                    LocalCopy.Damaged ->
                        return problem(saved, SecondDestinationProblem.FATAL_SKIPPED)

                    LocalCopy.NoSpace -> return problem(saved, SecondDestinationProblem.NO_SPACE)

                    LocalCopy.TooLarge, LocalCopy.Failed ->
                        return problem(saved, SecondDestinationProblem.WRITE_FAILED)
                }
            // Una base sin nada del negocio no se copia: una carpeta llena de copias vacías no sirve.
            if (copy.records == 0L && copy.catalogCount == 0L) return SecondDestinationRun.Empty
            // Una copia que no se podría restaurar no se escribe ni poda: las anteriores siguen.
            if (!copy.restorable) return problem(saved, SecondDestinationProblem.FATAL_SKIPPED)
            return write(
                saved,
                copy.file,
                copy.sizeBytes,
                copy.sha256,
                copy.changeSeq,
                row,
                copy.encrypted,
            )
        } finally {
            withContext(NonCancellable + ioContext) { emptyDirectory(directory) }
        }
    }

    /**
     * El paso 9b: con `Mantener al día esta carpeta`, el archivo final de una copia principal que no
     * es fatal también va a la carpeta, sin armar otra copia. Un fallo aquí nunca detiene la subida.
     */
    suspend fun writeMainCopy(
        file: File,
        sizeBytes: Long,
        sha256: String,
        changeSeq: Long,
        encrypted: Boolean = false,
    ): SecondDestinationRun {
        val saved = loaded() ?: return SecondDestinationRun.NoFolder
        if (!saved.followMainBackup) return SecondDestinationRun.NoFolder
        if (blockedByEncryption()) return problem(saved, SecondDestinationProblem.KEY_UNAVAILABLE)
        val row = state() ?: return SecondDestinationRun.Retry
        return write(saved, file, sizeBytes, sha256, changeSeq, row, encrypted)
    }

    private suspend fun write(
        saved: SecondDestinationState,
        source: File,
        sizeBytes: Long,
        sha256: String,
        changeSeq: Long,
        row: BackupStateEntity,
        encrypted: Boolean,
    ): SecondDestinationRun {
        val now = clock.now()
        val createdAt =
            CopyInstant.of(now) ?: return problem(saved, SecondDestinationProblem.WRITE_FAILED)
        val name =
            BackupFileName(
                generation = row.headGeneration,
                schemaVersion = schemaVersion,
                createdAt = createdAt,
                devicePrefix = deviceId().take(DEVICE_PREFIX_LENGTH),
                // El nombre dice el formato de los bytes: con el cifrado activo, la copia cifrada.
                format = if (encrypted) BackupFileFormat.ENCRYPTED else BackupFileFormat.GZIP,
            ).fileName
        val temporary = name + TEMPORARY_SUFFIX
        val folder = folderFor(saved)
        return try {
            withContext(ioContext) {
                var current = recover(saved, folder)
                current = current.copy(pending = name)
                store.save(current)
                listener.afterOperation(PENDING)
                folder.create(temporary)
                listener.afterOperation(CREATED)
                FileInputStream(source).use { folder.write(temporary, it) }
                listener.afterOperation(WRITTEN)
                val (readSize, readSha) = folder.read(temporary).use(::measure)
                if (readSize != sizeBytes || readSha != sha256) {
                    folder.delete(temporary)
                    return@withContext problem(current, SecondDestinationProblem.WRITE_FAILED)
                }
                if (!folder.rename(temporary, name)) {
                    folder.delete(temporary)
                    return@withContext problem(current, SecondDestinationProblem.WRITE_FAILED)
                }
                listener.afterOperation(RENAMED)
                // Con una copia nueva verificada, las adoptadas entran en la poda de siempre.
                current =
                    current.copy(
                        lastCopyAt = now.toEpochMilliseconds(),
                        lastCopySeq = changeSeq,
                        pending = null,
                        owned = current.owned + current.adopted + name,
                        adopted = emptySet(),
                        problem = null,
                    )
                store.save(current)
                listener.afterOperation(RECORDED)
                prune(current, folder)
                SecondDestinationRun.Written
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            problem(saved, problemOf(failure))
        }
    }

    /**
     * Lo que dejó una ejecución cortada: el temporal del nombre anotado se borra, y una copia con
     * ese nombre que ya existe se adopta, porque solo se renombra un archivo releído. Nada más.
     */
    private suspend fun recover(
        saved: SecondDestinationState,
        folder: SecondDestinationFolder,
    ): SecondDestinationState {
        val pending = saved.pending ?: return saved
        val present = folder.list()
        if (pending + TEMPORARY_SUFFIX in present) folder.delete(pending + TEMPORARY_SUFFIX)
        val recovered =
            if (pending in present) {
                saved.copy(pending = null, owned = saved.owned + pending)
            } else {
                saved.copy(pending = null)
            }
        store.save(recovered)
        return recovered
    }

    /** Borra las copias propias que sobran. Una que no se pudo borrar queda anotada y se reintenta. */
    private suspend fun prune(current: SecondDestinationState, folder: SecondDestinationFolder) {
        val present = folder.list().keys
        val mine =
            current.owned.filter { it in present }.mapNotNull { name ->
                BackupFileName.parse(name)?.let { name to it.createdAt }
            }.toMap()
        val doomed = SecondDestinationRule.toPrune(mine, current.followMainBackup)
        if (doomed.isEmpty() && current.owned.all { it in present }) return
        val removed = doomed.filter { folder.delete(it) }.toSet()
        listener.afterOperation(PRUNED)
        // Las anotadas que ya no están en la carpeta se olvidan: alguien las movió o borró.
        store.save(current.copy(owned = current.owned.filter { it in present }.toSet() - removed))
    }

    private suspend fun loaded(): SecondDestinationState? =
        (store.load() as? SecondDestinationLoad.Loaded)?.state

    private suspend fun blockedByEncryption(): Boolean {
        val local = encryption()
        return local.active && !local.keyAvailable
    }

    /** Anota [problem] sin tocar nada más. Si ni eso se puede, el problema igual se devuelve. */
    private suspend fun problem(
        saved: SecondDestinationState,
        problem: SecondDestinationProblem,
    ): SecondDestinationRun {
        try {
            withContext(NonCancellable + ioContext) {
                val latest = loaded() ?: saved
                store.save(latest.copy(problem = problem))
            }
        } catch (_: IOException) {
            Unit
        }
        return SecondDestinationRun.Problem(problem)
    }

    private fun problemOf(failure: Exception): SecondDestinationProblem = when (failure) {
        is FolderMissingException -> SecondDestinationProblem.FOLDER_MISSING

        is SecurityException -> SecondDestinationProblem.PERMISSION_LOST

        is IOException ->
            if (isNoSpace(
                    failure,
                )
            ) {
                SecondDestinationProblem.NO_SPACE
            } else {
                SecondDestinationProblem.WRITE_FAILED
            }

        else -> SecondDestinationProblem.WRITE_FAILED
    }

    private fun measure(input: InputStream): Pair<Long, String> {
        val digest = newSha256()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return total to digest.digest().toLowerHex()
    }

    private fun emptyDirectory(directory: File) {
        if (!directory.isDirectory) directory.mkdirs()
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        /** El temporal de una copia en la carpeta. */
        const val TEMPORARY_SUFFIX = ".tmp"

        // Los pasos, para las pruebas de muerte del proceso.
        const val PENDING = "pending"
        const val CREATED = "created"
        const val WRITTEN = "written"
        const val RENAMED = "renamed"
        const val RECORDED = "recorded"
        const val PRUNED = "pruned"

        private const val DEVICE_PREFIX_LENGTH = 8
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/** Cómo terminó una escritura del segundo destino. */
internal sealed interface SecondDestinationRun {
    /** La copia quedó en la carpeta, releída, y la poda corrió. */
    data object Written : SecondDestinationRun

    /** No hay carpeta elegida, o con el paso 9b la opción está apagada. */
    data object NoFolder : SecondDestinationRun

    /** No hubo cambios desde la última copia de la carpeta. */
    data object NoChanges : SecondDestinationRun

    /** La base no tiene nada del negocio. */
    data object Empty : SecondDestinationRun

    /** La base no se pudo abrir todavía: el trabajo se reintenta. */
    data object Retry : SecondDestinationRun

    /** No se escribió, por [problem], que quedó anotado aparte. */
    data class Problem(val problem: SecondDestinationProblem) : SecondDestinationRun
}
