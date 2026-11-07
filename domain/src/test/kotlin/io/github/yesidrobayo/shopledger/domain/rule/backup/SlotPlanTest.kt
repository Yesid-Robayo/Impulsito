package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot.A
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot.B
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot.C
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotChoice
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotContent
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotTable
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.candidate
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.choose
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.empty
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.halfWritten
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.hour
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.invalid
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.valid
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.write
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lo que la tabla de casos no cubre: la sucesión de la regla, la cabeza recordada y el reloj. */
class SlotPlanTest {
    @Test
    fun `seven copies rotate as the business rule example says`() {
        // Copia, hora en que se crea y ranura que espera la sucesión de la regla de rotación.
        val expected = listOf(0L to C, 1L to A, 2L to B, 3L to A, 4L to B, 168L to C, 169L to A)
        var slots = SlotTable(empty, empty, empty)
        val chosen = expected.mapIndexed { index, (at, _) ->
            val choice = SlotPlan.choose(slots, candidate(), hour(at), headFileRef = null)
            val slot = (choice as SlotChoice.Write).slot
            slots = slots.with(slot, valid(index + 1L, at))
            slot
        }

        assertEquals(expected.map { it.second }, chosen)
    }

    @Test
    fun `the anchor is renewed at exactly seven days and not a second before`() {
        val anchorAt = hour(0)
        val slots =
            SlotTable(valid(2), valid(3), SlotContent.Holding(SlotCopy(1, anchorAt, true, 100, 50)))

        assertEquals(write(C), SlotPlan.choose(slots, candidate(), anchorAt + 7.days, null))
        assertEquals(
            write(A),
            SlotPlan.choose(slots, candidate(), anchorAt + 7.days - 1.seconds, null),
        )
    }

    @Test
    fun `an invalid anchor is renewed by the next valid copy even within the hour`() {
        // Una copia dudosa vale más que ninguna, pero no debe quedar como lo más viejo que hay.
        assertEquals(write(C), choose(valid(3), valid(2), invalid(1), candidate(), now = 1))
    }

    @Test
    fun `a clock set back never renews the anchor by age`() {
        // El ancla dice haberse escrito dentro de un año: la edad es negativa, y la regla de los
        // siete días no la renueva aunque la distancia sea mayor. Si contara la distancia, un reloj
        // que salta atrás renovaría el ancla en cada copia.
        val slots = SlotTable(valid(2), valid(3), valid(1, at = 365 * 24))

        assertEquals(write(A), choose(slots.a, slots.b, slots.c, candidate(), now = 0))
    }

    @Test
    fun `the remembered head is protected even when it is not the newest slot`() {
        // Murió entre confirmar la ranura y escribir la cabeza: el estado aún recuerda A, y B ya
        // tiene la generación mayor. Las dos se protegen, y la copia va al ancla.
        val chosen = choose(valid(2), valid(3), valid(1), candidate(), now = 1, headFileRef = A)

        assertEquals(write(C), chosen)
    }

    @Test
    fun `with the remembered head, the newest and the latest valid apart, nothing is written`() {
        // Ninguna ranura protege datos de más, así que el conflicto no tiene ranura que mostrar.
        val chosen =
            choose(valid(2), invalid(3), valid(1), candidate(false), now = 1, headFileRef = C)

        assertEquals(SlotChoice.AllLocked(conflictSlot = null), chosen)
    }

    @Test
    fun `a remembered head is protected even when that slot is open`() {
        val chosen = choose(empty, valid(2), valid(1), candidate(false), now = 1, headFileRef = A)

        assertEquals(write(C), chosen)
    }

    @Test
    fun `a valid copy prefers the open anchor and an invalid one the first open slot`() {
        assertEquals(write(C), choose(halfWritten, valid(2), empty, candidate(), now = 1))
        assertEquals(write(A), choose(halfWritten, valid(2), empty, candidate(false), now = 1))
    }

    @Test
    fun `an invalid copy never renews an old anchor while the pair has room`() {
        val chosen = choose(valid(3), valid(2), invalid(1), candidate(false), now = 500)

        assertEquals(write(B), chosen)
    }

    @Test
    fun `the conflict shows the reserve with most records, and among equals the larger catalog`() {
        // Cabeza inválida en A y la válida más reciente en B. El ancla supera a las dos en
        // registros y en catálogo.
        val chosen = choose(
            invalid(4, records = 10, catalog = 10),
            valid(3, records = 10, catalog = 10),
            invalid(2, records = 900, catalog = 400),
            candidate(valid = false, records = 10, catalog = 10),
            now = 1,
        )

        assertEquals(SlotChoice.AllLocked(C), chosen)
    }

    @Test
    fun `equal reserves in records are told apart by catalog`() {
        // A es la reserva por registros, por tener mayor generación entre las de 900, y B la de
        // catálogo. Con los mismos registros, el conflicto muestra la de más catálogo.
        val chosen = choose(
            valid(2, records = 900, catalog = 10),
            invalid(1, records = 900, catalog = 400),
            invalid(3, records = 5, catalog = 5),
            candidate(valid = false, records = 5, catalog = 5),
            now = 1,
        )

        assertEquals(SlotChoice.AllLocked(B), chosen)
    }

    private fun SlotTable.with(slot: BackupSlot, content: SlotContent): SlotTable = when (slot) {
        A -> copy(a = content)
        B -> copy(b = content)
        C -> copy(c = content)
    }
}
