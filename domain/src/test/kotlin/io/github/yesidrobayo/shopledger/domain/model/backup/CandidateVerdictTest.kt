package io.github.yesidrobayo.shopledger.domain.model.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cada comprobación del validador tiene su clase, y la conclusión sale de la clase más grave. Un
 * aviso nunca rechaza ni marca la copia como dudosa.
 */
class CandidateVerdictTest {
    @Test
    fun `every check has the class of the specification`() {
        val expected =
            mapOf(
                CandidateCheck.SCHEMA_IDENTITY to FindingSeverity.FATAL,
                CandidateCheck.FOREIGN_KEYS to FindingSeverity.FATAL,
                CandidateCheck.STORAGE_TYPES to FindingSeverity.FATAL,
                CandidateCheck.LEDGER_ENUMS to FindingSeverity.FATAL,
                CandidateCheck.VALUE_RANGES to FindingSeverity.WARNING,
                CandidateCheck.STOCK_ON_HAND to FindingSeverity.REPAIRABLE,
                CandidateCheck.COPY_METADATA_MATCH to FindingSeverity.FATAL,
                CandidateCheck.EMPTY_OVER_DATA to FindingSeverity.FATAL,
                CandidateCheck.COST_AND_TOTALS to FindingSeverity.REPAIRABLE,
                CandidateCheck.OPEN_DATA to FindingSeverity.WARNING,
                CandidateCheck.HEADER_BALANCES to FindingSeverity.REPAIRABLE,
                CandidateCheck.UNDERIVABLE_COHERENCE to FindingSeverity.WARNING,
                CandidateCheck.TABLE_RULES to FindingSeverity.WARNING,
                CandidateCheck.PRESENTATION_PREFERENCES to FindingSeverity.WARNING,
            )

        assertEquals(expected, CandidateCheck.entries.associateWith { it.severity })
    }

    @Test
    fun `only the comparison with the copy metadata needs those metadata`() {
        val needingMetadata = CandidateCheck.entries.filter { it.requiresCopyMetadata }

        assertEquals(listOf(CandidateCheck.COPY_METADATA_MATCH), needingMetadata)
    }

    @Test
    fun `a check that needs metadata is skipped only where there are none`() {
        assertTrue(CandidateCheck.COPY_METADATA_MATCH.appliesWith(hasCopyMetadata = true))
        assertFalse(CandidateCheck.COPY_METADATA_MATCH.appliesWith(hasCopyMetadata = false))
        assertTrue(CandidateCheck.FOREIGN_KEYS.appliesWith(hasCopyMetadata = false))
        assertTrue(CandidateCheck.FOREIGN_KEYS.appliesWith(hasCopyMetadata = true))
    }

    @Test
    fun `a finding counts at least one case and takes the class of its check`() {
        val finding = CandidateFinding(CandidateCheck.STOCK_ON_HAND, cases = 3)

        assertEquals(FindingSeverity.REPAIRABLE, finding.severity)
        assertThrows(IllegalArgumentException::class.java) {
            CandidateFinding(CandidateCheck.OPEN_DATA, cases = 0)
        }
    }

    @Test
    fun `no findings make a valid restorable copy`() {
        val verdict = CandidateVerdict.of(emptyList())

        assertEquals(CandidateVerdict.Restorable(emptyList()), verdict)
        assertEquals(NewCopyMarks(valid = true, fatal = false), verdict.newCopyMarks)
    }

    @Test
    fun `warnings alone keep the copy valid and restorable`() {
        val warnings =
            listOf(finding(CandidateCheck.OPEN_DATA), finding(CandidateCheck.VALUE_RANGES))

        val verdict = CandidateVerdict.of(warnings)

        assertEquals(CandidateVerdict.Restorable(warnings), verdict)
        assertEquals(warnings, verdict.warnings)
        assertEquals(NewCopyMarks(valid = true, fatal = false), verdict.newCopyMarks)
    }

    @Test
    fun `a repairable finding needs repair and the copy is not valid but not fatal`() {
        val repair = finding(CandidateCheck.HEADER_BALANCES)
        val warning = finding(CandidateCheck.TABLE_RULES)

        val verdict = CandidateVerdict.of(listOf(warning, repair))

        assertEquals(CandidateVerdict.NeedsRepair(listOf(repair), listOf(warning)), verdict)
        assertEquals(NewCopyMarks(valid = false, fatal = false), verdict.newCopyMarks)
    }

    @Test
    fun `one fatal finding rejects and keeps every finding by class`() {
        val warning = finding(CandidateCheck.PRESENTATION_PREFERENCES)
        val repair = finding(CandidateCheck.STOCK_ON_HAND)
        val fatal = finding(CandidateCheck.FOREIGN_KEYS)

        val verdict = CandidateVerdict.of(listOf(warning, repair, fatal))

        assertEquals(
            CandidateVerdict.Rejected(listOf(fatal), listOf(repair), listOf(warning)),
            verdict,
        )
        assertEquals(NewCopyMarks(valid = false, fatal = true), verdict.newCopyMarks)
    }

    @Test
    fun `a fatal finding alone rejects`() {
        val fatal = finding(CandidateCheck.EMPTY_OVER_DATA)

        val verdict = CandidateVerdict.of(listOf(fatal))

        assertEquals(CandidateVerdict.Rejected(listOf(fatal), emptyList(), emptyList()), verdict)
    }

    private fun finding(check: CandidateCheck) = CandidateFinding(check, cases = 2)
}
