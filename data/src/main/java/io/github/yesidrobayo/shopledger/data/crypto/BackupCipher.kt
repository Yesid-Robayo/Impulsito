package io.github.yesidrobayo.shopledger.data.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.util.SecretBytes
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * El cifrado autenticado en flujo de las copias: `AesGcmHkdfStreaming` de Tink, con AES-256-GCM,
 * una clave por copia derivada con HKDF-SHA256 de su sal aleatoria, y segmentos cifrados de 1 MiB.
 *
 * Es el único cifrado de las copias, y no se escribe a mano: ni primitivas propias ni vectores de
 * inicio elegidos aquí. Sin `KeysetManager` ni Keystore, porque la clave sale del código de
 * recuperación. La memoria queda acotada a un segmento: Tink guarda un segmento en claro y uno
 * cifrado, nunca el archivo.
 */
internal class BackupCipher(key: BackupKey) {
    private val streamingAead: StreamingAead =
        AesGcmHkdfStreaming.create(
            AesGcmHkdfStreamingKey.create(
                PARAMETERS,
                SecretBytes.copyFrom(key.material(), InsecureSecretKeyAccess.get()),
            ),
        )

    /**
     * Un flujo que cifra hacia [destination] con [associatedData] como datos asociados. Al cerrarlo
     * escribe el último segmento y su etiqueta, pero **no** cierra [destination]: quien abrió el
     * archivo lo sincroniza con el disco antes de cerrarlo.
     */
    fun encryptingStream(destination: OutputStream, associatedData: ByteArray): OutputStream =
        streamingAead.newEncryptingStream(KeepOpenOutputStream(destination), associatedData)

    /**
     * Un flujo que descifra y autentica cada segmento antes de entregarlo. Un segmento alterado,
     * fuera de orden, de otra copia, o un final cortado, lanza `IOException`.
     */
    fun decryptingStream(source: InputStream, associatedData: ByteArray): InputStream =
        streamingAead.newDecryptingStream(source, associatedData)

    companion object {
        /** Tamaño del segmento cifrado, como la plantilla `AES256_GCM_HKDF_1MB` de Tink. */
        const val CIPHERTEXT_SEGMENT_BYTES: Int = 1 shl 20

        /**
         * Lo que Tink escribe antes del primer segmento: su largo, la sal de 32 bytes y el prefijo
         * de 7 bytes de los vectores de inicio.
         */
        const val STREAM_HEADER_BYTES: Int = 40

        /** La etiqueta de autenticación de cada segmento. */
        const val TAG_BYTES: Int = 16

        val PARAMETERS: AesGcmHkdfStreamingParameters =
            AesGcmHkdfStreamingParameters
                .builder()
                .setKeySizeBytes(RecoveryCodec.BACKUP_KEY_BYTES)
                .setDerivedAesGcmKeySizeBytes(RecoveryCodec.BACKUP_KEY_BYTES)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .setCiphertextSegmentSizeBytes(CIPHERTEXT_SEGMENT_BYTES)
                .build()
    }
}

/** Pasa todo a [out], pero al cerrarse solo lo vacía: el archivo lo cierra quien lo abrió. */
internal class KeepOpenOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
    }

    override fun close() {
        flush()
    }
}
