package io.github.yesidrobayo.shopledger.domain.model

import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BusinessTimeZoneTest(defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `a known identifier gives that zone and keeps its identifier`() {
        val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))

        assertEquals("Etc/GMT+5", zone.id)
        assertEquals("Etc/GMT+5", zone.timeZone.id)
        assertEquals("Etc/GMT+5", zone.toString())
    }

    @Test
    fun `zones of the configuration matrix are known`() {
        val ids =
            listOf(
                "Asia/Tokyo",
                "America/Los_Angeles",
                "Asia/Kolkata",
                "America/St_Johns",
                "Asia/Kuwait",
                "America/Santiago",
                "Pacific/Auckland",
                "Etc/UTC",
            )
        for (id in ids) {
            assertEquals(id, BusinessTimeZone.of(id)?.id)
        }
    }

    @Test
    fun `the same identifier gives equal zones`() {
        assertEquals(BusinessTimeZone.of("Asia/Kolkata"), BusinessTimeZone.of("Asia/Kolkata"))
    }

    @Test
    fun `an unknown, empty or too long identifier gives no zone`() {
        val longest = "A/" + "b".repeat(BusinessTimeZone.MAX_ID_LENGTH - 2)
        val tooLong = longest + "c"
        val rejected =
            listOf(
                "",
                "Not/AZone",
                "Etc/GMT+15",
                "../../etc/localtime",
                "SYSTEM",
                longest,
                tooLong,
            )

        assertEquals(BusinessTimeZone.MAX_ID_LENGTH, longest.length)
        for (id in rejected) {
            assertNull("'$id'", BusinessTimeZone.of(id))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL
    }
}
