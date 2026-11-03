package io.github.yesidrobayo.shopledger.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoTest {
    @Test
    fun `domain module compiles and runs tests`() {
        assertTrue(AppInfo.DOMAIN_READY)
    }
}
