package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.RetainedCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RetentionInput
import io.github.yesidrobayo.shopledger.domain.model.backup.RetentionPlan
import kotlin.random.Random
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La poda de WebDAV con los ejemplos de los documentos de respaldo, cada uno con hoy en el
 * 2026-11-01, y sus propiedades sobre copias al azar.
 */
class RetentionPolicyTest {
    @Test
    fun dailyUseKeepsTheNewestAndOnePerDayOfTheLastSevenWithCopies() {
        val copies =
            listOf(copy(122), copy(121), copy(120), copy(119, 1), copy(110, 2), copy(101, 3)) +
                listOf(copy(95, 4), copy(88, 5), copy(80, 6), copy(70, 7))
        assertPlan(
            copies,
            keep = listOf(122, 119, 110, 101, 95, 88, 80),
            prune = listOf(70, 120, 121),
        )
    }

    @Test
    fun fifteenDaysWithoutUseCountDaysWithCopiesNotCalendarDays() {
        val copies = listOf(copy(60)) + (59L downTo 52L).map { copy(it, (75 - it).toInt()) }
        assertPlan(copies, keep = listOf(60, 59, 58, 57, 56, 55, 54), prune = listOf(52, 53))
    }

    @Test
    fun theFirstDayKeepsOnlyTheNewest() {
        assertPlan(listOf(copy(5), copy(4), copy(3)), keep = listOf(5), prune = listOf(3, 4))
    }

    @Test
    fun afterKeepingThisPhoneTheOtherPhoneCopiesStay() {
        val copies =
            listOf(
                copy(301),
                copy(300, device = OTHER, pinned = true),
                copy(299, 1, device = OTHER),
            )
        assertPlan(copies, keep = listOf(301, 300, 299), prune = emptyList())
    }

    @Test
    fun theOtherPhoneLeavesTheWindowButItsPinnedCopyStays() {
        val copies =
            (317L downTo 311L).map { copy(it, (317 - it).toInt()) } +
                listOf(copy(310, 7), copy(300, 9, device = OTHER, pinned = true))
        assertPlan(copies, keep = (317L downTo 311L).toList() + 300, prune = listOf(310))
    }

    @Test
    fun anotherPhoneKeepsItsNewestCopyWithinTheWindow() {
        // Su copia más reciente es de un día que no queda entre los días de la regla: igual se
        // conserva por ser la de ese teléfono, si su día está en la ventana.
        val copies =
            listOf(copy(20), copy(19, device = OTHER), copy(18, 1), copy(17, 1, device = OTHER))
        assertPlan(copies, keep = listOf(20, 19, 18), prune = listOf(17))
        // Fuera de la ventana, no.
        val far =
            (30L downTo 24L).map { copy(it, (30 - it).toInt()) } + copy(10, 9, device = OTHER)
        assertPlan(far, keep = (30L downTo 24L).toList(), prune = listOf(10))
    }

    @Test
    fun aMistakenRestoreKeepsThePinnedHeadAndPrunesWhatItCovers() {
        val copies =
            listOf(
                copy(123, records = 700),
                copy(122, records = 1_000, pinned = true),
                copy(121, records = 990),
                copy(120, 1, records = 980),
            ) + (119L downTo 115L).map { copy(it, (121 - it).toInt(), records = 700) }
        assertPlan(
            copies,
            keep = listOf(123, 122, 120, 119, 118, 117, 116, 115),
            prune = listOf(121),
        )
    }

    @Test
    fun withoutThePinTheRecordsRuleStopsThePrune() {
        val copies =
            listOf(copy(123, records = 700), copy(122, records = 1_000), copy(121, records = 990)) +
                listOf(copy(120, 1, records = 980)) +
                (119L downTo 115L).map { copy(it, (121 - it).toInt(), records = 700) }
        val plan = retain(copies)
        assertTrue(plan.blockedByRecords)
        assertEquals(emptyList<RetainedCopy>(), plan.prune)
        assertEquals(copies.size, plan.keep.size)
    }

    @Test
    fun aPinWithAnotherKeyDoesNotCoverTheRecordsRule() {
        val copies =
            listOf(
                copy(101, records = 10),
                copy(100, records = 50, pinned = true, key = "K1"),
                copy(99, records = 50),
            )
        assertTrue(retain(copies).blockedByRecords)
    }

    @Test
    fun theFirstCopyWithANewKeyFreesNothingUntilOneIsValid() {
        val invalid =
            listOf(
                copy(90, valid = false, key = "K2"),
                copy(89, key = "K1"),
                copy(88, key = "K1"),
                copy(87),
            )
        assertPlan(invalid, keep = listOf(90, 89, 88, 87), prune = emptyList(), currentKey = "K2")
        val valid =
            listOf(
                copy(90, key = "K2"),
                copy(89, key = "K1"),
                copy(88, key = "K1"),
                copy(87, 1, key = "K1"),
            )
        assertPlan(valid, keep = listOf(90, 87), prune = listOf(88, 89), currentKey = "K2")
    }

    @Test
    fun turningEncryptionOffFreesTheEncryptedCopiesOnlyAfterAValidClearOne() {
        val invalid = listOf(copy(91, valid = false), copy(90, key = "K1"), copy(89, key = "K1"))
        assertPlan(invalid, keep = listOf(91, 90, 89), prune = emptyList())
        val valid =
            listOf(copy(91), copy(90, key = "K1"), copy(89, key = "K1"), copy(88, 1, key = "K1"))
        assertPlan(valid, keep = listOf(91, 88), prune = listOf(89, 90))
    }

    @Test
    fun anInvalidHeadNeverPrunesAValidCopy() {
        val copies =
            listOf(copy(211, valid = false), copy(210, 1, valid = false)) +
                (209L downTo 203L).map { copy(it, (211 - it).toInt()) }
        assertPlan(copies, keep = listOf(211L) + (209L downTo 203L), prune = listOf(210))
    }

    @Test
    fun aFatalHeadKeepsTheNewestCopyThatRestores() {
        val copies =
            listOf(copy(212, valid = false, fatal = true), copy(211, 1, valid = false)) +
                (209L downTo 203L).map { copy(it, (211 - it).toInt()) }
        assertPlan(copies, keep = listOf(212L, 211L) + (209L downTo 203L), prune = emptyList())
    }

    @Test
    fun aDamagedCopyCountsAsInvalid() {
        // La cabeza dañada no saca ninguna copia válida.
        val copies =
            listOf(copy(30), copy(29), copy(28, 1)) +
                (27L downTo 22L).map { copy(it, (29 - it).toInt()) }
        val plan = retain(copies, damaged = setOf(30L))
        assertTrue(plan.prune.isEmpty())
        // Una dañada que no es la cabeza no ocupa un día: el día lo toma otra.
        val older = listOf(copy(30), copy(29, 1), copy(28, 1))
        assertPlan(older, keep = listOf(30, 28), prune = listOf(29), damaged = setOf(29))
    }

    @Test
    fun theLastFullyReadCopyStays() {
        assertPlan(
            listOf(copy(122), copy(121), copy(120)),
            keep = listOf(122, 120),
            prune = listOf(121),
            fullVerify = 120,
        )
        // Sin ninguna leída completa, la poda sigue.
        assertPlan(listOf(copy(122), copy(121)), keep = listOf(122), prune = listOf(121))
    }

    @Test
    fun aDelayedClockKeepsTheCopiesItCannotDate() {
        assertPlan(
            listOf(copy(90, 2), copy(89), copy(88)),
            keep = listOf(90, 89, 88),
            prune = emptyList(),
        )
    }

    @Test
    fun aClockAYearBackKeepsTheRecentDaysAndAtMostSevenOddCopies() {
        val recent = (100L..107L).map { copy(it, (107 - it).toInt()) }
        val yearBefore = (108L..121L).map { copy(it, 365 + (121 - it).toInt()) }
        assertPlan(
            recent + yearBefore,
            keep = (121L downTo 115L).toList() + (107L downTo 101L),
            prune = (100L..114L).filter { it == 100L || it >= 108L },
        )
    }

    @Test
    fun anEmptyListKeepsAndPrunesNothing() {
        assertEquals(
            RetentionPlan(emptyList(), emptyList(), blockedByRecords = false),
            retain(emptyList()),
        )
    }

    @Test
    fun aTieInTheHeadKeepsEveryCopyOfIt() {
        val copies = listOf(copy(43), copy(43, device = OTHER), copy(42))
        assertPlan(copies, keep = listOf(43, 43), prune = listOf(42))
    }

    @Test
    fun aTieInTheHeadCountsItsLargestRecordsInAnyOrder() {
        // La cabeza con dos copias de registros e instantes distintos, en los dos órdenes posibles:
        // cuenta la de más registros, y la 41, con menos que esa, se poda.
        for ((other, own) in listOf(900L to 100L, 100L to 900L)) {
            val copies =
                listOf(
                    copy(43, daysAgo = if (other > own) 2 else 0, device = OTHER, records = other),
                    copy(43, daysAgo = if (other > own) 0 else 2, records = own),
                    copy(42, 3, records = 100),
                    copy(41, 3, records = 500),
                )
            assertPlan(copies, keep = listOf(43, 43, 42), prune = listOf(41))
        }
    }

    @Test
    fun randomListsNeverBreakTheRules() {
        val random = Random(2026_11_01)
        repeat(20_000) {
            val copies = randomCopies(random)
            val currentKey = listOf(null, "K1", "K2").random(random)
            val damaged = copies.map { it.generation }.filter { random.nextInt(12) == 0 }.toSet()
            val full = copies.randomOrNull(random)?.generation?.takeIf { random.nextBoolean() }
            val plan = retain(copies, currentKey, full, damaged)
            val pruned = plan.prune.toSet()
            assertEquals(copies.toSet(), plan.keep.toSet() + pruned)
            if (copies.isEmpty()) return@repeat
            val top = copies.maxOf { it.generation }
            val head = copies.filter { it.generation == top }
            // La cabeza y las fijadas nunca se podan.
            assertTrue(pruned.none { it.generation == top || it.pinned })
            // Con la cabeza inválida o dañada nunca sale una copia válida y sana.
            if (head.any { !it.valid || it.generation in damaged }) {
                assertTrue(pruned.none { it.valid && it.generation !in damaged })
            }
            // Sin una válida y sana con la clave vigente, nunca sale una con otra clave.
            if (copies.none { it.valid && it.generation !in damaged && it.keyId == currentKey }) {
                assertTrue(pruned.none { it.keyId != currentKey })
            }
            // Nunca sale una copia con más registros que la cabeza sin una fijada que los cubra.
            val headRecords = head.maxOf { it.records }
            val cover = copies.filter {
                it.pinned && it.keyId == currentKey
            }.maxOfOrNull { it.records }
            assertTrue(
                pruned.none {
                    it.records > headRecords &&
                        (cover == null || it.records > cover)
                },
            )
            // Con la cabeza fatal, queda la copia más reciente que se restaura.
            if (head.any { it.fatal }) {
                copies.filterNot { it.fatal }.maxOfOrNull { it.generation }?.let { newest ->
                    assertTrue(pruned.none { it.generation == newest })
                }
            }
            full?.let { generation -> assertTrue(pruned.none { it.generation == generation }) }
            // El orden de la lista nunca decide.
            assertEquals(plan, retain(copies.shuffled(random), currentKey, full, damaged))
            assertFalse(plan.blockedByRecords && pruned.isNotEmpty())
        }
    }

    private fun randomCopies(random: Random): List<RetainedCopy> {
        val count = random.nextInt(0, 25)
        var generation = 1L
        var day = 0
        return List(count) {
            generation += random.nextInt(1, 3)
            day += if (random.nextInt(8) == 0) -random.nextInt(1, 400) else random.nextInt(0, 3)
            val fatal = random.nextInt(15) == 0
            copy(
                generation,
                daysAgo = 500 - day,
                device = if (random.nextInt(6) == 0) OTHER else OWN,
                valid = !fatal && random.nextInt(6) != 0,
                fatal = fatal,
                pinned = random.nextInt(10) == 0,
                key = listOf(null, "K1", "K2").random(random),
                records = random.nextLong(0, 1_000),
            )
        }
    }

    private fun assertPlan(
        copies: List<RetainedCopy>,
        keep: List<Long>,
        prune: List<Long>,
        currentKey: String? = null,
        fullVerify: Long? = null,
        damaged: Set<Long> = emptySet(),
    ) {
        val plan = retain(copies, currentKey, fullVerify, damaged)
        assertFalse(plan.blockedByRecords)
        assertEquals(keep, plan.keep.map { it.generation })
        assertEquals(prune, plan.prune.map { it.generation })
    }

    private fun retain(
        copies: List<RetainedCopy>,
        currentKey: String? = null,
        fullVerify: Long? = null,
        damaged: Set<Long> = emptySet(),
    ): RetentionPlan =
        RetentionPolicy.retain(RetentionInput(copies, OWN, currentKey, fullVerify, damaged))

    private fun copy(
        generation: Long,
        daysAgo: Int = 0,
        device: String = OWN,
        valid: Boolean = true,
        fatal: Boolean = false,
        pinned: Boolean = false,
        key: String? = null,
        records: Long = 100,
    ) = RetainedCopy(
        key = "g$generation-$device",
        generation = generation,
        deviceId = device,
        createdAt = requireNotNull(CopyInstant.parse("20261101T120000Z")).minusDays(daysAgo),
        valid = valid,
        fatal = fatal,
        pinned = pinned,
        keyId = key,
        records = records,
    )

    private fun CopyInstant.minusDays(days: Int): CopyInstant {
        val seconds = epochSeconds - days * 86_400L
        return requireNotNull(CopyInstant.of(Instant.fromEpochSeconds(seconds)))
    }

    private companion object {
        const val OWN = "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd"
        const val OTHER = "0c4d2e7a-91b3-4f5e-a6d7-8899aabbccdd"
    }
}
