package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las categorías de gasto. Las iniciales las crea la base al nacer, con su clave; las demás, la
 * persona.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface ExpenseCategoryRepository {
    /** Las categorías de [filter], en el orden en que se crearon. */
    fun observe(filter: ArchiveFilter): Flow<List<ExpenseCategory>>

    suspend fun findById(id: Long): ExpenseCategory?

    /** La categoría sin archivar con ese nombre normalizado, para no repetir nombres. */
    suspend fun findActiveByNormalizedName(normalizedName: String): ExpenseCategory?

    /** Si tiene gastos, aunque estén anulados. Con gastos ya no se borra: solo se archiva. */
    suspend fun hasExpenses(id: Long): Boolean

    /** Crea una categoría de la persona, sin clave inicial, y devuelve su id. */
    suspend fun insert(details: ExpenseCategoryDetails, createdAt: Instant): Long

    suspend fun update(id: Long, details: ExpenseCategoryDetails, updatedAt: Instant)

    /** La archiva con [archivedAt], o la reactiva con `null`. La de otros gastos nunca se archiva. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)

    /** Elimina una categoría sin gastos. La de otros gastos nunca se elimina. */
    suspend fun delete(id: Long)
}
