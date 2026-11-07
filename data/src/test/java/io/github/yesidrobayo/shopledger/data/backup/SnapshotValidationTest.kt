package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateFinding
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cuántos elementos tienen un saldo que recalcular, para el aviso de la pantalla de inicio. */
class SnapshotValidationTest {
    private val warning = CandidateFinding(CandidateCheck.VALUE_RANGES, 9)

    @Test
    fun `a restorable copy has nothing to recalculate, whatever its warnings`() {
        assertEquals(0L, repairCasesOf(CandidateVerdict.Restorable(listOf(warning))))
    }

    @Test
    fun `one drifted product counts once although it fails two checks`() {
        val verdict =
            CandidateVerdict.NeedsRepair(
                repairs =
                    listOf(
                        CandidateFinding(CandidateCheck.STOCK_ON_HAND, 1),
                        CandidateFinding(CandidateCheck.HEADER_BALANCES, 1),
                    ),
                warnings = listOf(warning),
            )

        assertEquals(1L, repairCasesOf(verdict))
    }

    @Test
    fun `the largest repairable finding gives the count`() {
        val verdict =
            CandidateVerdict.NeedsRepair(
                repairs =
                    listOf(
                        CandidateFinding(CandidateCheck.STOCK_ON_HAND, 2),
                        CandidateFinding(CandidateCheck.COST_AND_TOTALS, 5),
                    ),
                warnings = emptyList(),
            )

        assertEquals(5L, repairCasesOf(verdict))
    }

    @Test
    fun `an unrestorable copy still counts what it would recalculate`() {
        val verdict =
            CandidateVerdict.Rejected(
                fatal = listOf(CandidateFinding(CandidateCheck.FOREIGN_KEYS, 1)),
                repairs = listOf(CandidateFinding(CandidateCheck.STOCK_ON_HAND, 3)),
                warnings = emptyList(),
            )

        assertEquals(3L, repairCasesOf(verdict))
        assertEquals(
            0L,
            repairCasesOf(
                CandidateVerdict.Rejected(
                    fatal = listOf(CandidateFinding(CandidateCheck.FOREIGN_KEYS, 1)),
                    repairs = emptyList(),
                    warnings = emptyList(),
                ),
            ),
        )
    }
}
