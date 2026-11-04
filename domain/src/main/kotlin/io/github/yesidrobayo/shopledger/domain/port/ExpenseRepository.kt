package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.expense.Expense
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import kotlinx.coroutines.flow.Flow

/**
 * Los gastos. Un gasto registrado no se edita: solo se anula.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface ExpenseRepository {
    /** Los gastos de [period], por fecha de negocio, del más nuevo al más antiguo. */
    fun observeIn(period: ReportPeriod): Flow<List<Expense>>

    suspend fun findById(id: Long): Expense?

    /** Guarda el gasto, activo, y devuelve su id. */
    suspend fun insert(expense: NewExpense): Long

    /** Anula un gasto activo. Es lo único que cambia en él después de registrarse. */
    suspend fun markVoided(id: Long, voiding: Voiding)
}
