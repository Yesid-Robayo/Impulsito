package io.github.yesidrobayo.shopledger.data.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.DestinationIdentity
import io.github.yesidrobayo.shopledger.domain.model.backup.ConnectFolderResult
import io.github.yesidrobayo.shopledger.domain.model.backup.FoundLocalCopies
import io.github.yesidrobayo.shopledger.domain.model.backup.FoundLocalCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView
import io.github.yesidrobayo.shopledger.domain.port.SecondDestinationPort
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Elegir, leer y quitar la carpeta del segundo destino, por el marco de acceso a documentos.
 *
 * - **Solo el almacenamiento del teléfono:** la autoridad del árbol debe ser la del sistema. Otra
 *   se rechaza sin tomar el permiso, aunque se declare local.
 * - **La carpeta de la app:** si la elegida ya se llama como la app, se usa; si no, se usa dentro
 *   la carpeta con el nombre de la app, o la de un nombre anterior si existe, y si no hay ninguna se
 *   crea. Así, al elegir `Documents`, las copias van en `Documents/<app_name>/`.
 * - **Cambiar de carpeta** suelta el permiso de la anterior; sus copias quedan donde están.
 *
 * Guardar toma el cerrojo del respaldo: el trabajo del segundo destino nunca lee el archivo a medias.
 *
 * @param previousAppNames los nombres anteriores de la app, en el orden del recurso.
 * @param afterConnect lo que sigue a elegir: programar la primera copia.
 */
internal class SecondDestinationService(
    private val resolver: ContentResolver,
    private val store: SecondDestinationStore,
    private val previousAppNames: List<String> = emptyList(),
    private val mutex: Mutex,
    private val afterConnect: suspend () -> Unit,
    private val ioContext: CoroutineContext,
) : SecondDestinationPort {
    override suspend fun view(): SecondDestinationView = when (val load = store.load()) {
        SecondDestinationLoad.Absent -> SecondDestinationView.None

        SecondDestinationLoad.Unreadable -> SecondDestinationView.Unreadable

        is SecondDestinationLoad.Loaded ->
            SecondDestinationView.Connected(
                folderName = withContext(ioContext) {
                    safely { nameOf(Uri.parse(load.state.folderUri)) }
                },
                lastCopyAt = load.state.lastCopyAt?.let(Instant::fromEpochMilliseconds),
                followMainBackup = load.state.followMainBackup,
                problem = load.state.problem,
            )
    }

    override suspend fun connect(treeUri: String, appName: String): ConnectFolderResult =
        save(treeUri, namesOf(appName), adopt = false)

    override suspend fun reconnectAndAdopt(treeUri: String, appName: String): ConnectFolderResult =
        save(treeUri, namesOf(appName), adopt = true)

    override suspend fun setFollowMainBackup(follow: Boolean) {
        mutex.withLock {
            val saved = (store.load() as? SecondDestinationLoad.Loaded)?.state ?: return
            store.save(saved.copy(followMainBackup = follow))
        }
    }

    override suspend fun remove() {
        mutex.withLock {
            val saved = (store.load() as? SecondDestinationLoad.Loaded)?.state
            store.clear()
            saved?.let { withContext(ioContext) { release(Uri.parse(it.treeUri)) } }
        }
    }

    override suspend fun find(treeUri: String, appName: String): FoundLocalCopies =
        withContext(ioContext) {
            val tree = Uri.parse(treeUri)
            if (!isLocal(tree)) return@withContext FoundLocalCopies.NotLocal
            val names = namesOf(appName)
            if (names.isEmpty()) return@withContext FoundLocalCopies.Failed
            val folder =
                safely { folderFor(tree, names, create = false) }
                    ?: return@withContext FoundLocalCopies.Failed
            val files =
                safely { children(tree, folder) } ?: return@withContext FoundLocalCopies.Failed
            val copies =
                files.filter { !it.isDirectory }.mapNotNull { child ->
                    val name = BackupFileName.parse(child.name) ?: return@mapNotNull null
                    FoundLocalCopy(
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            child.id,
                        ).toString(),
                        createdAt = Instant.fromEpochSeconds(name.createdAt.epochSeconds),
                        generation = name.generation,
                        encrypted = name.format == BackupFileFormat.ENCRYPTED,
                    )
                }.sortedWith(
                    compareByDescending<FoundLocalCopy> {
                        it.generation
                    }.thenByDescending { it.createdAt },
                )
            if (copies.isEmpty()) FoundLocalCopies.NoCopies else FoundLocalCopies.Found(copies)
        }

    private suspend fun save(
        treeUri: String,
        names: List<String>,
        adopt: Boolean,
    ): ConnectFolderResult {
        val tree = Uri.parse(treeUri)
        if (!isLocal(tree)) return ConnectFolderResult.NOT_LOCAL
        if (names.isEmpty()) return ConnectFolderResult.FAILED
        val folder =
            withContext(ioContext) {
                try {
                    resolver.takePersistableUriPermission(tree, PERMISSION_FLAGS)
                } catch (_: SecurityException) {
                    return@withContext null
                }
                safely { folderFor(tree, names, create = true) }.also {
                    if (it ==
                        null
                    ) {
                        release(tree)
                    }
                }
            } ?: return ConnectFolderResult.FAILED
        val adopted =
            if (adopt) {
                withContext(ioContext) { safely { children(tree, folder) } }.orEmpty()
                    .filter { !it.isDirectory && SecondDestinationStore.isOwnName(it.name) }
                    .map { it.name }
                    .toSet()
            } else {
                emptySet()
            }
        try {
            mutex.withLock {
                val previous = (store.load() as? SecondDestinationLoad.Loaded)?.state
                store.save(
                    SecondDestinationState(
                        treeUri = treeUri,
                        folderUri = DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            folder.id,
                        ).toString(),
                        adopted = adopted,
                    ),
                )
                if (previous != null && previous.treeUri != treeUri) {
                    withContext(ioContext) { release(Uri.parse(previous.treeUri)) }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            return ConnectFolderResult.FAILED
        }
        afterConnect()
        return ConnectFolderResult.CONNECTED
    }

    /** La carpeta donde van las copias dentro del árbol elegido, según [AppFolderChoice]. */
    private fun folderFor(tree: Uri, appNames: List<String>, create: Boolean): Child? {
        val chosenId = DocumentsContract.getTreeDocumentId(tree)
        val chosenName = nameOf(DocumentsContract.buildDocumentUriUsingTree(tree, chosenId))
        val chosen = Child(chosenId, chosenName.orEmpty(), isDirectory = true)
        val folders = children(tree, chosen).filter { it.isDirectory }
        return when (
            val choice = AppFolderChoice.choose(
                chosenName,
                folders.map {
                    it.name
                },
                appNames,
            )
        ) {
            AppFolderChoice.Choice.UseChosen -> chosen

            is AppFolderChoice.Choice.UseChild -> folders.first { it.name == choice.name }

            is AppFolderChoice.Choice.CreateChild ->
                if (create) {
                    val created =
                        DocumentsContract.createDocument(
                            resolver,
                            DocumentsContract.buildDocumentUriUsingTree(tree, chosenId),
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            choice.name,
                        ) ?: return null
                    Child(DocumentsContract.getDocumentId(created), choice.name, isDirectory = true)
                } else {
                    // Sin la carpeta de la app, las copias pueden estar en la elegida misma.
                    chosen
                }
        }
    }

    private fun children(tree: Uri, parent: Child): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id)
        val cursor =
            resolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            ) ?: throw FolderMissingException()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    add(
                        Child(
                            id,
                            name,
                            it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }
    }

    private fun nameOf(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun release(tree: Uri) {
        try {
            resolver.releasePersistableUriPermission(tree, PERMISSION_FLAGS)
        } catch (_: SecurityException) {
            Unit
        }
    }

    private fun isLocal(tree: Uri): Boolean = tree.authority == EXTERNAL_STORAGE_AUTHORITY

    /** El nombre de la app y los anteriores, limpios para nombrar una carpeta. */
    private fun namesOf(appName: String): List<String> =
        (listOf(appName) + previousAppNames).mapNotNull(DestinationIdentity::folderName).distinct()

    /** Lo leído, o nulo si el proveedor falló: una carpeta que no responde no cierra la pantalla. */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private data class Child(val id: String, val name: String, val isDirectory: Boolean)

    companion object {
        /** El almacenamiento del teléfono, de una tarjeta SD o de una memoria USB, del sistema. */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        private const val PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** Qué carpeta usar dentro de la que eligió la persona. Puro, para probarlo sin el sistema. */
internal object AppFolderChoice {
    sealed interface Choice {
        /** La elegida ya es la de la app. */
        data object UseChosen : Choice

        /** Dentro de la elegida ya está la carpeta [name], de la app o de un nombre anterior. */
        data class UseChild(val name: String) : Choice

        /** Dentro de la elegida se crea la carpeta [name], con el nombre de la app. */
        data class CreateChild(val name: String) : Choice
    }

    /**
     * Con [chosenName] la carpeta elegida, [childFolders] las carpetas dentro de ella y [appNames]
     * el nombre de la app y luego los anteriores, ya limpios.
     */
    fun choose(chosenName: String?, childFolders: List<String>, appNames: List<String>): Choice {
        require(appNames.isNotEmpty()) { "Falta el nombre de la app" }
        if (chosenName != null && chosenName in appNames) return Choice.UseChosen
        appNames.firstOrNull { it in childFolders }?.let { return Choice.UseChild(it) }
        return Choice.CreateChild(appNames.first())
    }
}
