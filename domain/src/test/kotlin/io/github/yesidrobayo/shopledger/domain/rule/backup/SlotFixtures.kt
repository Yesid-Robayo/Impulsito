package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotChoice
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotContent
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotTable
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Instantes y ranuras de prueba contados en horas desde un origen fijo, como en el modelo del
 * diseño.
 */
internal object SlotFixtures {
    private val origin = Instant.parse("2026-01-01T00:00:00Z")

    /** Una semana y una hora: el ancla ya venció. */
    const val LATE = 7 * 24 + 1L

    fun hour(hours: Long): Instant = origin + hours.hours

    fun valid(
        generation: Long,
        at: Long = 0,
        records: Long = 100,
        catalog: Long = 50,
    ): SlotContent = SlotContent.Holding(SlotCopy(generation, hour(at), true, records, catalog))

    fun invalid(
        generation: Long,
        at: Long = 0,
        records: Long = 100,
        catalog: Long = 50,
    ): SlotContent = SlotContent.Holding(SlotCopy(generation, hour(at), false, records, catalog))

    val halfWritten: SlotContent = SlotContent.HalfWritten(writingGeneration = 9)

    val empty: SlotContent = SlotContent.Empty

    fun candidate(valid: Boolean = true, records: Long = 100, catalog: Long = 50): SlotCandidate =
        SlotCandidate(NewCopyMarks(valid = valid, fatal = false), records, catalog)

    fun fatal(records: Long = 100, catalog: Long = 50): SlotCandidate =
        SlotCandidate(NewCopyMarks(valid = false, fatal = true), records, catalog)

    fun choose(
        a: SlotContent,
        b: SlotContent,
        c: SlotContent,
        candidate: SlotCandidate,
        now: Long,
        headFileRef: BackupSlot? = null,
    ): SlotChoice = SlotPlan.choose(SlotTable(a, b, c), candidate, hour(now), headFileRef)

    fun write(slot: BackupSlot): SlotChoice = SlotChoice.Write(slot)
}
