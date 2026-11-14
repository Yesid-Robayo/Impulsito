package io.github.yesidrobayo.shopledger.data.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.port.ImportFileReader
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Lee el documento que la persona eligió con el selector del sistema para importar.
 *
 * Si el proveedor declara un tamaño sobre el tope, se rechaza sin abrirlo. Si no lo declara, o
 * miente, el tope se comprueba igual al leer. El documento se lee una vez, en flujo, y no se copia
 * a ningún lado: el archivo no se conserva. Su nombre es solo un dato para mostrar, sin caracteres
 * de control y acotado: nunca se usa como ruta. No registra nada, ni la dirección ni el contenido.
 */
internal class DocumentImportReader(
    private val resolver: ContentResolver,
    private val ioContext: CoroutineContext,
    private val limits: ImportLimits = ImportLimits(),
    private val factsOf: (Uri) -> DocumentFacts = { uri -> queryFacts(resolver, uri) },
) : ImportFileReader {
    override suspend fun read(
        source: String,
        separator: CsvSeparator?,
        preferred: CsvSeparator,
        onRows: (Int) -> Unit,
    ): ImportFileRead = withContext(ioContext) {
        val uri = Uri.parse(source)
        val facts =
            try {
                factsOf(uri)
            } catch (denied: SecurityException) {
                // El permiso del documento venció, o nunca se dio: al abrirlo fallará igual.
                DocumentFacts(name = null, size = null)
            }
        val declared = facts.size
        if (declared != null && declared > limits.maxFileBytes) {
            return@withContext ImportFileRead.Rejected(ImportRejection.TOO_LARGE)
        }
        val read =
            try {
                val stream =
                    resolver.openInputStream(uri)
                        ?: return@withContext ImportFileRead.Rejected(ImportRejection.UNREADABLE)
                stream.use {
                    ImportStreamReader(limits).read(it, separator, preferred, onRows) {
                        ensureActive()
                    }
                }
            } catch (unreadable: IOException) {
                ImportFileRead.Rejected(ImportRejection.UNREADABLE)
            } catch (denied: SecurityException) {
                ImportFileRead.Rejected(ImportRejection.UNREADABLE)
            }
        if (read is ImportFileRead.Read) read.copy(fileName = displayName(facts.name)) else read
    }

    /** Lo que el proveedor dice del documento. Cualquiera de los dos puede faltar. */
    data class DocumentFacts(val name: String?, val size: Long?)

    companion object {
        /** El largo con que se muestra el nombre del archivo. */
        const val MAX_NAME_CHARS: Int = 120

        /**
         * [name] sin caracteres de control ni de formato, que podrían dar vuelta el texto en
         * pantalla, recortado, o `null` si no queda nada.
         */
        internal fun displayName(name: String?): String? = name
            ?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            ?.trim()
            ?.take(MAX_NAME_CHARS)
            ?.takeIf { it.isNotEmpty() }

        private fun queryFacts(resolver: ContentResolver, uri: Uri): DocumentFacts {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                DocumentFacts(
                    name = if (name >= 0 && !cursor.isNull(name)) cursor.getString(name) else null,
                    size = if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else null,
                )
            } ?: DocumentFacts(name = null, size = null)
        }
    }
}
