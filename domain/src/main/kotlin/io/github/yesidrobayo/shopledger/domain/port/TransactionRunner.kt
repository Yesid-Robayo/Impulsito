package io.github.yesidrobayo.shopledger.domain.port

/**
 * Abre las transacciones de la base. Cada caso de uso que escribe abre exactamente una.
 *
 * Una venta escribe cabecera, líneas, filas del libro, saldos y alertas: si cualquiera de esos
 * pasos falla, no debe quedar nada, porque una venta a medias es un dato falso guardado como
 * bueno. Por eso todo lo que escribe un caso de uso va dentro de un solo [write], y los
 * repositorios que se llaman dentro usan esa misma transacción.
 *
 * Lo implementa `:data` con la transacción de su base. `:domain` no conoce la base: solo este
 * contrato.
 */
interface TransactionRunner {
    /**
     * Corre [block] dentro de una sola transacción de escritura y devuelve su valor.
     *
     * Con [TxResult.Commit] confirma todo lo escrito y lo cuenta como un cambio sin respaldar, en la
     * misma transacción, para que la copia se programe. Con [TxResult.Rollback] deshace todo y no
     * cuenta nada: así una regla de negocio rechaza sin dejar rastro. Si [block] lanza una
     * excepción, también deshace todo y la relanza.
     *
     * Toma el bloqueo de escritura al empezar, así que leer un saldo y escribirlo después no puede
     * chocar con otro escritor. Una vez empezada, salir de la pantalla no la cancela: la persona ya
     * confirmó. Nada lento va dentro: ni red, ni avisos, ni trabajos en segundo plano.
     *
     * No se anida: [block] nunca llama a [write]. Lanza [StorageFailureException] si la base no
     * puede escribir, y con [StorageFailure.RESTORE_IN_PROGRESS] ni siquiera empieza.
     */
    suspend fun <T> write(block: suspend () -> TxResult<T>): T

    /**
     * Corre [block] como una lectura coherente: todo lo que lee sale de la misma foto de la base,
     * aunque otra transacción escriba mientras tanto. No escribe ni cuenta como cambio.
     */
    suspend fun <T> read(block: suspend () -> T): T
}
