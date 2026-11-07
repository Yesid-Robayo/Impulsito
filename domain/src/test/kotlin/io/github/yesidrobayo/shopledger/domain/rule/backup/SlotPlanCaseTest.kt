package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot.A
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot.B
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot.C
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotChoice
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotContent
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.LATE
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.candidate
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.choose
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.empty
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.fatal
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.halfWritten
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.invalid
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.valid
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotFixtures.write
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Los casos dirigidos del modelo del diseño, uno por regla y por caso límite, con el resultado que
 * el modelo espera. Las horas cuentan desde un origen fijo, y la copia nueva tiene 100 registros y
 * 50 productos salvo que el caso diga otra cosa.
 */
@RunWith(Parameterized::class)
class SlotPlanCaseTest(private val case: Case) {
    @Test
    fun `the plan picks what the design model expects`() {
        assertEquals(
            case.name,
            case.expected,
            choose(case.a, case.b, case.c, case.candidate, case.now),
        )
    }

    class Case(
        val name: String,
        val a: SlotContent,
        val b: SlotContent,
        val c: SlotContent,
        val candidate: SlotCandidate,
        val now: Long,
        val expected: SlotChoice,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private val shrunkTo = { slot: BackupSlot -> SlotChoice.AllLocked(slot) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> = listOf(
            Case(
                "todo vacío: la copia válida va al ancla",
                empty,
                empty,
                empty,
                candidate(),
                500,
                write(C),
            ),
            Case(
                "todo vacío: la copia inválida va a A",
                empty,
                empty,
                empty,
                candidate(valid = false),
                500,
                write(A),
            ),
            Case(
                "una copia fatal nunca se escribe",
                empty,
                empty,
                empty,
                fatal(),
                500,
                SlotChoice.FatalCopy,
            ),
            Case(
                "tampoco con las ranuras llenas",
                valid(1),
                valid(2),
                valid(3),
                fatal(),
                500,
                SlotChoice.FatalCopy,
            ),
            Case(
                "el ancla con la cabeza y 8 días: va a A",
                empty,
                empty,
                valid(1),
                candidate(),
                LATE,
                write(A),
            ),
            Case(
                "el ancla con 8 días y sin la cabeza: se renueva",
                valid(2),
                valid(3),
                valid(1),
                candidate(),
                LATE,
                write(C),
            ),
            Case(
                "el ancla con 6 días: va a la más antigua",
                valid(2),
                valid(3),
                valid(1),
                candidate(),
                144,
                write(A),
            ),
            Case("la más antigua es B", valid(3), valid(2), valid(1), candidate(), 1, write(B)),
            Case(
                "la cabeza en el ancla no se toca",
                valid(4),
                valid(3),
                valid(5),
                candidate(),
                100,
                write(B),
            ),
            Case(
                "única válida vieja en el ancla, copia válida",
                invalid(2),
                invalid(3),
                valid(1),
                candidate(),
                LATE,
                write(A),
            ),
            Case(
                "única válida vieja en el ancla, copia inválida",
                invalid(2),
                invalid(3),
                valid(1),
                candidate(valid = false),
                LATE,
                write(A),
            ),
            Case(
                "cabeza inválida y válida en B: la inválida va al ancla vacía",
                invalid(3),
                valid(2),
                empty,
                candidate(false),
                500,
                write(C),
            ),
            Case(
                "cabeza inválida y válida en B: la inválida va al ancla inválida",
                invalid(3),
                valid(2),
                invalid(1),
                candidate(false),
                500,
                write(C),
            ),
            Case(
                "el ancla inválida se renueva con la primera válida",
                valid(3),
                valid(2),
                invalid(1),
                candidate(),
                500,
                write(C),
            ),
            Case(
                "la ranura a medias va antes que otra",
                halfWritten,
                valid(2),
                valid(3),
                candidate(),
                500,
                write(A),
            ),
            Case(
                "también con una copia inválida",
                valid(2),
                halfWritten,
                valid(3),
                candidate(false),
                500,
                write(B),
            ),
            Case(
                "el ancla a medias se usa con una copia inválida",
                valid(2),
                valid(3),
                halfWritten,
                candidate(false),
                500,
                write(C),
            ),
            Case(
                "una vacía gana a un ancla vencida",
                empty,
                valid(3),
                valid(1),
                candidate(),
                LATE,
                write(A),
            ),
            Case(
                "restauró antes: la cabeza con más registros sigue",
                valid(3, records = 900),
                valid(2, records = 800),
                valid(1, records = 500),
                candidate(records = 505),
                1,
                write(B),
            ),
            Case(
                "reserva en A: la copia va al ancla aunque tenga una hora",
                valid(3, records = 900),
                valid(4, records = 505),
                valid(1, records = 500),
                candidate(records = 510),
                1,
                write(C),
            ),
            Case(
                "reserva en A: luego vuelve a B",
                valid(3, records = 900),
                valid(4, records = 505),
                valid(5, records = 510),
                candidate(records = 515),
                1,
                write(B),
            ),
            Case(
                "cabeza, válida y reserva distintas: conflicto",
                invalid(4, records = 505),
                valid(3, records = 500),
                invalid(2, records = 900),
                candidate(valid = false, records = 510),
                1,
                shrunkTo(C),
            ),
            Case(
                "la reserva deja de serlo al alcanzarla",
                valid(3, records = 900),
                valid(4, records = 905),
                valid(5, records = 910),
                candidate(records = 915),
                1,
                write(A),
            ),
            Case(
                "reserva por catálogo, sin registros",
                valid(3, records = 0, catalog = 400),
                valid(4, records = 0, catalog = 10),
                valid(5, records = 0, catalog = 10),
                candidate(records = 0, catalog = 10),
                1,
                write(B),
            ),
            Case(
                "dos reservas, por registros y por catálogo: conflicto en la de más registros",
                valid(3, records = 900, catalog = 10),
                valid(4, records = 0, catalog = 400),
                valid(5, records = 0, catalog = 10),
                candidate(records = 5, catalog = 10),
                1,
                shrunkTo(A),
            ),
            Case(
                "la reserva de catálogo se suelta al alcanzarla",
                valid(3, records = 0, catalog = 400),
                valid(4, records = 0, catalog = 395),
                valid(5, records = 0, catalog = 398),
                candidate(records = 0, catalog = 400),
                1,
                write(A),
            ),
            Case(
                "una ranura que supera en los dos conteos es una sola reserva",
                valid(3, records = 900, catalog = 400),
                valid(4, records = 800, catalog = 300),
                valid(5, records = 10, catalog = 10),
                candidate(records = 10, catalog = 10),
                1,
                write(B),
            ),
            // Los ejemplos de la tabla de casos del diseño que no repiten uno de arriba.
            Case(
                "ya hay otra válida, y el ancla vieja se renueva",
                valid(4),
                invalid(3),
                valid(1),
                candidate(),
                LATE,
                write(C),
            ),
            Case(
                "copias inválidas seguidas: la inválida va al ancla",
                invalid(5),
                valid(4),
                valid(3),
                candidate(false),
                1,
                write(C),
            ),
            Case(
                "las tres protegidas con una copia inválida: conflicto",
                invalid(20, records = 505),
                valid(19, records = 500),
                invalid(18, records = 900),
                candidate(valid = false, records = 510),
                1,
                shrunkTo(C),
            ),
            Case(
                "sin movimientos: restauró la copia con menos catálogo",
                valid(2, records = 0, catalog = 200),
                valid(3, records = 0, catalog = 400),
                valid(1, records = 0, catalog = 10),
                candidate(records = 0, catalog = 10),
                1,
                write(A),
            ),
            Case(
                "la siguiente: la de 400 productos es la reserva",
                valid(4, records = 0, catalog = 10),
                valid(3, records = 0, catalog = 400),
                valid(1, records = 0, catalog = 10),
                candidate(records = 0, catalog = 10),
                1,
                write(C),
            ),
            Case(
                "restauró por error la copia del ancla",
                valid(12, records = 1_000),
                valid(11, records = 990),
                valid(10, records = 700),
                candidate(records = 705),
                1,
                write(B),
            ),
            Case(
                "la siguiente: A es la reserva y el ancla entra aunque sea reciente",
                valid(12, records = 1_000),
                valid(13, records = 705),
                valid(10, records = 700),
                candidate(records = 710),
                1,
                write(C),
            ),
            Case(
                "la siguiente: A sigue de reserva hasta que la copia llegue a 1000",
                valid(12, records = 1_000),
                valid(13, records = 705),
                valid(14, records = 710),
                candidate(records = 715),
                1,
                write(B),
            ),
            Case(
                "la copia nueva iguala a la reserva en los dos conteos: deja de serlo",
                valid(3, records = 900, catalog = 400),
                valid(4, records = 500, catalog = 300),
                valid(5, records = 500, catalog = 300),
                candidate(records = 900, catalog = 400),
                1,
                write(A),
            ),
        )
    }
}
