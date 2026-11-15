package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los gastos recurrentes: plantillas que la app propone en su fecha. Ninguna registra un gasto
 * sola.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface RecurringExpenseRepository {
    /** Los vigentes o los archivados, en el orden en que se crearon. */
    fun observe(filter: ArchiveFilter): Flow<List<RecurringExpense>>

    suspend fun findById(id: Long): RecurringExpense?

    /** Guarda uno nuevo, vigente, y devuelve su id. */
    suspend fun insert(details: RecurringExpenseDetails, createdAt: Instant): Long

    /** Cambia lo que escribe la persona, sin tocar su archivo ni su creación. */
    suspend fun update(id: Long, details: RecurringExpenseDetails, updatedAt: Instant)

    /** Pasa al vencimiento siguiente, [nextDueDate], al confirmar u omitir el actual. */
    suspend fun advance(id: Long, nextDueDate: BusinessDate, updatedAt: Instant)

    /** Lo archiva con [archivedAt], o lo reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)
}
