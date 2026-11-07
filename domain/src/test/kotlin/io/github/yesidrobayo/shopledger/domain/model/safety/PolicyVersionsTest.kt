package io.github.yesidrobayo.shopledger.domain.model.safety

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cada versión empieza en 1, y la app publica una de cada texto. */
class PolicyVersionsTest {
    @Test
    fun `the app publishes a version of each text from the first one on`() {
        assertTrue(PolicyVersions.CURRENT.privacyPolicy >= PolicyVersions.FIRST)
        assertTrue(PolicyVersions.CURRENT.termsOfUse >= PolicyVersions.FIRST)
    }

    @Test
    fun `a version below the first one is not a version`() {
        assertThrows(IllegalArgumentException::class.java) { PolicyVersions(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { PolicyVersions(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { PolicyVersions(-1, -1) }
    }
}
