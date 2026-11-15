package io.github.yesidrobayo.shopledger.domain.rule.update

import io.github.yesidrobayo.shopledger.domain.model.update.ManifestRead
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.domain.model.update.VersionManifest
import kotlin.time.Instant

/**
 * Compara el aviso de versión leído con la app instalada. Solo informa: una versión segura más
 * alta que la instalada avisa, y nunca bloquea la app ni los datos.
 *
 * Un aviso publicado antes de la compilación de la app instalada se ignora en silencio, como si
 * no hubiera nada nuevo: no hay dónde guardar el último visto, y esa fecha basta contra un aviso
 * viejo reenviado, que solo podría esconder una versión, nunca inventarla.
 */
object UpdateCheckRule {
    /**
     * Lo que dice la búsqueda con [read], la versión instalada [installedVersionCode] y la fecha
     * de su compilación [builtAt], o nula si no se conoce: entonces no se descarta ningún aviso
     * por su fecha.
     */
    fun evaluate(
        read: ManifestRead,
        installedVersionCode: Long,
        builtAt: Instant?,
    ): UpdateCheckOutcome = when (read) {
        is ManifestRead.Verified -> compare(read.manifest, installedVersionCode, builtAt)

        ManifestRead.BadSignature -> UpdateCheckOutcome.BadSignature

        ManifestRead.Offline -> UpdateCheckOutcome.Offline

        ManifestRead.Unreadable,
        ManifestRead.NotConfigured,
        -> UpdateCheckOutcome.Unreadable
    }

    private fun compare(
        manifest: VersionManifest,
        installed: Long,
        builtAt: Instant?,
    ): UpdateCheckOutcome = when {
        builtAt != null && manifest.publishedAt < builtAt -> UpdateCheckOutcome.Latest(null)

        manifest.versionCode > installed ->
            UpdateCheckOutcome.NewVersion(
                versionName = manifest.versionName,
                publishedAt = manifest.publishedAt,
                apkSha256 = manifest.apkSha256,
                important = manifest.minSafeVersionCode > installed,
            )

        else -> UpdateCheckOutcome.Latest(manifest.apkSha256)
    }
}
