package io.github.yesidrobayo.shopledger.data.destination.slots

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * La muerte del proceso en un punto elegido. Es un `Error` para que ningún `catch` del código la
 * detenga.
 */
internal class Crash : Error("muerte simulada")

/** Un estado de las ranuras en memoria. Guardar es atómico, como el del archivo. */
internal class MemorySlotStore(initial: SlotState?) : SlotStateStore {
    var state: SlotState? = initial
        private set
    var unreadable = false
    var failSave = false

    /** Si no es nulo, la próxima escritura cuyo número coincida muere antes de guardar. */
    var crashBeforeSave: Int? = null
    var saves = 0
        private set

    override suspend fun load(): SlotStateLoad = when {
        unreadable -> SlotStateLoad.Unreadable
        else -> state?.let { SlotStateLoad.Loaded(it) } ?: SlotStateLoad.Missing
    }

    override suspend fun save(state: SlotState) {
        saves += 1
        if (crashBeforeSave == saves) throw Crash()
        if (failSave) throw IOException("disco")
        this.state = state
    }

    override suspend fun delete(): Boolean {
        state = null
        unreadable = false
        return true
    }
}

/**
 * Documentos en memoria, con los fallos que puede dar un proveedor. Cada documento guarda sus
 * bytes; abrir con `wt` o `rwt` los vacía, salvo que el documento simule un proveedor que no vacía.
 */
internal class FakeDocuments : DocumentAccess {
    class Doc(var bytes: ByteArray = ByteArray(0)) {
        var present = true
        var sizeUnknown = false
        var reportedSize: Long? = null
        var displayName: String? = "copia"
        var queryError: DocumentError? = null
        var openErrors = mutableMapOf<WriteMode, DocumentError>()
        var readError: DocumentError? = null
        var noTruncate = false
        var failWriteAfter: Int? = null
        var failReadAfter: Int? = null
        var syncUnsupported = false
        var failClose = false
        var crashOnOpen = false
        var crashAfterWriteBytes: Int? = null
        var crashOnClose = false
        var crashOnRead = false
    }

    val docs = mutableMapOf<String, Doc>()
    val persisted = mutableSetOf<String>()
    val opened = mutableListOf<Pair<String, WriteMode>>()

    /**
     * Lo que se hace justo al abrir para escribir, antes de vaciar: para ver el estado en ese
     * momento.
     */
    var onOpenForWrite: (String) -> Unit = {}

    var syncs = 0
        private set

    fun add(uri: String, bytes: ByteArray = ByteArray(0)): Doc {
        persisted += uri
        return Doc(bytes).also { docs[uri] = it }
    }

    override suspend fun query(uri: String): DocumentResult<DocumentInfo> {
        val doc =
            docs[uri] ?: return DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        doc.queryError?.let { return DocumentResult.Error(it) }
        if (!doc.present) return DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        val size = if (doc.sizeUnknown) null else doc.reportedSize ?: doc.bytes.size.toLong()
        return DocumentResult.Ok(DocumentInfo(size, doc.displayName))
    }

    override suspend fun isPersisted(uri: String): Boolean = uri in persisted

    override suspend fun openForWrite(
        uri: String,
        mode: WriteMode,
    ): DocumentResult<WritableDocument> {
        val doc =
            docs[uri] ?: return DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        opened += uri to mode
        onOpenForWrite(uri)
        if (doc.crashOnOpen) throw Crash()
        doc.openErrors[mode]?.let { return DocumentResult.Error(it) }
        if (!doc.present) return DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        val kept = if (doc.noTruncate) doc.bytes.copyOf() else ByteArray(0)
        doc.bytes = kept
        return DocumentResult.Ok(Writable(doc))
    }

    override suspend fun openForRead(uri: String): DocumentResult<InputStream> {
        val doc =
            docs[uri] ?: return DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
        if (doc.crashOnRead) throw Crash()
        doc.readError?.let { return DocumentResult.Error(it) }
        val failAfter =
            doc.failReadAfter ?: return DocumentResult.Ok(ByteArrayInputStream(doc.bytes))
        return DocumentResult.Ok(
            object : InputStream() {
                private var position = 0

                override fun read(): Int {
                    if (position >= failAfter) throw IOException("lectura")
                    return if (position <
                        doc.bytes.size
                    ) {
                        doc.bytes[position++].toInt() and 0xff
                    } else {
                        -1
                    }
                }
            },
        )
    }

    private inner class Writable(private val doc: Doc) : WritableDocument {
        private val buffer = ByteArrayOutputStream().apply { write(doc.bytes) }
        private var written = 0

        override val stream: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                doc.crashAfterWriteBytes?.let { if (written >= it) throw Crash() }
                doc.failWriteAfter?.let { if (written >= it) throw IOException("escritura") }
                // Un proveedor que no vacía escribe encima desde el comienzo y deja el resto.
                val current = buffer.toByteArray()
                if (doc.noTruncate && written < current.size) {
                    current[written] = b.toByte()
                    buffer.reset()
                    buffer.write(current)
                } else {
                    buffer.write(b)
                }
                written += 1
                doc.bytes = buffer.toByteArray()
            }
        }

        override fun sync(): Boolean {
            syncs += 1
            return !doc.syncUnsupported
        }

        override fun close() {
            if (doc.crashOnClose) throw Crash()
            if (doc.failClose) throw IOException("cierre")
        }
    }
}
