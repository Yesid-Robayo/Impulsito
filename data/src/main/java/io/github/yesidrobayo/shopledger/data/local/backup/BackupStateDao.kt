package io.github.yesidrobayo.shopledger.data.local.backup

import androidx.room3.Dao
import androidx.room3.Query
import io.github.yesidrobayo.shopledger.data.backup.NeutralCounts
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de la única fila de `backup_state`.
 *
 * No hay inserción ni borrado: la fila nace con la base y dura lo que ella. Cada escritura del motor
 * de respaldo es un `UPDATE` de solo sus columnas, nunca de la fila entera, para no perder un
 * `change_seq` que una venta subió a la vez. No pasan por la transacción de los casos de uso ni
 * cuentan como cambio.
 */
@Dao
internal interface BackupStateDao {
    /** El estado del respaldo, de nuevo cada vez que cambia. Nulo solo si falta la fila. */
    @Query("SELECT * FROM backup_state WHERE id = 1")
    fun observe(): Flow<BackupStateEntity?>

    @Query("SELECT * FROM backup_state WHERE id = 1")
    suspend fun get(): BackupStateEntity?

    /**
     * Cuenta un cambio de negocio sin respaldar: sube `change_seq` en uno y, si no había cambios
     * pendientes, deja [now] como el primero sin copia. Si ya había uno, lo conserva: la antigüedad
     * se mide desde el cambio más viejo sin copia.
     *
     * Va dentro de la misma transacción que escribe los datos, al final, solo si el caso de uso
     * confirma: así un cambio y su marca se confirman juntos o no ocurren. La misma sentencia
     * fuerza una subida cuando el respaldo lo necesita.
     *
     * [now] es el reloj de pared, en milisegundos UTC. Devuelve las filas cambiadas: 1, o 0 si
     * falta la fila. Con 0 la transacción no debe confirmarse, porque el cambio quedaría sin marca.
     */
    @Query(
        "UPDATE backup_state SET change_seq = change_seq + 1, " +
            "first_unsynced_at = COALESCE(first_unsynced_at, :now) WHERE id = 1",
    )
    suspend fun markChanged(now: Long): Int

    /**
     * Si la base no tiene nada del negocio que respaldar: ni productos, proveedores ni clientes, ni
     * ventas, compras, gastos, cotizaciones, pedidos ni movimientos de dinero. Una base así nunca
     * sube, para que no tape en el destino una copia con datos.
     */
    @Query(NeutralCounts.IS_EMPTY)
    suspend fun isEmpty(): Boolean

    /** Los registros de la base activa, de las tablas que solo crecen, para la guarda. */
    @Query(NeutralCounts.RECORD_COUNT)
    suspend fun recordCount(): Long

    /** Los productos, proveedores y clientes de la base activa, para la guarda. */
    @Query(NeutralCounts.CATALOG_COUNT)
    suspend fun catalogCount(): Long

    /**
     * Fuerza una subida: la copia que el destino tiene ya no es la que esta base confirmó, y los
     * datos dejan de contar como respaldados. Es un cambio más, como el de un caso de uso.
     */
    @Query(
        "UPDATE backup_state SET change_seq = change_seq + 1, " +
            "first_unsynced_at = COALESCE(first_unsynced_at, :now) WHERE id = 1",
    )
    suspend fun forceUpload(now: Long): Int

    /** Anota que empezó una ejecución del respaldo, con el reloj de pared en milisegundos UTC. */
    @Query("UPDATE backup_state SET last_attempt_at = :now WHERE id = 1")
    suspend fun markAttempt(now: Long): Int

    /**
     * Devuelve el intento anterior, [previous], tras una ejecución que salió sin intentar subir: el
     * reintento con el servicio lleno se cuenta desde el último intento real.
     */
    @Query("UPDATE backup_state SET last_attempt_at = :previous WHERE id = 1")
    suspend fun restoreAttempt(previous: Long?): Int

    /**
     * Guarda la intención de subida antes de escribir nada en el destino: si el proceso muere a
     * mitad, la ejecución siguiente sabe qué copia estaba en camino.
     */
    @Query(
        "UPDATE backup_state SET pending_generation = :generation, pending_seq = :seq, " +
            "pending_sha256 = :sha256, pending_size_bytes = :sizeBytes WHERE id = 1",
    )
    suspend fun saveIntent(generation: Long, seq: Long, sha256: String, sizeBytes: Long): Int

    /** Olvida la intención de subida: esa copia nunca se confirma. */
    @Query(
        "UPDATE backup_state SET pending_generation = NULL, pending_seq = NULL, " +
            "pending_sha256 = NULL, pending_size_bytes = NULL WHERE id = 1",
    )
    suspend fun clearIntent(): Int

    /**
     * Anota que se envió el último byte de una copia. Es la base del intervalo en red medida, y no
     * prueba nada de la copia: solo que esos datos ya se gastaron.
     */
    @Query("UPDATE backup_state SET last_upload_finished_at = :now WHERE id = 1")
    suspend fun markUploadFinished(now: Long): Int

    /** Deja [code] como el fallo de la última ejecución: el nombre guardado de su enum. */
    @Query("UPDATE backup_state SET failure_code = :code WHERE id = 1")
    suspend fun recordFailure(code: String): Int

    /**
     * Cuenta un fallo de transporte más, de clase [kind], el nombre guardado de su enum. Al llegar
     * a [limit] fallos seguidos deja [persistentCode] como el fallo a la vista; antes, el fallo que
     * hubiera sigue igual. Todo en una sentencia, para que dos ejecuciones no pierdan una cuenta.
     *
     * El contador vuelve a 0 solo con una copia confirmada, en [confirm].
     */
    @Query(
        "UPDATE backup_state SET consecutive_failures = consecutive_failures + 1, " +
            "last_failure_kind = :kind, failure_code = CASE WHEN consecutive_failures + 1 >= " +
            ":limit THEN :persistentCode ELSE failure_code END WHERE id = 1",
    )
    suspend fun recordTransportFailure(kind: String, limit: Long, persistentCode: String): Int

    /**
     * Pasa el enlace a [linkState] si sigue conectado al destino [destinationId]. Si la persona
     * desconectó o cambió de destino mientras tanto, no cambia nada y devuelve 0: lo que pasó con el
     * destino anterior ya no importa.
     */
    @Query(
        "UPDATE backup_state SET link_state = :linkState WHERE id = 1 " +
            "AND link_state = 'CONNECTED' AND destination_id = :destinationId",
    )
    suspend fun leaveConnected(linkState: String, destinationId: String): Int

    /**
     * Detiene las copias por un conflicto de clase [kind] con el destino [destinationId], si sigue
     * conectado a él. Devuelve 0 si ya no lo está.
     */
    @Query(
        "UPDATE backup_state SET link_state = 'CONFLICT', conflict_kind = :kind, " +
            "conflict_generation = :generation, conflict_device_id = :deviceId, " +
            "conflict_file_ref = :fileRef WHERE id = 1 " +
            "AND link_state = 'CONNECTED' AND destination_id = :destinationId",
    )
    suspend fun markConflict(
        kind: String,
        generation: Long?,
        deviceId: String?,
        fileRef: String?,
        destinationId: String,
    ): Int

    /**
     * Deja de esperar a la persona porque eligió quedarse con este teléfono, si el enlace sigue en
     * conflicto con el destino [destinationId]. Devuelve 0 si ya no lo está.
     *
     * La cabeza del destino pasa a ser la que esta base conoce, y queda aceptada aunque tenga más
     * datos que la base: la guarda ya no la toma por una pérdida. Fuerza una subida, que va por
     * encima de esa cabeza y no borra nada.
     */
    @Query(
        "UPDATE backup_state SET head_generation = :generation, head_device_id = :deviceId, " +
            "head_file_ref = :fileRef, shrink_accepted_generation = :generation, " +
            "link_state = 'CONNECTED', conflict_kind = NULL, conflict_generation = NULL, " +
            "conflict_device_id = NULL, conflict_file_ref = NULL, " +
            "change_seq = change_seq + 1, first_unsynced_at = COALESCE(first_unsynced_at, :now) " +
            "WHERE id = 1 AND link_state = 'CONFLICT' AND destination_id = :destinationId",
    )
    suspend fun keepThisPhone(
        generation: Long,
        deviceId: String,
        fileRef: String,
        now: Long,
        destinationId: String,
    ): Int

    /**
     * Sale del conflicto con el destino [destinationId] sin tocar la cabeza conocida: el destino
     * ya no tiene cabeza que proteger, porque la persona borró sus copias. Fuerza una subida.
     * Devuelve 0 si el enlace ya no está en conflicto con ese destino.
     */
    @Query(
        "UPDATE backup_state SET link_state = 'CONNECTED', conflict_kind = NULL, " +
            "conflict_generation = NULL, conflict_device_id = NULL, conflict_file_ref = NULL, " +
            "change_seq = change_seq + 1, first_unsynced_at = COALESCE(first_unsynced_at, :now) " +
            "WHERE id = 1 AND link_state = 'CONFLICT' AND destination_id = :destinationId",
    )
    suspend fun reopenAfterConflict(now: Long, destinationId: String): Int

    /**
     * Confirma una copia comprobada en el destino, en una sola sentencia, y solo si el destino
     * sigue siendo [destinationId]: si la persona cambió de destino durante la subida, no cambia
     * ninguna fila, devuelve 0, y la copia queda en el destino anterior sin confirmar.
     *
     * La copia pasa a ser la cabeza, `verified_seq` toma el `change_seq` que trae la copia, y la
     * intención se borra. Si no llegaron cambios durante la copia, no queda ninguno sin copia; si
     * llegaron, el más viejo sin copia es del instante de la copia, [copiedAt].
     *
     * @param failureCode nulo para una copia válida, o el fallo que la copia sube marcado. Con una
     *   copia de saldos por recalcular se conserva `invariant_failures`, que dejó la revisión de la
     *   instantánea; con cualquier otra vuelve a 0.
     */
    @Query(
        "UPDATE backup_state SET head_generation = :generation, head_device_id = :deviceId, " +
            "head_file_ref = :fileRef, verified_seq = :seq, last_success_at = :now, " +
            "consecutive_failures = 0, last_failure_kind = NULL, " +
            "first_unsynced_at = CASE WHEN change_seq = :seq THEN NULL ELSE :copiedAt END, " +
            "failure_code = :failureCode, invariant_failures = CASE WHEN :failureCode = " +
            "'LOCAL_INVARIANT' THEN invariant_failures ELSE 0 END, " +
            "pending_generation = NULL, pending_seq = NULL, pending_sha256 = NULL, " +
            "pending_size_bytes = NULL WHERE id = 1 AND destination_id = :destinationId",
    )
    suspend fun confirm(
        generation: Long,
        deviceId: String,
        fileRef: String,
        seq: Long,
        now: Long,
        copiedAt: Long,
        failureCode: String?,
        destinationId: String,
    ): Int

    /**
     * Confirma una copia que la app no podría restaurar, comprobada en el destino, en una sola
     * sentencia y solo si el destino sigue siendo [destinationId]. Devuelve 0 si no lo es.
     *
     * La copia pasa a ser la cabeza, para que la guarda la reconozca como propia, y deja
     * [failureCode] a la vista. Los cambios siguen sin respaldar: `verified_seq`, `last_success_at`
     * y `first_unsynced_at` no cambian, así que el aviso de un día sin copia llega a su hora. Los
     * fallos de transporte vuelven a 0, porque el destino respondió, y la intención se borra.
     */
    @Query(
        "UPDATE backup_state SET head_generation = :generation, head_device_id = :deviceId, " +
            "head_file_ref = :fileRef, consecutive_failures = 0, last_failure_kind = NULL, " +
            "failure_code = :failureCode, pending_generation = NULL, pending_seq = NULL, " +
            "pending_sha256 = NULL, pending_size_bytes = NULL " +
            "WHERE id = 1 AND destination_id = :destinationId",
    )
    suspend fun confirmUnrestorable(
        generation: Long,
        deviceId: String,
        fileRef: String,
        failureCode: String,
        destinationId: String,
    ): Int

    /**
     * Guarda cuántos elementos tienen un saldo que recalcular según la última revisión: 0 si
     * ninguno. Es el número del aviso, sin decir cuáles.
     */
    @Query("UPDATE backup_state SET invariant_failures = :count WHERE id = 1")
    suspend fun recordInvariantFailures(count: Long): Int

    /**
     * Anota que la copia recién confirmada se leyó entera del destino y su suma coincidió, si el
     * destino sigue siendo [destinationId]. Solo una lectura completa lo escribe: una muestra no ve
     * el centro del archivo. Devuelve 0 si la persona cambió de destino.
     */
    @Query(
        "UPDATE backup_state SET last_full_verify_at = :now WHERE id = 1 " +
            "AND destination_id = :destinationId",
    )
    suspend fun markFullVerify(now: Long, destinationId: String): Int

    /**
     * Deja lo que encontró la verificación diaria de la base activa: [count] elementos con un saldo
     * que no coincide con su libro, y el fallo `LOCAL_INVARIANT` a la vista. Un fallo distinto que
     * ya estaba se conserva, porque su salida es otra y el programador lo mira: la copia siguiente
     * revisa lo mismo y lo deja al confirmarse.
     *
     * Solo se llama con [count] mayor que 0: una base sana no cambia nada, y el aviso lo borra una
     * copia válida.
     */
    @Query(
        "UPDATE backup_state SET invariant_failures = :count, failure_code = CASE WHEN " +
            "failure_code IS NULL THEN 'LOCAL_INVARIANT' ELSE failure_code END WHERE id = 1",
    )
    suspend fun recordActiveInvariantFailures(count: Long): Int

    /**
     * Anota que salió la notificación de un día sin copia, con el reloj de pared en milisegundos
     * UTC: la siguiente espera un día desde aquí.
     */
    @Query("UPDATE backup_state SET last_stale_notice_at = :now WHERE id = 1")
    suspend fun markStaleNotice(now: Long): Int

    /**
     * Anota cómo terminó la comprobación completa de la cabeza, [result], el nombre guardado de su
     * enum, si el destino sigue siendo [destinationId]. Devuelve 0 si la persona cambió de destino:
     * lo leído del anterior ya no dice nada.
     */
    @Query(
        "UPDATE backup_state SET last_drill_at = :now, last_drill_result = :result WHERE id = 1 " +
            "AND destination_id = :destinationId",
    )
    suspend fun markDrill(now: Long, result: String, destinationId: String): Int
}
