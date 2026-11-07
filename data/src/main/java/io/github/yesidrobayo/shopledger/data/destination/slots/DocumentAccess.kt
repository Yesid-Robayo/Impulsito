package io.github.yesidrobayo.shopledger.data.destination.slots

import java.io.InputStream
import java.io.OutputStream

/**
 * Las operaciones sobre los documentos de las ranuras, con los fallos de la plataforma ya
 * clasificados. Así la escritura de una ranura se prueba sin Android, y solo el adaptador de
 * `ContentResolver` conoce las excepciones del sistema.
 *
 * Un documento se nombra por su URI en texto: es un dato, nunca una ruta, y nunca va a un log.
 * Ninguna operación toca otro documento que el que recibe.
 */
internal interface DocumentAccess {
    /** Lo que el proveedor dice del documento, sin abrirlo. */
    suspend fun query(uri: String): DocumentResult<DocumentInfo>

    /** Si la app todavía tiene el permiso persistente de lectura y escritura de [uri]. */
    suspend fun isPersisted(uri: String): Boolean

    /**
     * Abre [uri] para escribir con [mode]. Con `wt` el proveedor puede vaciarlo al abrir: desde
     * aquí lo que tenía se da por perdido.
     */
    suspend fun openForWrite(uri: String, mode: WriteMode): DocumentResult<WritableDocument>

    /** Abre [uri] para leerlo por el proveedor. */
    suspend fun openForRead(uri: String): DocumentResult<InputStream>
}

/**
 * Lo que el proveedor informa de un documento.
 *
 * @property sizeBytes su tamaño, o nulo si el proveedor no lo sabe.
 * @property displayName su nombre visible, texto no confiable que la persona puede cambiar.
 */
internal data class DocumentInfo(val sizeBytes: Long?, val displayName: String?)

/** Cómo se abre un documento para escribir. El texto es el modo que recibe el proveedor. */
internal enum class WriteMode(val mode: String) {
    /** Escribir vaciando antes: el modo normal. */
    WT("wt"),

    /** Leer y escribir vaciando antes: el reintento cuando un proveedor rechaza `wt`. */
    RWT("rwt"),
}

/**
 * Un documento abierto para escribir. [stream] se cierra con [close], y un error al cerrar cuenta
 * como error de escritura.
 */
internal interface WritableDocument {
    val stream: OutputStream

    /**
     * Pide al sistema que lleve lo escrito al disco. Devuelve `false` si el proveedor entregó algo
     * donde eso no aplica, como una tubería: no es un fallo, y la relectura decide.
     */
    fun sync(): Boolean

    /** Cierra el documento. Puede fallar con un error de escritura. */
    fun close()
}

/** El resultado de una operación sobre un documento. */
internal sealed interface DocumentResult<out T> {
    data class Ok<out T>(val value: T) : DocumentResult<T>

    data class Error(val error: DocumentError) : DocumentResult<Nothing>
}

/**
 * Por qué falló una operación sobre un documento, ya separado de la excepción que la causó. Nunca
 * guarda el mensaje del proveedor.
 */
internal enum class DocumentError {
    /** La otra app pide iniciar sesión. Se separa antes que [SECURITY], del que hereda. */
    AUTHENTICATION_REQUIRED,

    /** La app ya no tiene permiso sobre el documento. */
    SECURITY,

    /** El documento no está, o el proveedor no admite el modo pedido. */
    NOT_FOUND_OR_UNSUPPORTED_MODE,

    /** Un error de entrada y salida que puede pasar solo. */
    IO,

    /** El proveedor rechazó la operación de otra forma. */
    REJECTED,
}
