package io.github.yesidrobayo.shopledger.domain.model.update

import kotlin.time.Instant

/**
 * El aviso de versión publicado, ya con su firma comprobada. Solo informa: nada de esto bloquea la
 * app ni los datos, ni descarga o instala algo.
 *
 * @property versionCode el número de compilación de la versión publicada.
 * @property versionName su nombre, como `1.0.1`.
 * @property apkSha256 la huella SHA-256 de su APK, en hexadecimal en minúsculas.
 * @property minSafeVersionCode la versión más vieja sin un problema conocido: debajo de ella, la
 *   app avisa que hay una corrección importante, y nunca bloquea.
 * @property publishedAt cuándo se publicó.
 */
data class VersionManifest(
    val versionCode: Long,
    val versionName: String,
    val apkSha256: String,
    val minSafeVersionCode: Long,
    val publishedAt: Instant,
) {
    companion object {
        /** El nombre más largo que se acepta. Uno real es como `1.0.1`. */
        const val MAX_NAME_LENGTH = 64

        private const val SHA256_HEX_LENGTH = 64
        private val SHA256_HEX = Regex("[0-9a-f]{$SHA256_HEX_LENGTH}")

        /**
         * El aviso con los campos que trajo el archivo, o nulo si alguno falta o no tiene sentido:
         * una compilación desde 1, un nombre con texto, una huella de 64 cifras hexadecimales, una
         * versión segura desde 0 y una fecha positiva, en milisegundos UTC. La huella se acepta en
         * mayúsculas y se guarda en minúsculas.
         */
        fun of(
            versionCode: Long?,
            versionName: String?,
            apkSha256: String?,
            minSafeVersionCode: Long?,
            publishedAtMillis: Long?,
        ): VersionManifest? {
            if (versionCode == null || versionCode < 1) return null
            val name = versionName?.trim()
            if (name.isNullOrEmpty() || name.length > MAX_NAME_LENGTH) return null
            val hash = apkSha256?.lowercase()
            if (hash == null || !SHA256_HEX.matches(hash)) return null
            if (minSafeVersionCode == null || minSafeVersionCode < 0) return null
            if (publishedAtMillis == null || publishedAtMillis <= 0) return null
            return VersionManifest(
                versionCode = versionCode,
                versionName = name,
                apkSha256 = hash,
                minSafeVersionCode = minSafeVersionCode,
                publishedAt = Instant.fromEpochMilliseconds(publishedAtMillis),
            )
        }
    }
}

/** Lo que dio leer el aviso de versión, antes de compararlo con la app instalada. */
sealed interface ManifestRead {
    /** Se leyó, la firma coincide con la clave de la app y los campos tienen sentido. */
    data class Verified(val manifest: VersionManifest) : ManifestRead

    /** La firma no coincide: se ignora sin mostrar nada de su contenido. */
    data object BadSignature : ManifestRead

    /** Sin red, o el host no respondió. */
    data object Offline : ManifestRead

    /** Respondió otra cosa, una redirección, algo de más o un archivo que no se entiende. */
    data object Unreadable : ManifestRead

    /** La app no tiene la dirección del aviso o su clave pública: no hay con qué comprobarlo. */
    data object NotConfigured : ManifestRead
}

/** Lo que dice `Buscar actualización` a la persona. Ninguno bloquea nada. */
sealed interface UpdateCheckOutcome {
    /**
     * La instalada es la más reciente que se conoce.
     *
     * @property apkSha256 la huella del APK del aviso, para compararla al verificar un archivo, o
     *   nula si el aviso se ignoró por viejo.
     */
    data class Latest(val apkSha256: String?) : UpdateCheckOutcome

    /**
     * Hay una versión más nueva.
     *
     * @property important si la instalada está debajo de la versión segura: avisa, y nunca
     *   bloquea.
     */
    data class NewVersion(
        val versionName: String,
        val publishedAt: Instant,
        val apkSha256: String,
        val important: Boolean,
    ) : UpdateCheckOutcome

    /** El aviso no pasó la comprobación de firma, y se ignoró. */
    data object BadSignature : UpdateCheckOutcome

    /** Sin conexión: se puede reintentar. */
    data object Offline : UpdateCheckOutcome

    /** No se pudo leer o comprobar el aviso. */
    data object Unreadable : UpdateCheckOutcome
}
