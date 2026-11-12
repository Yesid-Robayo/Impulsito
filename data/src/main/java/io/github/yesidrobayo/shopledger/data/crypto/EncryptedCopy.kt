package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupHeaderField
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.zip.GZIPOutputStream

/**
 * Escribe y lee el formato 2 de las copias, el `.slbk`, y el `.slmd` de las fotos, en flujo: la
 * cabecera en claro y, detrás, la base comprimida con gzip y cifrada con [BackupCipher], con la
 * cabecera completa como datos asociados.
 *
 * Nunca carga el archivo en memoria: la lectura y la escritura usan un búfer fijo y Tink guarda un
 * solo segmento. No abre ni cierra archivos: quien los abre los sincroniza y los renombra.
 */
internal object EncryptedCopy {
    /** 64 KiB: el búfer de lectura, de escritura y del gzip. */
    const val BUFFER_BYTES: Int = 64 * 1024

    /**
     * Escribe la cabecera en [destination] y devuelve el flujo que cifra lo que siga. Cerrar el
     * flujo devuelto escribe el último segmento, pero no cierra [destination].
     *
     * Nunca deja pasar nada en claro: lo único que va sin cifrar es la cabecera, que no lleva datos
     * del negocio.
     */
    fun encryptingStream(
        destination: OutputStream,
        header: BackupHeader,
        key: BackupKey,
    ): OutputStream {
        require(key.matches(header.keyId)) { "La cabecera no es de esta clave" }
        val headerBytes = header.encode()
        val cipher = BackupCipher(key)
        // Primero la cabecera: Tink escribe la suya, con la sal y el prefijo, apenas abre el flujo.
        destination.write(headerBytes)
        return try {
            cipher.encryptingStream(destination, headerBytes)
        } catch (e: GeneralSecurityException) {
            // Quien abrió el archivo lo borra: con solo la cabecera no es una copia.
            throw IOException("No se pudo preparar el cifrado", e)
        }
    }

    /**
     * Comprime y cifra todo [source] hacia [destination], con [header]. Devuelve el largo y la
     * suma de lo que leyó de [source]. No cierra ninguno de los dos.
     *
     * Si [source] no tiene exactamente `raw_size` bytes, falla con [RawSizeMismatch]: la copia ya
     * escrita no se podría restaurar.
     */
    fun write(
        source: InputStream,
        destination: OutputStream,
        header: BackupHeader,
        key: BackupKey,
        checkActive: () -> Unit = {},
    ): RawContent {
        val digest = newSha256()
        var total = 0L
        GZIPOutputStream(encryptingStream(destination, header, key), BUFFER_BYTES).use { gzip ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkActive()
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
                gzip.write(buffer, 0, read)
            }
            // Sin el cierre de gzip, la copia no se descomprime aunque se descifre.
            gzip.finish()
        }
        if (total != header.rawSize) throw RawSizeMismatch()
        return RawContent(total, digest.digest().toLowerHex())
    }

    /**
     * Lee una copia desde el comienzo de [input], como entrada no confiable y en el orden de la
     * restauración: formato por los primeros bytes, cabecera contra la lista cerrada, tope de
     * tamaño, `key_id`, descifrado autenticado en flujo y descompresión contando bytes. Escribe la
     * base en [sink], si se da; sin él, solo la suma, como en la comprobación antes de subir.
     *
     * [key] es la clave local o la del código escrito. Sin ella, o con otra, se detiene en
     * `key_id`: no descifra nada. Lo escrito en [sink] solo vale si el resultado es
     * [EncryptedReadResult.Read]: con cualquier otro, quien lo llama lo descarta.
     */
    fun read(
        input: InputStream,
        key: BackupKey?,
        sink: OutputStream? = null,
        maxRawBytes: Long = BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES,
        checkActive: () -> Unit = {},
    ): EncryptedReadResult {
        val valid =
            when (val result = BackupHeader.read(input)) {
                is HeaderReadResult.Valid -> result

                HeaderReadResult.PlainFormat -> return EncryptedReadResult.PlainFormat

                HeaderReadResult.NotEncryptedCopy -> return EncryptedReadResult.NotEncryptedCopy

                is HeaderReadResult.NewerFormat ->
                    return EncryptedReadResult.NewerFormat(result.formatVersion)

                is HeaderReadResult.Rejected -> return EncryptedReadResult.HeaderRejected(
                    result.field,
                )

                HeaderReadResult.Truncated -> return EncryptedReadResult.Damaged
            }
        val header = valid.header
        if (header.rawSize > maxRawBytes) return EncryptedReadResult.TooLarge
        if (key == null || !key.matches(header.keyId)) {
            // Con otro código no se descifra nada, y nada se borra.
            return EncryptedReadResult.OtherKey(header.keyId.toLowerHex())
        }
        val content =
            try {
                inflate(
                    BackupCipher(key).decryptingStream(input, valid.bytes),
                    header.rawSize,
                    sink,
                    checkActive,
                )
            } catch (_: GeneralSecurityException) {
                return EncryptedReadResult.Damaged
            } catch (_: TooManyBytes) {
                return EncryptedReadResult.SizeMismatch
            } catch (_: IOException) {
                // Un segmento que no autentica, un final cortado o un gzip dañado: copia dañada.
                return EncryptedReadResult.Damaged
            }
        if (content.size != header.rawSize) return EncryptedReadResult.SizeMismatch
        return EncryptedReadResult.Read(header, content)
    }

    /**
     * Descomprime el único miembro de gzip contando bytes, y corta apenas pasa de [expected]. Lee el
     * flujo cifrado hasta el final, para que Tink autentique también el último segmento.
     */
    private fun inflate(
        decrypting: InputStream,
        expected: Long,
        sink: OutputStream?,
        checkActive: () -> Unit,
    ): RawContent {
        val digest = newSha256()
        var total = 0L
        SingleGzipMember.inflate(decrypting, BUFFER_BYTES, checkActive) { chunk, length ->
            total += length
            if (total > expected) throw TooManyBytes()
            digest.update(chunk, 0, length)
            sink?.write(chunk, 0, length)
        }
        return RawContent(total, digest.digest().toLowerHex())
    }

    /** La descompresión dio más bytes que los que dice la cabecera autenticada. */
    private class TooManyBytes : IOException()
}

/** Largo y suma SHA-256, en hexadecimal en minúscula, de un archivo sin comprimir ni cifrar. */
internal data class RawContent(val size: Long, val sha256: String)

/** Lo leído de la fuente no tiene los `raw_size` bytes que dice la cabecera ya escrita. */
internal class RawSizeMismatch : IOException()

/** Lo que dio leer una copia. Ningún caso lleva el mensaje de una excepción. */
internal sealed interface EncryptedReadResult {
    /** Se descifró, autenticó y descomprimió entera, con el largo de su cabecera. */
    data class Read(val header: BackupHeader, val content: RawContent) : EncryptedReadResult

    /** Es una copia en claro, el `.db.gz`: se lee por su camino, sin comprobar el origen. */
    data object PlainFormat : EncryptedReadResult

    /** Ni gzip ni la firma de una copia cifrada. */
    data object NotEncryptedCopy : EncryptedReadResult

    /** Un formato más nuevo que esta app: pide actualizar, nunca dice "copia dañada". */
    data class NewerFormat(val formatVersion: Int) : EncryptedReadResult

    /** Un valor de la cabecera fuera de la lista cerrada, rechazado antes de usar la clave. */
    data class HeaderRejected(val field: BackupHeaderField) : EncryptedReadResult

    /** La cabecera pide más de lo que se acepta restaurar. */
    data object TooLarge : EncryptedReadResult

    /**
     * Es de otro código, o no hay clave. [keyIdHex] dice cuál pide. No se descifró nada ni se borra
     * nada.
     */
    data class OtherKey(val keyIdHex: String) : EncryptedReadResult

    /** Un segmento no autentica, el archivo está cortado o el gzip está dañado. */
    data object Damaged : EncryptedReadResult

    /** Autenticada, pero el contenido no tiene los `raw_size` bytes de su cabecera. */
    data object SizeMismatch : EncryptedReadResult
}
