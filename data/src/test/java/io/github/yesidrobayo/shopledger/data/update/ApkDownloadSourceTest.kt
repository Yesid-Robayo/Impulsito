package io.github.yesidrobayo.shopledger.data.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La dirección del APK de la versión anunciada: sale de la plantilla de la app y del nombre de
 * versión del aviso, y solo si ese nombre es una versión limpia, como `1.0.2` o `1.1.0-rc.2`.
 * Nada de la dirección viene de la red. La plantilla es inventada, con la forma de la real.
 */
class ApkDownloadSourceTest {
    private val template = "https://github.com/someone/app/releases/download/v%1\$s/app-%1\$s.apk"

    @Test
    fun `a release version fills the template in both places`() {
        assertEquals(
            "https://github.com/someone/app/releases/download/v1.0.2/app-1.0.2.apk",
            ApkDownloadSource.urlOf(template, "1.0.2").toString(),
        )
    }

    @Test
    fun `a release candidate is accepted`() {
        assertEquals(
            "https://github.com/someone/app/releases/download/v1.1.0-rc.2/app-1.1.0-rc.2.apk",
            ApkDownloadSource.urlOf(template, "1.1.0-rc.2").toString(),
        )
    }

    @Test
    fun `a name that is not a clean version builds nothing`() {
        val names =
            listOf(
                "",
                "1.0",
                "1.0.2.3",
                "v1.0.2",
                " 1.0.2",
                "1.0.2 ",
                "1.0.2\n",
                "1.0.2-beta",
                "1.0.2-rc.",
                "1.0.2-rc.1.2",
                "1.0.2/../../evil",
                "../1.0.2",
                "1.0.2?x=1",
                "1.0.2#x",
                "1.0.2%2F",
                "１.0.2",
                "12345.0.0",
            )

        for (name in names) assertNull(name, ApkDownloadSource.urlOf(template, name))
    }

    @Test
    fun `a template that is not https or has no place for the version builds nothing`() {
        val templates =
            listOf(
                "",
                "http://github.com/someone/app/releases/download/v%1\$s/app-%1\$s.apk",
                "https://github.com/someone/app/releases/latest/app.apk",
                "https://user:pw@example.com/someone/app/v%1\$s/app.apk",
                "https://github.com/someone/app/v%1\$s/app.apk?token=1",
                "https://github.com/someone/app/v%1\$s/app.apk#top",
                "not an address %1\$s",
            )

        for (bad in templates) assertNull(bad, ApkDownloadSource.urlOf(bad, "1.0.2"))
    }

    @Test
    fun `the policy only allows https to the release hosts`() {
        val policy = ApkDownloadPolicy.RELEASES

        assertTrue(policy.allows("https://github.com/someone/app/a.apk".toHttpUrl()))
        assertTrue(
            policy.allows("https://release-assets.githubusercontent.com/x?sig=1".toHttpUrl()),
        )
        assertTrue(policy.allows("https://objects.githubusercontent.com/x".toHttpUrl()))
        assertFalse(policy.allows("http://github.com/someone/app/a.apk".toHttpUrl()))
        assertFalse(policy.allows("https://evil.github.com/a.apk".toHttpUrl()))
        assertFalse(policy.allows("https://github.com.example.com/a.apk".toHttpUrl()))
        assertFalse(policy.allows("https://example.com/a.apk".toHttpUrl()))
        assertFalse(policy.allows("https://github.com:8443/someone/app/a.apk".toHttpUrl()))
        assertTrue(policy.allows("https://github.com:443/someone/app/a.apk".toHttpUrl()))
        val withCredentials =
            "https://github.com/a.apk".toHttpUrl().newBuilder().username("user").password("pw")
        assertFalse(policy.allows(withCredentials.build()))
        assertEquals(3, policy.maxRedirects)
    }
}
