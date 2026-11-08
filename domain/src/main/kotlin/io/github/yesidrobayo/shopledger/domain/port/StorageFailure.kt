package io.github.yesidrobayo.shopledger.domain.port

/**
 * Por qué la base no pudo escribir. Llega en una [StorageFailureException] y el caso de uso la
 * devuelve como su propio resultado, para que la pantalla diga qué puede hacer la persona.
 *
 * En todos los casos la transacción se revirtió entera: no queda nada a medias, y lo escrito en el
 * formulario se conserva para intentarlo de nuevo.
 */
enum class StorageFailure {
    /** El teléfono no tiene espacio. */
    DISK_FULL,

    /** El almacenamiento falló al leer o escribir. */
    IO_ERROR,

    /** La base está dañada. La app deja de respaldar y ofrece recuperarla, sin borrar nada. */
    CORRUPT,

    /** Hay una restauración en curso: ninguna escritura entra hasta que termine. */
    RESTORE_IN_PROGRESS,

    /**
     * La base rechazó una fila que rompe una de sus reglas. Es un error de programación, porque
     * `:domain` valida antes, y no se reintenta.
     */
    INVARIANT_VIOLATION,

    /** La base estaba ocupada por otro escritor, algo que con un solo escritor no debería pasar. */
    BUSY,
    ;

    /**
     * Si la falla se arregla liberando espacio en el teléfono. Es lo único que la pantalla puede
     * pedirle a la persona; ante las demás solo dice que no se guardó y que lo escrito sigue ahí.
     * Así ninguna pantalla repite qué falla es cuál, ni necesita conocer esta lista.
     */
    val isOutOfSpace: Boolean get() = this == DISK_FULL
}
