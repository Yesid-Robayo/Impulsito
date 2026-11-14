package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.data.crypto.BackupHeader
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Una foto cifrada, el `.slmd`: la misma cabecera, la misma clave y el mismo flujo que una copia
 * cifrada, con `generation` y `schema_version` en 0 y `raw_size` igual al tamaño de la foto. Así la
 * herramienta de rescate abre las dos igual. Una foto pesa a lo sumo 300 KiB: se cifra en memoria.
 */
internal object MediaSeal {
    /** [plain] cifrada con [key], con [deviceId] y [createdAtUtcMillis] en su cabecera. */
    fun seal(
        plain: ByteArray,
        key: BackupKey,
        deviceId: String,
        createdAtUtcMillis: Long,
    ): ByteArray {
        val header =
            BackupHeader(
                keyId = key.keyId,
                generation = 0,
                schemaVersion = 0,
                createdAtUtcMillis = createdAtUtcMillis,
                deviceId = UUID.fromString(deviceId),
                rawSize = plain.size.toLong(),
            )
        val sealed = ByteArrayOutputStream()
        EncryptedCopy.write(ByteArrayInputStream(plain), sealed, header, key)
        return sealed.toByteArray()
    }
}
