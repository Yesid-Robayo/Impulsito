package io.github.yesidrobayo.shopledger.data.backup

import android.system.ErrnoException
import android.system.OsConstants
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.File
import java.io.IOException
import java.io.SyncFailedException

/**
 * Arma una copia completa fuera del destino, con los pasos 7 a 9 del respaldo y las mismas piezas:
 * instantánea, sello, `integrity_check`, el validador de las restauraciones y compresión
 * comprobada. La usan la copia exportada y el segundo destino.
 *
 * - **No reserva generación:** el sello lleva `head_generation`, la de la última copia del
 *   destino, porque esta copia no va al destino ni cambia su cabeza.
 * - **Solo lee la base activa.** No escribe `verified_seq`, `head_*` ni ningún fallo: lo que pasa
 *   aquí se dice aparte y nunca toca el estado del respaldo.
 * - **Una copia que la app no podría restaurar** se arma igual y vuelve marcada: quien la usa decide
 *   si la escribe. Una base dañada no se arma.
 *
 * Todo queda dentro de [directory], que quien llama borra al terminar.
 *
 * @param snapshots la instantánea de la base activa.
 * @param sealer el sello de la copia.
 * @param validation el validador de las restauraciones sobre la instantánea.
 * @param packager la compresión comprobada.
 * @param headGeneration la generación de la cabeza en el estado del respaldo.
 * @param deviceId el identificador de este teléfono.
 */
internal class LocalCopyBuilder(
    private val snapshots: SnapshotCreator,
    private val sealer: SnapshotSealer,
    private val validation: SnapshotValidation,
    private val packager: BackupPackager,
    private val headGeneration: suspend () -> Long,
    private val deviceId: suspend () -> String,
    private val clock: Clock,
) {
    /** Arma la copia dentro de [directory], que debe existir. */
    suspend fun build(directory: File): LocalCopy {
        val snapshotFile = File(directory, SNAPSHOT_NAME).absoluteFile
        val packedFile = File(directory, PACKED_NAME).absoluteFile
        val generation = headGeneration()
        val snapshot =
            when (val created = snapshots.create(snapshotFile)) {
                is SnapshotResult.Created -> created
                SnapshotResult.DatabaseMissing -> return LocalCopy.Failed
                SnapshotResult.Corrupt -> return LocalCopy.Damaged
                SnapshotResult.StorageFull -> return LocalCopy.NoSpace
                is SnapshotResult.Failed -> return LocalCopy.Failed
            }
        val sealed =
            when (
                val result =
                    sealer.seal(
                        snapshot.file,
                        CopySeal(generation, deviceId(), clock.nowEpochMillis()),
                    )
            ) {
                is SealResult.Sealed -> result
                SealResult.NoState, SealResult.Corrupt -> return LocalCopy.Damaged
                SealResult.StorageFull -> return LocalCopy.NoSpace
                is SealResult.Failed -> return LocalCopy.Failed
            }
        when (snapshots.checkIntegrity(snapshot.file)) {
            IntegrityResult.Intact -> Unit
            IntegrityResult.Damaged -> return LocalCopy.Damaged
            is IntegrityResult.Failed -> return LocalCopy.Failed
        }
        val marks =
            when (val verdict = validation.check(snapshot.file)) {
                is SnapshotVerdict.Checked -> verdict.marks
                SnapshotVerdict.Corrupt -> return LocalCopy.Damaged
                is SnapshotVerdict.Failed -> return LocalCopy.Failed
            }
        if (snapshot.file.length() > BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES) {
            return LocalCopy.TooLarge
        }
        return when (val packed = packager.compress(snapshot.file, packedFile)) {
            is PackageResult.Packed ->
                LocalCopy.Built(
                    file = packed.file,
                    sizeBytes = packed.fileSizeBytes,
                    sha256 = packed.fileSha256,
                    restorable = !marks.fatal,
                    records = sealed.records,
                    changeSeq = sealed.changeSeq,
                )

            PackageResult.TooLarge -> LocalCopy.TooLarge

            PackageResult.StorageFull -> LocalCopy.NoSpace

            PackageResult.Mismatch -> LocalCopy.Failed

            is PackageResult.Failed -> LocalCopy.Failed
        }
    }

    companion object {
        /** La instantánea, que la compresión borra al terminar bien. */
        const val SNAPSHOT_NAME = "local-copy.db"

        /** El archivo comprimido y comprobado. */
        const val PACKED_NAME = "local-copy.db.gz"
    }
}

/** Lo que dio armar una copia local. */
internal sealed interface LocalCopy {
    /**
     * La copia comprimida y comprobada en [file].
     *
     * @property sha256 la suma de [file], en hexadecimal en minúscula.
     * @property restorable si la app la podría restaurar.
     * @property records los registros que trae, para sus metadatos.
     * @property changeSeq el `change_seq` de la instantánea: lo que la copia trae de verdad.
     */
    data class Built(
        val file: File,
        val sizeBytes: Long,
        val sha256: String,
        val restorable: Boolean,
        val records: Long,
        val changeSeq: Long,
    ) : LocalCopy

    /** La base no pasó `integrity_check`, o no se pudo leer como una base: no se arma. */
    data object Damaged : LocalCopy

    /** No hubo espacio en el teléfono para prepararla. */
    data object NoSpace : LocalCopy

    /** Pasaba el tamaño que la app puede restaurar. */
    data object TooLarge : LocalCopy

    /** Otra falla del disco o de la base. */
    data object Failed : LocalCopy
}

/**
 * Si [failure] dice que no hubo espacio donde se escribía. Con `ENOSPC`, y también con una
 * sincronización que falla: en API 33 la falta de espacio de una carpeta elegida llega así, sin
 * `ENOSPC`.
 */
internal fun isNoSpace(failure: IOException): Boolean = failure is SyncFailedException ||
    generateSequence<Throwable>(failure) { it.cause }
        .any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
