package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeDraft
import io.github.yesidrobayo.shopledger.domain.model.backup.TypedCodeCheck
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que la pantalla del cifrado pide sin Keystore: el código impreso, la comparación de lo escrito
 * con su grupo y la prueba de ida y vuelta. El código es el sintético de la especificación.
 */
class EncryptionServiceTest {
    private val directory = File(System.getProperty("java.io.tmpdir"), "encryption-service-test")
    private val custody =
        RecoveryCustody(
            RecoveryKeyStore({ directory }, Dispatchers.IO),
            RecoveryStateStore({ directory }, Dispatchers.IO),
        ) { 0L }
    private val service =
        EncryptionService(
            custody,
            EncryptionActivation(
                custody = custody,
                backupMutex = Mutex(),
                discardPendingPlainUpload = {},
                forceUpload = {},
                nowUtcMillis = { 0L },
                ioContext = Dispatchers.IO,
                random = SecureRandom(),
            ),
        )
    private val draft =
        RecoveryCodeDraft(listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51"))

    @Test
    fun `a new code is printed in seven groups with a valid check`() {
        val created = service.newCode()
        assertEquals(7, created.groups.size)
        val parsed = RecoveryCodec.parse(created.groups.joinToString(""))
        assertTrue(parsed is CodeParseResult.Valid)
    }

    @Test
    fun `typing is normalized like the format before comparing`() {
        assertEquals(
            TypedCodeCheck.Matches,
            service.checkTyped(draft, "g7k2m9pqr4stv6wxy8z3abcdef5l"),
        )
    }

    @Test
    fun `the first group that differs is the one marked`() {
        assertEquals(
            TypedCodeCheck.GroupMismatch(1),
            service.checkTyped(draft, "X7K2M9PQR4STV6WXY8Z3ABCDEF51"),
        )
        assertEquals(
            TypedCodeCheck.GroupMismatch(4),
            service.checkTyped(draft, "G7K2M9PQR4STV6WYY8Z3ABCDEF52"),
        )
        assertEquals(
            TypedCodeCheck.GroupMismatch(7),
            service.checkTyped(draft, "G7K2M9PQR4STV6WXY8Z3ABCDEF52"),
        )
    }

    @Test
    fun `a short or long code is incomplete`() {
        assertEquals(TypedCodeCheck.Incomplete, service.checkTyped(draft, "G7K2M9PQ"))
        assertEquals(
            TypedCodeCheck.Incomplete,
            service.checkTyped(draft, "G7K2M9PQR4STV6WXY8Z3ABCDEF510"),
        )
    }

    @Test
    fun `the round trip passes with the printed code`() = runBlocking {
        assertTrue(service.roundTrip(draft))
        assertTrue(service.roundTrip(service.newCode()))
    }
}
