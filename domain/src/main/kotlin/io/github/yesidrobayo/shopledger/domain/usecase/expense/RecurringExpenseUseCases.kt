package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.RecurringExpenseRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurrenceRules
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurringExpenseCheck
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurringExpenseRules
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurringRegistration
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Los gastos recurrentes vigentes, separados: los que ya toca confirmar y los programados.
 *
 * [today] es `null` si el teléfono no conoce la zona del negocio: sin saber qué día es, ninguno se
 * propone, y todos van como programados.
 */
data class RecurringExpenseOverview(
    val today: BusinessDate?,
    val due: List<RecurringExpense>,
    val scheduled: List<RecurringExpense>,
) {
    /** Si no hay ninguno vigente. */
    val isEmpty: Boolean get() = due.isEmpty() && scheduled.isEmpty()
}

/**
 * Los gastos recurrentes de su pantalla, y cuántos esperan confirmación en los gastos y en el
 * centro `Negocio`.
 *
 * - **Por confirmar:** los vigentes cuya fecha llegó o pasó, del más atrasado al más reciente. Un
 *   gasto con varios vencimientos atrasados sale una sola vez, con el más viejo: al registrarlo u
 *   omitirlo aparece el siguiente si su fecha ya pasó.
 * - **Programados:** los demás vigentes, del más próximo al más lejano.
 *
 * Hoy es la fecha de negocio del instante actual, y se vuelve a mirar con cada emisión de
 * [invoke]`(dayChecks)`. Nada de esto depende del permiso de avisos.
 */
class ObserveRecurringExpensesUseCase(
    private val recurring: RecurringExpenseRepository,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
) {
    operator fun invoke(dayChecks: Flow<Unit> = flowOf(Unit)): Flow<RecurringExpenseOverview> =
        combine(
            recurring.observe(ArchiveFilter.ACTIVE),
            calendar.observe(),
            dayChecks,
        ) { active, calendarResult, _ ->
            val today = when (calendarResult) {
                is BusinessCalendarResult.Ready ->
                    calendarResult.calendar.businessDateOf(clock.now())

                is BusinessCalendarResult.UnknownZone -> null
            }
            overviewOf(active, today)
        }.distinctUntilChanged()

    /** Los archivados, en el orden en que se crearon: ya no se proponen. */
    fun archived(): Flow<List<RecurringExpense>> = recurring.observe(ArchiveFilter.ARCHIVED)

    private fun overviewOf(
        active: List<RecurringExpense>,
        today: BusinessDate?,
    ): RecurringExpenseOverview {
        val byDate = active.sortedWith(compareBy({ it.details.nextDueDate }, { it.id }))
        val (due, scheduled) = byDate.partition { today != null && it.isDue(today) }
        return RecurringExpenseOverview(today, due, scheduled)
    }
}

/** Cómo termina crear o editar un gasto recurrente. Salvo [Saved], no se escribió nada. */
sealed interface SaveRecurringExpenseResult {
    /** Quedó guardado, o ya estaba exactamente así. */
    data class Saved(val recurringExpenseId: Long) : SaveRecurringExpenseResult

    /** No se guardó nada: cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<RecurringExpenseInputError>) : SaveRecurringExpenseResult

    /** El gasto recurrente que se editaba ya no existe. */
    data object NotFound : SaveRecurringExpenseResult

    /** La categoría elegida ya no existe. */
    data object CategoryNotFound : SaveRecurringExpenseResult

    /**
     * La categoría elegida está archivada. Un gasto recurrente que ya la tenía la conserva al
     * editarlo; uno nuevo, o uno que cambia a ella, no.
     */
    data object CategoryArchived : SaveRecurringExpenseResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : SaveRecurringExpenseResult
}

/**
 * Crea un gasto recurrente, o edita uno, en una sola transacción. Guardarlo no registra ningún
 * gasto: solo deja la propuesta para su fecha. Una edición que no cambia nada no escribe.
 */
class SaveRecurringExpenseUseCase(
    private val transactions: TransactionRunner,
    private val recurring: RecurringExpenseRepository,
    private val categories: ExpenseCategoryRepository,
    private val clock: Clock,
) {
    /** Crea el de [draft] si [editingId] es nulo, o edita ese. */
    suspend operator fun invoke(
        editingId: Long?,
        draft: RecurringExpenseDraft,
    ): SaveRecurringExpenseResult = storageSafe(SaveRecurringExpenseResult::StorageFailed) {
        transactions.write { save(editingId, draft) }
    }

    private suspend fun save(
        editingId: Long?,
        draft: RecurringExpenseDraft,
    ): TxResult<SaveRecurringExpenseResult> {
        val current = editingId?.let {
            recurring.findById(it) ?: return reject(SaveRecurringExpenseResult.NotFound)
        }
        val details = when (val check = RecurringExpenseRules.check(draft)) {
            is RecurringExpenseCheck.Invalid ->
                return reject(SaveRecurringExpenseResult.Invalid(check.errors))

            is RecurringExpenseCheck.Valid -> check.details
        }
        val category = categories.findById(details.expenseCategoryId)
            ?: return reject(SaveRecurringExpenseResult.CategoryNotFound)
        val keepsCategory = current?.details?.expenseCategoryId == category.id
        if (category.isArchived && !keepsCategory) {
            return reject(SaveRecurringExpenseResult.CategoryArchived)
        }
        val now = clock.now()
        if (current == null) {
            val id = recurring.insert(details, now)
            return TxResult.Commit(SaveRecurringExpenseResult.Saved(id))
        }
        if (current.details == details) {
            return TxResult.Rollback(SaveRecurringExpenseResult.Saved(current.id))
        }
        recurring.update(current.id, details, now)
        return TxResult.Commit(SaveRecurringExpenseResult.Saved(current.id))
    }

    private fun reject(result: SaveRecurringExpenseResult): TxResult<SaveRecurringExpenseResult> =
        TxResult.Rollback(result)
}

/** Cómo termina omitir un vencimiento. Salvo [Skipped], no se escribió nada. */
sealed interface SkipRecurringExpenseResult {
    /** No se registró nada, y la propuesta pasó a [nextDueDate]. */
    data class Skipped(val nextDueDate: BusinessDate) : SkipRecurringExpenseResult

    /** El gasto recurrente ya no existe. */
    data object NotFound : SkipRecurringExpenseResult

    /** Se archivó: ya no se propone. */
    data object Archived : SkipRecurringExpenseResult

    /**
     * Ese vencimiento ya se registró u omitió, quizá en otra pantalla: la propuesta está en otra
     * fecha, y omitir de nuevo saltaría uno más.
     */
    data object AlreadyHandled : SkipRecurringExpenseResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : SkipRecurringExpenseResult
}

/**
 * Omite un vencimiento: no registra ningún gasto y pasa la propuesta a la fecha siguiente, en una
 * sola transacción. Es lo que se hace con un mes en que el gasto no se pagó.
 */
class SkipRecurringExpenseUseCase(
    private val transactions: TransactionRunner,
    private val recurring: RecurringExpenseRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(occurrence: RecurringOccurrence): SkipRecurringExpenseResult =
        storageSafe(SkipRecurringExpenseResult::StorageFailed) {
            transactions.write { skip(occurrence) }
        }

    private suspend fun skip(
        occurrence: RecurringOccurrence,
    ): TxResult<SkipRecurringExpenseResult> {
        val current = recurring.findById(occurrence.recurringExpenseId)
            ?: return TxResult.Rollback(SkipRecurringExpenseResult.NotFound)
        if (current.isArchived) return TxResult.Rollback(SkipRecurringExpenseResult.Archived)
        val details = current.details
        if (details.nextDueDate != occurrence.dueDate) {
            return TxResult.Rollback(SkipRecurringExpenseResult.AlreadyHandled)
        }
        val next =
            RecurrenceRules.next(details.recurrence, details.dayOfPeriod, details.nextDueDate)
        recurring.advance(current.id, next, clock.now())
        return TxResult.Commit(SkipRecurringExpenseResult.Skipped(next))
    }
}

/** Cómo termina archivar o reactivar un gasto recurrente. Salvo [Done], no se escribió nada. */
sealed interface RecurringExpenseLifecycleResult {
    data object Done : RecurringExpenseLifecycleResult

    /** Ya no existe. */
    data object NotFound : RecurringExpenseLifecycleResult

    /** Ya estaba como se pedía. Nada cambió. */
    data object Unchanged : RecurringExpenseLifecycleResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RecurringExpenseLifecycleResult
}

/**
 * Archiva un gasto recurrente, que deja de proponerse, o lo reactiva. Los gastos que ya se
 * registraron desde él no cambian, y siguen apuntándolo.
 */
class SetRecurringExpenseArchivedUseCase(
    private val transactions: TransactionRunner,
    private val recurring: RecurringExpenseRepository,
    private val clock: Clock,
) {
    /** Lo archiva si [archived], o lo reactiva. */
    suspend operator fun invoke(id: Long, archived: Boolean): RecurringExpenseLifecycleResult =
        storageSafe(RecurringExpenseLifecycleResult::StorageFailed) {
            transactions.write { change(id, archived) }
        }

    private suspend fun change(
        id: Long,
        archived: Boolean,
    ): TxResult<RecurringExpenseLifecycleResult> {
        val current = recurring.findById(id)
            ?: return TxResult.Rollback(RecurringExpenseLifecycleResult.NotFound)
        if (current.isArchived == archived) {
            return TxResult.Rollback(RecurringExpenseLifecycleResult.Unchanged)
        }
        val now = clock.now()
        recurring.setArchivedAt(id, now.takeIf { archived }, now)
        return TxResult.Commit(RecurringExpenseLifecycleResult.Done)
    }
}

/**
 * Lo que trae registrar el vencimiento actual de un gasto recurrente: el gasto nuevo de la
 * pantalla de gastos con sus datos puestos, que la persona puede cambiar antes de guardar. Solo
 * lee.
 */
class PrepareRecurringRegistrationUseCase(
    private val recurring: RecurringExpenseRepository,
    private val categories: ExpenseCategoryRepository,
) {
    /** El registro de [recurringExpenseId], o `null` si ya no existe o está archivado. */
    suspend operator fun invoke(recurringExpenseId: Long): RecurringRegistration? {
        val current = recurring.findById(recurringExpenseId)?.takeIf { !it.isArchived }
            ?: return null
        val category = categories.findById(current.details.expenseCategoryId)
        return RecurringExpenseRules.registrationOf(current, category)
    }
}
