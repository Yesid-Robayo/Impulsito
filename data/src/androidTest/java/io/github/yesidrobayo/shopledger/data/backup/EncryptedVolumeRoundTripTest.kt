package io.github.yesidrobayo.shopledger.data.backup

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedReadResult
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.repository.newProduct
import io.github.yesidrobayo.shopledger.data.seed.SPANISH_ROW
import io.github.yesidrobayo.shopledger.data.seed.SeedDataGenerator
import io.github.yesidrobayo.shopledger.data.seed.SeedProfile
import io.github.yesidrobayo.shopledger.data.seed.seedConfigOf
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La ida y vuelta de una copia cifrada con el volumen de referencia: el respaldo de la app la arma,
 * la cifra, la comprueba y la sube; después se baja, se descifra solo con el código y se compara
 * por SHA-256 con la base sellada que se cifró. Mide lo que el cifrado suma al tiempo de una copia
 * en claro de la misma base. La memoria no se mide aquí: el destino falso guarda las copias en
 * memoria, y la lectura en flujo ya la prueban las del formato.
 *
 * Un emulador no es el teléfono de referencia: los tiempos solo sirven para comparar, y se anotan
 * sin exigir un tope. Lo que sí se exige es que la copia vuelva idéntica. Tarda minutos, así que
 * solo corre con el argumento `seedVolume=true` del runner de instrumentación. Solo registra
 * cifras, tiempos y tamaños, nunca datos del negocio inventado ni el código.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedVolumeRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "encrypted-volume-round-trip")
    private val destination = FakeBackupDestination()
    private val code = RecoveryCode("0123456789ABCDEFGHJKMNPQRS")
    private val downloaded = File(context.cacheDir, "volume-round-trip.slbk")
    private val decrypted = File(context.cacheDir, "volume-round-trip.db")

    @After
    fun close() {
        fixture.close()
        downloaded.delete()
        decrypted.delete()
    }

    @Test
    fun theVolumeCopyComesBackIdenticalOnlyWithTheCode() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")
        val config = requireNotNull(seedConfigOf(SPANISH_ROW))
        deleteDatabaseFiles(fixture.file)
        fixture.noBackup.deleteRecursively()
        SeedDataGenerator(SeedDataGenerator.DEFAULT_SEED, SeedProfile.VOLUME, config)
            .generate(context, fixture.file)
        assertTrue(fixture.reopen() is DataAccess.Ready)
        fixture.connect()
        log("base: ${fixture.file.length()} bytes")

        // La misma base, primero en claro y después cifrada: lo que cambia es el cifrado.
        val plain = timedRun(encrypt = false)
        // Un cambio del negocio para que haya copia nueva. La base generada ya fijó su moneda.
        fixture.ports.transactions.write {
            TxResult.Commit(fixture.ports.products.insert(newProduct("Producto de prueba")))
        }
        val encrypted = timedRun(encrypt = true)
        log("copia en claro: ${plain.millis} ms, cifrada: ${encrypted.millis} ms")
        log("cifrar sumó: ${encrypted.millis - plain.millis} ms")

        // Bajar, descifrar solo con el código y comparar con la base sellada que se cifró.
        val listing = destination.listCopies(TransferControl.Unbounded) as DestinationResult.Success
        val copy = listing.value.copies.single { it.name?.generation == 2L }
        FileOutputStream(downloaded).use { output ->
            val result =
                destination.download(copy.ref, output, Long.MAX_VALUE, TransferControl.Unbounded)
            assertTrue("$result", result is DestinationResult.Success)
        }
        assertTrue(copy.ref.value.endsWith(".slbk"))
        val started = TimeSource.Monotonic.markNow()
        val read =
            FileInputStream(downloaded).use { input ->
                FileOutputStream(decrypted).use { output ->
                    EncryptedCopy.read(input, BackupKey.from(code), output)
                }
            }
        log("descifrar: ${started.elapsedNow().inWholeMilliseconds} ms")
        assertTrue("$read", read is EncryptedReadResult.Read)
        log("cifrada: ${downloaded.length()} bytes, base: ${decrypted.length()} bytes")
        assertEquals(encrypted.sealedSha256, sha256(decrypted))
        assertEquals(encrypted.sealedSize, decrypted.length())
    }

    /** Una copia de la base de la prueba, en claro o cifrada, con su tiempo y lo que se cifró. */
    private suspend fun timedRun(encrypt: Boolean): Measured {
        var sealedSha = ""
        var sealedSize = 0L
        var hashing = 0L
        val packager =
            BackupPackager(Dispatchers.IO, encryption = {
                if (encrypt) {
                    // La suma de la base sellada no cuenta en el tiempo del cifrado.
                    val mark = TimeSource.Monotonic.markNow()
                    val sealed = fixture.stagingDir.listFiles().orEmpty().single {
                        it.name.endsWith(".db")
                    }
                    sealedSha = sha256(sealed)
                    sealedSize = sealed.length()
                    hashing = mark.elapsedNow().inWholeMilliseconds
                    PackEncryption.Encrypt(code)
                } else {
                    PackEncryption.Plain
                }
            })
        val encryption = if (encrypt) ON else LocalEncryption.NONE
        val started = TimeSource.Monotonic.markNow()
        val run =
            fixture
                .runner(destination, packager = packager, encryption = { encryption })
                .run(TestControl())
        val millis = started.elapsedNow().inWholeMilliseconds - hashing
        assertTrue("$run", run is BackupRun.Verified)
        return Measured(millis, sealedSha, sealedSize)
    }

    private class Measured(val millis: Long, val sealedSha256: String, val sealedSize: Long)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val ARGUMENT = "seedVolume"
        const val TAG = "EncryptedVolume"
        const val BUFFER_BYTES = 64 * 1024

        val ON =
            LocalEncryption(
                active = true,
                keyAvailable = true,
                hasRecoveryCode = true,
                offConfirmed = false,
            )
    }
}
