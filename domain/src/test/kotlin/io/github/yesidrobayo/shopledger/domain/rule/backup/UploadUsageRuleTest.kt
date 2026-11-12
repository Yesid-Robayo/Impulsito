package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** `Subido este mes`: suma lo enviado en el mes del negocio y empieza de cero en otro mes. */
class UploadUsageRuleTest {
    private val megabyte = 1_000_000L

    @Test
    fun `three backups of 28 MB in the month add up to 84 MB`() {
        var usage: UploadUsage? = null
        repeat(3) { usage = UploadUsageRule.add(usage, "2026-11", 28 * megabyte) }

        assertEquals(84 * megabyte, UploadUsageRule.inMonth(usage, "2026-11"))
    }

    @Test
    fun `a cut upload counts what was sent`() {
        val usage = UploadUsageRule.add(UploadUsage("2026-11", 28 * megabyte), "2026-11", 7_340_032)

        assertEquals(35_340_032L, usage.bytes)
    }

    @Test
    fun `the first day of a new month starts again`() {
        val october = UploadUsage("2026-10", 90 * megabyte)

        assertEquals(0L, UploadUsageRule.inMonth(october, "2026-11"))
        assertEquals(
            UploadUsage("2026-11", 5 * megabyte),
            UploadUsageRule.add(october, "2026-11", 5 * megabyte),
        )
    }

    @Test
    fun `nothing stored is zero`() {
        assertEquals(0L, UploadUsageRule.inMonth(null, "2026-11"))
        assertEquals(UploadUsage("2026-11", 0), UploadUsageRule.add(null, "2026-11", 0))
    }

    @Test
    fun `a total that does not fit stays at the maximum`() {
        val usage = UploadUsageRule.add(UploadUsage("2026-11", Long.MAX_VALUE - 1), "2026-11", 10)

        assertEquals(Long.MAX_VALUE, usage.bytes)
    }

    @Test
    fun `negative bytes are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            UploadUsageRule.add(null, "2026-11", -1)
        }
    }

    @Test
    fun `the month is the business date year and month with two digits`() {
        assertEquals(
            "2026-03",
            UploadUsageRule.monthOf(requireNotNull(BusinessDate.parse("2026-03-01"))),
        )
        assertEquals(
            "0999-12",
            UploadUsageRule.monthOf(requireNotNull(BusinessDate.parse("0999-12-31"))),
        )
    }

    @Test
    fun `a stored month must be a real month`() {
        assertThrows(IllegalArgumentException::class.java) { UploadUsage("2026-13", 0) }
        assertThrows(IllegalArgumentException::class.java) { UploadUsage("2026-1", 0) }
        assertThrows(IllegalArgumentException::class.java) { UploadUsage("2026-11", -1) }
    }
}
