package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotChoice
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotContent
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotTable
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.hour
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La elección sobre todas las combinaciones de ranuras, cabeza, clase de copia, registros y
 * catálogo que recorre el modelo del diseño, en el mismo orden.
 *
 * El modelo resume sus 209.952 decisiones en una huella FNV-1a de 64 bits: cada decisión es la
 * letra elegida, `FATAL`, o `SHRUNK:` con la ranura que muestra el conflicto, seguida de un salto
 * de línea. Si la huella de aquí coincide, la función decide igual que el modelo en cada uno de
 * esos casos. Si alguien cambia la regla en un lado, esta prueba falla hasta cambiarla en el otro.
 */
class SlotPlanReferenceTest {
    @Test
    fun `every combination decides exactly as the design model`() {
        var fingerprint = FNV_OFFSET
        var cases = 0
        val violations = mutableListOf<String>()
        val tally = mutableMapOf<String, Int>()
        for (generations in GENERATION_ORDERS) {
            for (a in options(generations[0])) {
                for (b in options(generations[1])) {
                    for (c in options(generations[2])) {
                        val slots = SlotTable(a, b, c)
                        for (kind in Kind.entries) {
                            for (now in NOW_HOURS) {
                                val candidate = SlotCandidate(
                                    NewCopyMarks(
                                        valid = kind == Kind.VALID,
                                        fatal =
                                            kind == Kind.FATAL,
                                    ),
                                    NEW_RECORDS,
                                    NEW_CATALOG,
                                )
                                val choice = SlotPlan.choose(
                                    slots,
                                    candidate,
                                    hour(now),
                                    headFileRef = null,
                                )
                                SlotOracle.violation(slots, candidate, null, choice)?.let {
                                    violations +=
                                        it
                                }
                                val outcome = outcomeOf(choice)
                                tally[outcome] = (tally[outcome] ?: 0) + 1
                                for (byte in "$outcome\n".encodeToByteArray()) {
                                    fingerprint =
                                        (fingerprint xor byte.toUByte().toULong()) * FNV_PRIME
                                }
                                cases += 1
                            }
                        }
                    }
                }
            }
        }

        assertEquals(209_952, cases)
        assertEquals(emptyList<String>(), violations.distinct())
        assertEquals(EXPECTED_TALLY, tally.toSortedMap())
        assertEquals(MODEL_FINGERPRINT, fingerprint.toString(radix = 16).padStart(16, '0'))
    }

    private fun outcomeOf(choice: SlotChoice): String = when (choice) {
        is SlotChoice.Write -> choice.slot.name
        SlotChoice.FatalCopy -> "FATAL"
        is SlotChoice.AllLocked -> "SHRUNK:${choice.conflictSlot?.name ?: "-"}"
    }

    /**
     * Las 18 formas de una ranura en el modelo: vacía, a medias, y una copia de generación
     * [generation] válida o no, de la hora 0 o 400, con 0 o 500 registros de más y 0 o 300
     * productos de más.
     */
    private fun options(generation: Long): List<SlotContent> = buildList {
        add(SlotContent.Empty)
        add(SlotContent.HalfWritten(HALF_WRITTEN_GENERATION))
        for (valid in listOf(true, false)) {
            for (at in listOf(0L, 400L)) {
                for (extraRecords in listOf(0L, 500L)) {
                    for (extraCatalog in listOf(0L, 300L)) {
                        val copy = SlotCopy(
                            generation = generation,
                            writtenAt = hour(at),
                            valid = valid,
                            records = 100 + generation + extraRecords,
                            catalogCount = 50 + generation + extraCatalog,
                        )
                        add(SlotContent.Holding(copy))
                    }
                }
            }
        }
    }

    private enum class Kind { VALID, INVALID, FATAL }

    private companion object {
        /** La huella que da el modelo del diseño sobre esta misma enumeración. */
        const val MODEL_FINGERPRINT = "d1ec1e73b7bb8ca5"

        /**
         * Cuántas veces sale cada decisión en el modelo. Ayuda a ver dónde está la diferencia si la
         * huella no coincide.
         */
        val EXPECTED_TALLY = sortedMapOf(
            "A" to 42_864,
            "B" to 41_232,
            "C" to 40_896,
            "FATAL" to 69_984,
            "SHRUNK:A" to 4_992,
            "SHRUNK:B" to 4_992,
            "SHRUNK:C" to 4_992,
        )

        /**
         * Las seis formas de repartir las generaciones 1, 2 y 3 entre `A`, `B` y `C`, en orden
         * lexicográfico.
         */
        val GENERATION_ORDERS = listOf(
            listOf(1L, 2L, 3L),
            listOf(1L, 3L, 2L),
            listOf(2L, 1L, 3L),
            listOf(2L, 3L, 1L),
            listOf(3L, 1L, 2L),
            listOf(3L, 2L, 1L),
        )

        /**
         * Con la hora 401 el ancla de la hora 400 tiene una hora; con la 700, más de siete días.
         */
        val NOW_HOURS = listOf(401L, 700L)

        const val HALF_WRITTEN_GENERATION = 99L
        const val NEW_RECORDS = 103L
        const val NEW_CATALOG = 53L
        val FNV_OFFSET: ULong = 0xCBF29CE484222325uL
        val FNV_PRIME: ULong = 0x100000001B3uL
    }
}
