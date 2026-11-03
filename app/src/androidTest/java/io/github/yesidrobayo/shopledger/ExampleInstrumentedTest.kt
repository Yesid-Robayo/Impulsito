package io.github.yesidrobayo.shopledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Corre en un dispositivo. Las pruebas instrumentadas usan `debug`, que agrega el sufijo `.debug` al applicationId. */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun debugBuildUsesSuffixedApplicationId() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.yesidrobayo.shopledger.debug", appContext.packageName)
    }
}
