package io.github.yesidrobayo.shopledger.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * `Verificar un APK`: compara un archivo que eligió la persona con la app instalada, antes de que
 * lo abra el instalador del sistema. **Nunca instala nada**, y la app no tiene el permiso para
 * hacerlo.
 *
 * El archivo es entrada no confiable, aunque diga ser de la app:
 *
 * 1. Se copia a un temporal privado **de nombre fijo**, nunca con el nombre que trae, con el mismo
 *    límite de lectura que una copia que se baja: si el tamaño declarado lo pasa, no se lee, y si
 *    el flujo entrega más de lo declarado, se corta al pasarlo. Mientras se copia se calcula su
 *    SHA-256.
 * 2. El sistema lee el temporal con `getPackageArchiveInfo`. Desde Android 9 pide los certificados
 *    con su historia de rotación, y en Android 8 y 8.1, que no la tienen, con las firmas de
 *    siempre.
 * 3. Coincide si es el mismo paquete y trae todos los certificados de la app instalada.
 *
 * El temporal se borra siempre, también tras una falla. Ningún error cierra la app: cada uno tiene
 * su resultado.
 *
 * @param app el contexto de la aplicación.
 * @param ioContext donde se lee y se escribe el disco.
 * @param maxBytes el tope de lectura. Las pruebas usan uno chico.
 * @param workDirectory la carpeta privada del temporal.
 */
class ApkVerifier(
    private val app: Context,
    private val ioContext: CoroutineContext,
    private val maxBytes: Long = MAX_APK_BYTES,
    private val workDirectory: () -> File = { File(app.cacheDir, WORK_FOLDER) },
) {
    /** Verifica el archivo de [uri], que entregó el selector del sistema. */
    suspend fun verify(uri: Uri): ApkCheckResult = withContext(ioContext) {
        val folder = workDirectory()
        val candidate = File(folder, CANDIDATE_NAME)
        try {
            val sha256 = copyBounded(uri, folder, candidate)
            if (sha256 == null) ApkCheckResult.ReadFailed else compare(candidate, sha256)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ApkCheckResult.ReadFailed
        } catch (_: SecurityException) {
            // El proveedor del archivo retiró el permiso de lectura.
            ApkCheckResult.ReadFailed
        } finally {
            candidate.delete()
        }
    }

    /** La versión y la huella de la app instalada, o nulo si el sistema no las da. */
    suspend fun installed(): InstalledApp? = withContext(ioContext) {
        val own = ownInfo() ?: return@withContext null
        val fingerprints =
            currentSigners(own).map { ApkFingerprints.grouped(ApkFingerprints.sha256Hex(it)) }
        InstalledApp(
            versionName = own.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(own),
            fingerprint = fingerprints.sorted().joinToString("\n").ifEmpty { null },
        )
    }

    /**
     * Copia [uri] a [target] y devuelve su SHA-256, o nulo si pasa [maxBytes]. Lo que se copió de
     * más se borra al salir de [verify].
     */
    private fun copyBounded(uri: Uri, folder: File, target: File): String? {
        val declared = declaredSize(uri)
        if (declared != null && declared > maxBytes) return null
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException()
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { source ->
            FileOutputStream(target).use { sink ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) return null
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                }
            }
        }
        return ApkFingerprints.hex(digest.digest())
    }

    /** El tamaño que declara el proveedor, o nulo sin él. Puede mentir: se corta igual. */
    private fun declaredSize(uri: Uri): Long? = try {
        app.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
    } catch (_: RuntimeException) {
        // Un proveedor que no responde a la consulta: el flujo se corta igual en el límite.
        null
    }

    private fun compare(file: File, sha256: String): ApkCheckResult {
        val archive = archiveInfo(file) ?: return ApkCheckResult.NotApk
        val own = ownInfo() ?: return ApkCheckResult.ReadFailed
        val same =
            archive.packageName == own.packageName &&
                ApkFingerprints.signersMatch(signerSet(own), signerSet(archive))
        if (!same) return ApkCheckResult.Mismatch(sha256)
        val versionCode = PackageInfoCompat.getLongVersionCode(archive)
        return ApkCheckResult.Match(
            versionName = archive.versionName,
            versionCode = versionCode,
            sha256 = sha256,
            older = versionCode < PackageInfoCompat.getLongVersionCode(own),
        )
    }

    /** Lo que el sistema lee del archivo, o nulo si no es un APK. Uno roto no lanza aquí. */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? = try {
        app.packageManager.getPackageArchiveInfo(file.path, SIGNATURE_FLAGS)
    } catch (_: RuntimeException) {
        null
    }

    // Las firmas de siempre solo se piden para comparar, nunca para confiar en una sola: la
    // comparación exige todos los certificados de la app instalada.
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun ownInfo(): PackageInfo? = try {
        app.packageManager.getPackageInfo(app.packageName, SIGNATURE_FLAGS)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Las huellas de los certificados que [info] puede usar, con su historia de rotación. */
    private fun signerSet(info: PackageInfo): Set<String> =
        allSigners(info).map { ApkFingerprints.sha256Hex(it) }.toSet()

    @Suppress("DEPRECATION")
    private fun allSigners(info: PackageInfo): List<ByteArray> {
        val signers: Array<out Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let { signing ->
                    if (signing.hasMultipleSigners()) {
                        signing.apkContentsSigners
                    } else {
                        signing.signingCertificateHistory
                    }
                } ?: info.signatures
            } else {
                info.signatures
            }
        return signers.orEmpty().map { it.toByteArray() }
    }

    /** Los certificados con que está firmada hoy la app, sin los anteriores a una rotación. */
    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): List<ByteArray> {
        val signers: Array<out Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners ?: info.signatures
            } else {
                info.signatures
            }
        return signers.orEmpty().map { it.toByteArray() }
    }

    companion object {
        /**
         * El tope de lectura de un archivo elegido: el mismo que el de una copia que se baja, 512
         * MiB. Un APK de la app pesa mucho menos.
         */
        const val MAX_APK_BYTES: Long = 512L * 1024 * 1024

        /** La carpeta privada del temporal, dentro de la caché de la app. */
        const val WORK_FOLDER = "apk-check"

        /** El nombre fijo del temporal: nunca el que trae el archivo. */
        const val CANDIDATE_NAME = "candidate.apk"

        private const val BUFFER_BYTES = 64 * 1024

        /**
         * Desde Android 9, los certificados con su historia, y también las firmas de siempre, por
         * si el sistema no entrega los primeros de un archivo. En Android 8 y 8.1, solo las
         * firmas.
         */
        @Suppress("DEPRECATION")
        private val SIGNATURE_FLAGS: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            } else {
                PackageManager.GET_SIGNATURES
            }
    }
}
