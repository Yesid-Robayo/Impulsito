package io.github.yesidrobayo.shopledger.domain.rule.update

import io.github.yesidrobayo.shopledger.domain.model.update.ManifestRead
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.domain.model.update.VersionManifest
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La búsqueda de versión solo informa: una versión nueva lo dice, una versión segura más alta
 * avisa sin bloquear, un aviso viejo se ignora en silencio y lo que no se pudo leer o comprobar lo
 * dice. Versiones y fechas inventadas.
 */
class UpdateCheckRuleTest {
    private val builtAt = Instant.parse("2026-11-01T00:00:00Z")
    private val published = Instant.parse("2026-11-10T00:00:00Z")
    private val hash = "ab".repeat(32)

    private fun manifest(
        versionCode: Long,
        minSafe: Long = 1,
        publishedAt: Instant = published,
    ) = ManifestRead.Verified(
        VersionManifest(versionCode, "1.0.$versionCode", hash, minSafe, publishedAt),
    )

    private fun evaluate(read: ManifestRead, installed: Long = 3, built: Instant? = builtAt) =
        UpdateCheckRule.evaluate(read, installed, built)

    @Test
    fun `the same version is the latest and keeps the hash to compare a file`() {
        assertEquals(UpdateCheckOutcome.Latest(hash), evaluate(manifest(versionCode = 3)))
    }

    @Test
    fun `an older published version is also the latest`() {
        assertEquals(UpdateCheckOutcome.Latest(hash), evaluate(manifest(versionCode = 2)))
    }

    @Test
    fun `a newer version says so with its name, date and hash`() {
        assertEquals(
            UpdateCheckOutcome.NewVersion("1.0.4", published, hash, important = false),
            evaluate(manifest(versionCode = 4, minSafe = 3)),
        )
    }

    @Test
    fun `a safe version above the installed one is important and blocks nothing`() {
        assertEquals(
            UpdateCheckOutcome.NewVersion("1.0.5", published, hash, important = true),
            evaluate(manifest(versionCode = 5, minSafe = 4)),
        )
    }

    @Test
    fun `a notice published before this build is ignored in silence`() {
        val before = Instant.parse("2026-10-01T00:00:00Z")
        val old = manifest(versionCode = 9, minSafe = 9, publishedAt = before)

        assertEquals(UpdateCheckOutcome.Latest(null), evaluate(old))
    }

    @Test
    fun `without a known build date no notice is ignored for its date`() {
        val old = manifest(versionCode = 4, publishedAt = Instant.parse("2026-10-01T00:00:00Z"))

        assertEquals(
            UpdateCheckOutcome.NewVersion(
                "1.0.4",
                Instant.parse("2026-10-01T00:00:00Z"),
                hash,
                important = false,
            ),
            evaluate(old, built = null),
        )
    }

    @Test
    fun `a notice published at the build moment counts`() {
        val same = manifest(versionCode = 4, publishedAt = builtAt)

        assertEquals(
            UpdateCheckOutcome.NewVersion("1.0.4", builtAt, hash, important = false),
            evaluate(same),
        )
    }

    @Test
    fun `a bad signature, no network or an unreadable notice say what happened`() {
        assertEquals(UpdateCheckOutcome.BadSignature, evaluate(ManifestRead.BadSignature))
        assertEquals(UpdateCheckOutcome.Offline, evaluate(ManifestRead.Offline))
        assertEquals(UpdateCheckOutcome.Unreadable, evaluate(ManifestRead.Unreadable))
    }

    @Test
    fun `without the address or the public key it cannot check and blocks nothing`() {
        assertEquals(UpdateCheckOutcome.Unreadable, evaluate(ManifestRead.NotConfigured))
    }
}
