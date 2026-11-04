package io.github.yesidrobayo.shopledger.data.transaction

import androidx.sqlite.SQLiteException
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/**
 * Traduce un error de SQLite a la [StorageFailure] que entiende `:domain`.
 *
 * El SQLite empaquetado y Room no exponen el código del error como campo: lo ponen al principio del
 * mensaje, como `Error code: 13, message: ...`. Aquí solo se lee ese número. El resto del mensaje
 * puede traer SQL o datos del negocio, y no sale de aquí.
 *
 * Se mira el código primario, el byte bajo: los códigos extendidos, como el de una llave foránea o
 * el de un error de lectura, son variantes del primario y se tratan igual.
 */
internal object SqliteFailures {
    private val leadingCode = Regex("""^Error code: (-?\d+)""")

    /** El código de resultado de SQLite que trae [failure], o `null` si no trae ninguno. */
    fun resultCode(failure: SQLiteException): Int? = failure.message
        ?.let { leadingCode.find(it) }
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

    /**
     * La falla de almacenamiento de [resultCode], o `null` si no es una: un error de SQL, un mal uso
     * de la conexión u otro error de programación, que debe seguir su camino tal cual.
     *
     * Un error de lectura o escritura sale como [StorageFailure.IO_ERROR]. Quien llama decide si,
     * con poco espacio, es en realidad un disco lleno.
     */
    fun storageFailureOf(resultCode: Int): StorageFailure? = when (resultCode and PRIMARY_MASK) {
        SQLITE_BUSY -> StorageFailure.BUSY
        SQLITE_IOERR, SQLITE_CANTOPEN -> StorageFailure.IO_ERROR
        SQLITE_CORRUPT, SQLITE_NOTADB -> StorageFailure.CORRUPT
        SQLITE_FULL -> StorageFailure.DISK_FULL
        SQLITE_CONSTRAINT -> StorageFailure.INVARIANT_VIOLATION
        else -> null
    }

    // Códigos primarios de SQLite, de https://www.sqlite.org/rescode.html.
    private const val PRIMARY_MASK = 0xFF
    private const val SQLITE_BUSY = 5
    private const val SQLITE_IOERR = 10
    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_FULL = 13
    private const val SQLITE_CANTOPEN = 14
    private const val SQLITE_CONSTRAINT = 19
    private const val SQLITE_NOTADB = 26
}
