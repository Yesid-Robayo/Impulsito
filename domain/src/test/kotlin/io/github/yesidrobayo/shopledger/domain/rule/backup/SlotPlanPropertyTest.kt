package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotChoice
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotContent
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotTable
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.hour
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Propiedades de la elección sobre estados al azar, con semilla fija para que una falla se repita
 * igual. Nunca escribe una copia fatal, ni en la cabeza, ni en la copia válida más reciente, ni en
 * la reserva, y solo deja de escribir con las tres protegidas.
 */
class SlotPlanPropertyTest {
    @Test
    fun `no random state makes the plan break a rule`() {
        val tally = run(Random(SEED), CASES) { random -> random.nextLong(0, 2_000) }

        assertEquals(emptyList<String>(), tally.violations)
        assertTrue("debe ejercitar el conflicto", tally.allLocked > 0)
        assertTrue("debe ejercitar la reserva", tally.reserveAvoided > 0)
    }

    @Test
    fun `with no records at all the catalog alone keeps the reserve`() {
        // Un negocio sin movimientos: todas las copias con cero registros y catálogos distintos.
        // Solo el catálogo distingue la copia que conviene conservar.
        val tally = run(Random(SEED + 1), CASES) { 0L }

        assertEquals(emptyList<String>(), tally.violations)
        assertTrue("debe ejercitar la reserva por catálogo", tally.reserveAvoided > 0)
        assertTrue("debe ejercitar el conflicto", tally.allLocked > 0)
    }

    @Test
    fun `a long run of copies with restores never loses the last valid copy nor the richest one`() {
        // Cada paso escribe la copia nueva donde el plan dice. A veces la escritura muere a mitad y
        // deja la ranura abierta, y a veces la persona restaura una copia con menos datos.
        val random = Random(SEED + 2)
        var lostValid = 0
        var lostRichest = 0
        repeat(SEQUENCES) {
            var slots = SlotTable(SlotContent.Empty, SlotContent.Empty, SlotContent.Empty)
            var head: BackupSlot? = null
            var generation = 0L
            var records = 100L
            var catalog = 50L
            var hourNow = 0L
            repeat(STEPS) {
                hourNow += random.nextLong(1, 60)
                when (random.nextInt(10)) {
                    0 -> records = maxOf(0, records - random.nextLong(1, 300))

                    1 -> catalog = maxOf(0, catalog - random.nextLong(1, 40))

                    else -> {
                        records += random.nextLong(0, 5)
                        catalog += random.nextLong(0, 2)
                    }
                }
                val valid = random.nextInt(5) != 0
                val candidate =
                    SlotCandidate(
                        NewCopyMarks(valid, fatal = random.nextInt(20) == 0),
                        records,
                        catalog,
                    )
                val before = slots.copies.map { it.second }
                val hadValid = before.any { it.valid }
                val richestRecords = before.maxOfOrNull { it.records } ?: -1
                val richestCatalog = before.maxOfOrNull { it.catalogCount } ?: -1
                val choice = SlotPlan.choose(slots, candidate, hour(hourNow), head)
                if (choice is SlotChoice.Write) {
                    generation += 1
                    // Abrir ya vació la ranura: cuenta como perdida aunque la escritura muera.
                    val dies = random.nextInt(6) == 0
                    val content = if (dies) {
                        SlotContent.HalfWritten(generation)
                    } else {
                        SlotContent.Holding(
                            SlotCopy(generation, hour(hourNow), valid, records, catalog),
                        )
                    }
                    slots = slots.with(choice.slot, content)
                    if (!dies) head = choice.slot
                }
                val after = slots.copies.map { it.second }
                if (hadValid && after.none { it.valid }) lostValid += 1
                if (richestRecords > records &&
                    after.none { it.records >= richestRecords }
                ) {
                    lostRichest += 1
                }
                if (richestCatalog > catalog &&
                    after.none { it.catalogCount >= richestCatalog }
                ) {
                    lostRichest += 1
                }
            }
        }

        assertEquals("veces sin ninguna copia válida", 0, lostValid)
        assertEquals("veces sin la copia de más registros o más catálogo", 0, lostRichest)
    }

    private class Tally(val violations: List<String>, val allLocked: Int, val reserveAvoided: Int)

    private fun run(random: Random, cases: Int, records: (Random) -> Long): Tally {
        val violations = mutableListOf<String>()
        var allLocked = 0
        var reserveAvoided = 0
        repeat(cases) {
            val generations = (1L..30L).shuffled(random).take(3)
            val slots = SlotTable(
                randomContent(random, generations[0], records),
                randomContent(random, generations[1], records),
                randomContent(random, generations[2], records),
            )
            val candidate = SlotCandidate(
                NewCopyMarks(valid = random.nextBoolean(), fatal = random.nextInt(10) == 0),
                records(random),
                random.nextLong(0, 400),
            )
            val headFileRef = if (random.nextInt(4) ==
                0
            ) {
                BackupSlot.entries.random(random)
            } else {
                null
            }
            val now = hour(random.nextLong(-2_000, 2_000))
            val choice = SlotPlan.choose(slots, candidate, now, headFileRef)
            SlotOracle.violation(slots, candidate, headFileRef, choice)?.let {
                violations += "$it: $slots, $candidate, cabeza $headFileRef -> $choice"
            }
            if (choice is SlotChoice.AllLocked) allLocked += 1
            val richer = slots.copies.any {
                it.second.records > candidate.records ||
                    it.second.catalogCount > candidate.catalogCount
            }
            if (choice is SlotChoice.Write && richer) reserveAvoided += 1
        }
        return Tally(violations.take(5), allLocked, reserveAvoided)
    }

    private fun randomContent(
        random: Random,
        generation: Long,
        records: (Random) -> Long,
    ): SlotContent = when (random.nextInt(8)) {
        0 -> SlotContent.Empty

        1 -> SlotContent.HalfWritten(generation)

        else -> SlotContent.Holding(
            SlotCopy(
                generation = generation,
                writtenAt = hour(random.nextLong(-2_000, 2_000)),
                valid = random.nextInt(3) != 0,
                records = records(random),
                catalogCount = random.nextLong(0, 400),
            ),
        )
    }

    private fun SlotTable.with(slot: BackupSlot, content: SlotContent): SlotTable = when (slot) {
        BackupSlot.A -> copy(a = content)
        BackupSlot.B -> copy(b = content)
        BackupSlot.C -> copy(c = content)
    }

    private companion object {
        const val SEED = 52
        const val CASES = 100_000
        const val SEQUENCES = 2_000
        const val STEPS = 50
    }
}
