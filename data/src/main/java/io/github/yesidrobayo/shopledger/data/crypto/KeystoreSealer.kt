package io.github.yesidrobayo.shopledger.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sella archivos pequeños con una clave AES-GCM de Android Keystore, la de [alias].
 *
 * La clave no pide autenticación, no usa StrongBox ni exige el teléfono desbloqueado: así un
 * trabajo en segundo plano la usa sin nadie presente y con la pantalla bloqueada. Keystore impide
 * sacar la clave, no usarla: todo lo que corra como la app puede abrir el sobre.
 *
 * Abrir nunca crea ni reemplaza la clave: si falta, lo informa, y el sobre queda como estaba. Solo
 * [seal] crea una clave, y solo si no existe.
 */
internal class KeystoreSealer(val alias: String) {
    init {
        require(SealedEnvelope.isValidAlias(alias)) { "Alias de clave no válido" }
    }

    /**
     * Sella [plaintext] con la clave de [alias], y la crea si todavía no existe. Lanza la excepción
     * de Keystore si falla: quien guarda decide si reemplaza la clave.
     */
    fun seal(plaintext: ByteArray): ByteArray {
        val key = loadKey() ?: generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Keystore genera el vector de inicio: una clave de Keystore no acepta uno elegido.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        cipher.updateAAD(SealedEnvelope.headerOf(alias, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return SealedEnvelope(alias, iv, ciphertext).encode()
    }

    /**
     * Abre un sobre sellado con esta clave. Nunca lanza por un fallo de Keystore: lo devuelve
     * clasificado. Un sobre de otro alias cuenta como dañado.
     */
    fun open(envelopeBytes: ByteArray): SealOpenResult {
        val envelope =
            SealedEnvelope.decode(envelopeBytes)?.takeIf { it.alias == alias }
                ?: return SealOpenResult.EnvelopeDamaged("MalformedEnvelope")
        return try {
            val key = loadKey() ?: return SealOpenResult.KeyMissing
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.iv))
            cipher.updateAAD(envelope.header)
            SealOpenResult.Opened(cipher.doFinal(envelope.ciphertext))
        } catch (e: GeneralSecurityException) {
            classify(e)
        } catch (e: ProviderException) {
            classify(e)
        } catch (e: IOException) {
            // Cargar el almacén de Keystore puede fallar por entrada y salida: se reintenta.
            classify(e)
        }
    }

    fun keyExists(): Boolean = keyStore().containsAlias(alias)

    /** Borra la clave, si existe. Lo sellado con ella ya no abre de ninguna forma. */
    fun deleteKey() {
        val store = keyStore()
        if (!store.containsAlias(alias)) return
        try {
            store.deleteEntry(alias)
        } catch (_: KeyStoreException) {
            // En Android 8, Keystore no deja borrar una clave que invalidó al quitar el bloqueo:
            // otra con el mismo alias la reemplaza, y esa sí se borra.
            generateKey()
            store.deleteEntry(alias)
        }
    }

    /**
     * Pone una clave nueva en [alias], aunque la anterior ya no se pueda usar ni borrar. Solo sirve
     * antes de sellar contenido nuevo: lo sellado con la clave anterior deja de abrir.
     */
    fun replaceKey() {
        try {
            val store = keyStore()
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        } catch (_: KeyStoreException) {
            // Generar otra con el mismo alias la reemplaza igual.
        }
        generateKey()
    }

    private fun loadKey(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    private fun generateKey(): SecretKey {
        val builder =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Sin autenticación: una clave atada a la persona no abre desde un trabajo.
                .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Sin StrongBox y sin exigir el teléfono desbloqueado, para abrir con la pantalla
            // bloqueada. Son los valores por defecto: se escriben para que no cambien por error.
            builder.setIsStrongBoxBacked(false)
            builder.setUnlockedDeviceRequired(false)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val MAX_CAUSES = 6

        fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        /**
         * Separa lo que no vuelve de lo que puede pasar solo. Una clave invalidada o irrecuperable
         * no vuelve, y un sobre que no autentica tampoco: la salida la decide la persona. Lo demás
         * se reintenta, porque puede ser un fallo pasajero del servicio de Keystore.
         */
        fun classify(error: Throwable): SealOpenResult {
            val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
            val names = errorChain(error)
            return when {
                chain.any { it is KeyPermanentlyInvalidatedException } ->
                    SealOpenResult.KeyInvalidated(names)

                chain.any {
                    it is UnrecoverableKeyException
                } -> SealOpenResult.KeyInvalidated(names)

                chain.any { it is AEADBadTagException } -> SealOpenResult.EnvelopeDamaged(names)

                else -> SealOpenResult.Transient(names)
            }
        }

        /** Clases de la cadena de causas, sin mensajes: un mensaje puede llevar datos. */
        fun errorChain(error: Throwable): String = generateSequence(error) { it.cause }
            .take(MAX_CAUSES)
            .joinToString("<") { it.javaClass.simpleName }
    }
}

/** Resultado de abrir un sobre. Ningún caso lleva el mensaje de una excepción, solo su clase. */
internal sealed interface SealOpenResult {
    class Opened(val plaintext: ByteArray) : SealOpenResult {
        override fun toString(): String = "Opened"
    }

    /** No hay clave con ese alias. Abrir no la crea. */
    data object KeyMissing : SealOpenResult

    /** La clave existe pero Keystore ya no la deja usar. */
    data class KeyInvalidated(val errorChain: String) : SealOpenResult

    /** El sobre está dañado, es de otra clave, o la clave de ese alias no es la que lo selló. */
    data class EnvelopeDamaged(val errorChain: String) : SealOpenResult

    /** Fallo que puede pasar solo: se reintenta sin cambiar el estado. */
    data class Transient(val errorChain: String) : SealOpenResult
}
