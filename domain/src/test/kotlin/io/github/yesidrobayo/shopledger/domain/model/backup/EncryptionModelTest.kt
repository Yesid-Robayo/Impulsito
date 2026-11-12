package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** El código recién creado y el nombre del kit. El código es el sintético de la especificación. */
class EncryptionModelTest {
    private val groups = listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51")

    @Test
    fun `a draft keeps its seven groups and never shows them as text`() {
        val draft = RecoveryCodeDraft(groups)
        assertEquals(groups, draft.groups)
        groups.forEach { assertFalse(draft.toString().contains(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a draft needs seven groups`() {
        RecoveryCodeDraft(groups.dropLast(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `every group has four characters`() {
        RecoveryCodeDraft(groups.dropLast(1) + "EF5")
    }

    @Test
    fun `the kit name carries the date without separators`() {
        assertEquals(
            "recovery-kit-20261112.pdf",
            RecoveryKitFileName.of(BusinessDate(LocalDate(2026, 11, 12))),
        )
        assertEquals(
            "recovery-kit-20270105.pdf",
            RecoveryKitFileName.of(BusinessDate(LocalDate(2027, 1, 5))),
        )
    }

    @Test
    fun `a shown code never shows itself as text`() {
        val draft =
            RecoveryCodeDraft(listOf("G7K2", "M9PQ", "R4ST", "V6WX", "Y8Z3", "ABCD", "EF51"))
        val view = RecoveryCodeView.Shown(draft)

        assertEquals(draft, view.code)
        assertFalse(view.toString().contains("G7K2"))
        assertEquals("Unavailable", RecoveryCodeView.Unavailable.toString())
    }
}
