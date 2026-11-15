package io.github.yesidrobayo.shopledger.domain.model.update

import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Los campos del aviso de versión, después de comprobar su firma: lo que falta o no tiene sentido
 * deja el aviso sin leer, nunca a medias. Versiones y huellas inventadas.
 */
class VersionManifestTest {
    private val hash = "ab".repeat(32)

    private fun of(
        versionCode: Long? = 2,
        versionName: String? = "1.0.1",
        apkSha256: String? = hash,
        minSafeVersionCode: Long? = 1,
        publishedAtMillis: Long? = 1_790_000_000_000,
    ) = VersionManifest.of(
        versionCode,
        versionName,
        apkSha256,
        minSafeVersionCode,
        publishedAtMillis,
    )

    @Test
    fun `a complete notice keeps every field`() {
        assertEquals(
            VersionManifest(
                versionCode = 2,
                versionName = "1.0.1",
                apkSha256 = hash,
                minSafeVersionCode = 1,
                publishedAt = Instant.fromEpochMilliseconds(1_790_000_000_000),
            ),
            of(),
        )
    }

    @Test
    fun `the name is trimmed and the hash is kept in lowercase`() {
        val read = of(versionName = "  1.0.1 ", apkSha256 = "AB".repeat(32))

        assertEquals("1.0.1", read?.versionName)
        assertEquals(hash, read?.apkSha256)
    }

    @Test
    fun `the build number starts at one`() {
        assertNull(of(versionCode = null))
        assertNull(of(versionCode = 0))
        assertEquals(1L, of(versionCode = 1)?.versionCode)
    }

    @Test
    fun `the name has text and a bounded length`() {
        assertNull(of(versionName = null))
        assertNull(of(versionName = "   "))
        assertNull(of(versionName = "1".repeat(VersionManifest.MAX_NAME_LENGTH + 1)))
        val longest = "1".repeat(VersionManifest.MAX_NAME_LENGTH)
        assertEquals(longest, of(versionName = longest)?.versionName)
    }

    @Test
    fun `the hash has exactly 64 hexadecimal digits`() {
        assertNull(of(apkSha256 = null))
        assertNull(of(apkSha256 = "ab".repeat(31)))
        assertNull(of(apkSha256 = "zz".repeat(32)))
    }

    @Test
    fun `the safe version starts at zero`() {
        assertNull(of(minSafeVersionCode = null))
        assertNull(of(minSafeVersionCode = -1))
        assertEquals(0L, of(minSafeVersionCode = 0)?.minSafeVersionCode)
    }

    @Test
    fun `the publication date is positive`() {
        assertNull(of(publishedAtMillis = null))
        assertNull(of(publishedAtMillis = 0))
        assertEquals(Instant.fromEpochMilliseconds(1), of(publishedAtMillis = 1)?.publishedAt)
    }
}
