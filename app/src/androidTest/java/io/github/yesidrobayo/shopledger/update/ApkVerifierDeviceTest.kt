package io.github.yesidrobayo.shopledger.update

import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Verificar un APK` en el teléfono, con archivos reales: el APK de la propia app coincide, uno de
 * otra firma dice que no se instale, y uno truncado, uno que no es un APK, uno que pasa el límite
 * o uno que no se puede abrir dan su resultado sin cerrar la app. El temporal se borra siempre. En
 * API 26 y 27 compara con las firmas de siempre, y desde API 28 con los certificados y su
 * historia.
 */
@RunWith(AndroidJUnit4::class)
class ApkVerifierDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(app.cacheDir, "apk-check-test")
    private val work = File(folder, "work")

    /** El APK instalado de la app que se prueba: el mismo paquete y el mismo certificado. */
    private val ownApk = File(app.applicationInfo.sourceDir)

    private fun verifier(maxBytes: Long = ApkVerifier.MAX_APK_BYTES) =
        ApkVerifier(app, Dispatchers.IO, maxBytes, workDirectory = { work })

    private fun copyOf(source: File, name: String, length: Long = source.length()): File {
        val target = File(folder, name)
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    left -= read
                }
            }
        }
        return target
    }

    private fun check(file: File, maxBytes: Long = ApkVerifier.MAX_APK_BYTES) = runBlocking {
        verifier(maxBytes).verify(Uri.fromFile(file))
    }

    private fun assertNoLeftover() = assertFalse(File(work, ApkVerifier.CANDIDATE_NAME).exists())

    /** La huella de [file] leída por partes: un APK entero no cabe en la memoria de la prueba. */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return ApkFingerprints.hex(digest.digest())
    }

    @Before
    fun cleanFolder() {
        folder.deleteRecursively()
        assertTrue(folder.mkdirs())
    }

    @After
    fun removeFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun theAppsOwnApkMatchesWithItsVersionAndHash() {
        val file = copyOf(ownApk, "propio.apk")
        val own = app.packageManager.getPackageInfo(app.packageName, 0)

        val result = check(file)

        assertTrue(result.toString(), result is ApkCheckResult.Match)
        result as ApkCheckResult.Match
        assertEquals(own.versionName, result.versionName)
        assertEquals(PackageInfoCompat.getLongVersionCode(own), result.versionCode)
        assertFalse(result.older)
        assertEquals(sha256Of(file), result.sha256)
        assertNoLeftover()
    }

    @Test
    fun anApkWithAnotherSignatureSaysNotToInstallIt() {
        // Una app del sistema que existe en todas las versiones: otro paquete y otra firma. Los
        // recursos del sistema (`android`) ya no se leen como un APK común desde Android 13.
        val system = File(app.packageManager.getApplicationInfo("com.android.shell", 0).sourceDir)
        val file = copyOf(system, "otra-firma.apk")

        val result = check(file)

        assertTrue(result.toString(), result is ApkCheckResult.Mismatch)
        assertEquals(
            sha256Of(file),
            (result as ApkCheckResult.Mismatch).sha256,
        )
        assertNoLeftover()
    }

    @Test
    fun aTruncatedApkIsNotAnApkAndNothingCloses() {
        val file = copyOf(ownApk, "truncado.apk", ownApk.length() / 2)

        assertEquals(ApkCheckResult.NotApk, check(file))
        assertNoLeftover()
    }

    @Test
    fun aFileThatIsNotAnApkSaysSo() {
        val file = File(folder, "texto.apk").apply { writeBytes(Random(7).nextBytes(4096)) }

        assertEquals(ApkCheckResult.NotApk, check(file))
        assertNoLeftover()
    }

    @Test
    fun aFileOverTheLimitIsNotReadToTheEnd() {
        val file = copyOf(ownApk, "grande.apk")

        assertEquals(ApkCheckResult.ReadFailed, check(file, maxBytes = 1024))
        assertNoLeftover()
    }

    @Test
    fun aFileThatCannotBeOpenedCouldNotBeRead() {
        assertEquals(ApkCheckResult.ReadFailed, check(File(folder, "no-existe.apk")))
        assertNoLeftover()
    }

    @Test
    fun theInstalledAppShowsItsVersionAndFingerprint() {
        val installed = runBlocking { verifier().installed() }

        assertNotNull(installed)
        val fingerprint = checkNotNull(installed?.fingerprint)
        // SHA-256: 32 pares de cifras en mayúsculas, separados por dos puntos.
        assertTrue(fingerprint, Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(fingerprint))
    }
}

/** El tramo con que se calcula una huella en la prueba. */
private const val HASH_BUFFER_BYTES = 64 * 1024
