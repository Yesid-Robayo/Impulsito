package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.FlatJson
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationProblem
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * El segundo destino de esta instalación, en `noBackupFilesDir/second-destination.json`: la
 * carpeta, `follow_main_backup`, `last_copy_at`, `last_copy_seq`, el nombre que se está escribiendo,
 * las copias propias y el último problema. Pertenece a la instalación y a esa carpeta: no viaja en
 * una copia, y quitar el destino lo borra entero.
 *
 * Se escribe con temporal, sincronización y renombrado. Un archivo que no se entiende se lee como
 * ilegible, nunca como ausente: así ninguna copia ajena entra en la poda por error.
 *
 * @param file la ruta del archivo. Se pide al leer o escribir, en [ioContext].
 */
internal class SecondDestinationStore(
    private val file: () -> File,
    private val ioContext: CoroutineContext,
) {
    suspend fun load(): SecondDestinationLoad = withContext(ioContext) {
        val bytes =
            try {
                file().readBytes()
            } catch (_: FileNotFoundException) {
                return@withContext SecondDestinationLoad.Absent
            } catch (_: IOException) {
                return@withContext SecondDestinationLoad.Unreadable
            }
        if (bytes.size > MAX_BYTES) return@withContext SecondDestinationLoad.Unreadable
        decode(bytes.decodeToString())?.let { SecondDestinationLoad.Loaded(it) }
            ?: SecondDestinationLoad.Unreadable
    }

    /** Deja [state]. Lanza `IOException` si no quedó en el disco. */
    suspend fun save(state: SecondDestinationState) = withContext(ioContext) {
        DurableFile.write(file(), encode(state).encodeToByteArray())
    }

    /** Quita el destino entero. Las copias de la carpeta quedan donde están. */
    suspend fun clear(): Boolean = withContext(ioContext) { DurableFile.delete(file()) }

    private fun encode(state: SecondDestinationState): String = FlatJson.write(
        buildList {
            add(TREE to FlatJson.Value.Text(state.treeUri))
            add(FOLDER to FlatJson.Value.Text(state.folderUri))
            add(FOLLOW to FlatJson.Value.Bool(state.followMainBackup.toString()))
            state.lastCopyAt?.let { add(LAST_AT to FlatJson.Value.Number(it.toString())) }
            state.lastCopySeq?.let { add(LAST_SEQ to FlatJson.Value.Number(it.toString())) }
            state.pending?.let { add(PENDING to FlatJson.Value.Text(it)) }
            add(OWNED to FlatJson.Value.Text(state.owned.sorted().joinToString(SEPARATOR)))
            add(ADOPTED to FlatJson.Value.Text(state.adopted.sorted().joinToString(SEPARATOR)))
            state.problem?.let { add(PROBLEM to FlatJson.Value.Text(it.name)) }
        },
    )

    private fun decode(text: String): SecondDestinationState? {
        val members = FlatJson.parse(text) ?: return null
        if (!members.keys.containsAll(REQUIRED) || !KNOWN.containsAll(members.keys)) return null
        val tree = (members[TREE] as? FlatJson.Value.Text)?.text ?: return null
        val folder = (members[FOLDER] as? FlatJson.Value.Text)?.text ?: return null
        val follow = (members[FOLLOW] as? FlatJson.Value.Bool)?.text?.toBooleanStrictOrNull()
            ?: return null
        val lastAt = optionalCount(members[LAST_AT]) ?: return null
        val lastSeq = optionalCount(members[LAST_SEQ]) ?: return null
        val pending =
            when (val value = members[PENDING]) {
                null -> null
                is FlatJson.Value.Text -> value.text.takeIf(::isOwnName) ?: return null
                else -> return null
            }
        val owned = names(members[OWNED]) ?: return null
        val adopted = names(members[ADOPTED]) ?: return null
        val problem =
            when (val value = members[PROBLEM]) {
                null -> null

                is FlatJson.Value.Text ->
                    SecondDestinationProblem.entries.firstOrNull { it.name == value.text }
                        ?: return null

                else -> return null
            }
        if (tree.isEmpty() || folder.isEmpty()) return null
        return SecondDestinationState(
            treeUri = tree,
            folderUri = folder,
            followMainBackup = follow,
            lastCopyAt = lastAt.value,
            lastCopySeq = lastSeq.value,
            pending = pending,
            owned = owned,
            adopted = adopted,
            problem = problem,
        )
    }

    /** Un número opcional: [Optional.value] nulo si falta, o nulo entero si no tiene la forma. */
    private fun optionalCount(value: FlatJson.Value?): Optional? = when (value) {
        null -> Optional(null)

        is FlatJson.Value.Number -> value.text.takeIf(
            COUNT::matches,
        )?.toLongOrNull()?.let(::Optional)

        else -> null
    }

    private class Optional(val value: Long?)

    /** Los nombres de copias propias, separados; vacío si no hay ninguna. */
    private fun names(value: FlatJson.Value?): Set<String>? {
        val text = (value as? FlatJson.Value.Text)?.text ?: return null
        if (text.isEmpty()) return emptySet()
        val names = text.split(SEPARATOR)
        return names.takeIf { it.all(::isOwnName) }?.toSet()
    }

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "second-destination.json"

        private const val TREE = "tree_uri"
        private const val FOLDER = "folder_uri"
        private const val FOLLOW = "follow_main_backup"
        private const val LAST_AT = "last_copy_at"
        private const val LAST_SEQ = "last_copy_seq"
        private const val PENDING = "pending"
        private const val OWNED = "owned"
        private const val ADOPTED = "adopted"
        private const val PROBLEM = "problem"
        private val REQUIRED = setOf(TREE, FOLDER, FOLLOW, OWNED, ADOPTED)
        private val KNOWN = REQUIRED + setOf(LAST_AT, LAST_SEQ, PENDING, PROBLEM)

        /** Los nombres de copia no llevan `/`: sirve de separador sin escapes. */
        private const val SEPARATOR = "/"

        /** Muchas copias propias caben de sobra. */
        private const val MAX_BYTES = 64 * 1024

        private val COUNT = Regex("0|[1-9][0-9]{0,18}")

        /** Si [name] tiene el patrón exacto de una copia que la app pudo escribir. */
        fun isOwnName(name: String): Boolean = BackupFileName.parse(name) != null
    }
}

/** Lo que dio leer `second-destination.json`. */
internal sealed interface SecondDestinationLoad {
    /** No hay segundo destino. */
    data object Absent : SecondDestinationLoad

    data class Loaded(val state: SecondDestinationState) : SecondDestinationLoad

    /** El archivo existe y no se entiende: no se escribe ni se poda hasta elegir la carpeta otra vez. */
    data object Unreadable : SecondDestinationLoad
}

/**
 * El segundo destino guardado.
 *
 * @property treeUri la carpeta que eligió la persona, con su permiso persistente.
 * @property folderUri el documento de la carpeta donde van las copias: la elegida, o `<app_name>`
 *   dentro de ella.
 * @property followMainBackup `Mantener al día esta carpeta`: apagado por defecto.
 * @property lastCopyAt cuándo terminó la última copia en la carpeta, en milisegundos.
 * @property lastCopySeq el `change_seq` que traía esa copia.
 * @property pending el nombre que se está escribiendo: si quedó, la siguiente ejecución sabe qué
 *   temporal o qué copia sin anotar es suya.
 * @property owned las copias que la app escribió en esta carpeta: las únicas que poda.
 * @property adopted las copias que adoptó al reconectar la carpeta tras reinstalar: no se podan
 *   hasta que exista una copia nueva verificada.
 * @property problem el último problema, o nulo si la última copia quedó bien.
 */
internal data class SecondDestinationState(
    val treeUri: String,
    val folderUri: String,
    val followMainBackup: Boolean = false,
    val lastCopyAt: Long? = null,
    val lastCopySeq: Long? = null,
    val pending: String? = null,
    val owned: Set<String> = emptySet(),
    val adopted: Set<String> = emptySet(),
    val problem: SecondDestinationProblem? = null,
) {
    // Las direcciones de la carpeta no van a un registro.
    override fun toString(): String = "SecondDestinationState(${owned.size} copias, $problem)"
}
