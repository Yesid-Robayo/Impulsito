package io.github.yesidrobayo.shopledger.data.repository

import androidx.sqlite.SQLiteException
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.experience.ModuleUsageRow
import io.github.yesidrobayo.shopledger.data.transaction.SqliteFailures
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.port.ModuleUsageSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Las herramientas en uso, leídas con una sola consulta de solo lectura. Una falla de la base sale
 * tipada y sin su texto, que puede traer SQL: quien la recibe deja todas las herramientas como no
 * usadas.
 */
internal class RoomModuleUsageSource(database: AppDatabase) : ModuleUsageSource {
    private val usage = database.moduleUsageDao()

    override fun observe(): Flow<Set<BusinessTool>> = usage
        .observe()
        .map(::toolsInUse)
        .catch { failure ->
            if (failure !is SQLiteException) throw failure
            // Un error de SQL es de programación y sigue tal cual; solo el disco sale como falla.
            val storage =
                SqliteFailures.resultCode(failure)?.let(SqliteFailures::storageFailureOf)
                    ?: throw failure
            throw StorageFailureException(storage)
        }

    private fun toolsInUse(row: ModuleUsageRow): Set<BusinessTool> = buildSet {
        fun mark(tool: BusinessTool, flag: Long) {
            if (flag != 0L) add(tool)
        }
        mark(BusinessTool.CUSTOMERS, row.customers)
        mark(BusinessTool.LOYALTY, row.loyalty)
        mark(BusinessTool.MONEY_ACCOUNTS, row.moneyAccounts)
        mark(BusinessTool.PAYABLES, row.payables)
        mark(BusinessTool.CASH_REGISTER, row.cashRegister)
        mark(BusinessTool.HELD_SALES, row.heldSales)
        mark(BusinessTool.QUOTES, row.quotes)
        mark(BusinessTool.ORDERS, row.orders)
        mark(BusinessTool.PENDING_FISCAL, row.pendingFiscal)
        mark(BusinessTool.PRICE_LISTS, row.priceLists)
        mark(BusinessTool.REFERENCE_CURRENCY, row.referenceCurrency)
        mark(BusinessTool.UNITS, row.units)
        mark(BusinessTool.COMBOS, row.combos)
        mark(BusinessTool.CUSTOM_FIELDS, row.customFields)
        mark(BusinessTool.LOTS, row.lots)
        mark(BusinessTool.LOCATIONS, row.locations)
        mark(BusinessTool.STOCK_COUNT, row.stockCount)
        mark(BusinessTool.PRODUCTION, row.production)
        mark(BusinessTool.SUPPLIER_RETURNS, row.supplierReturns)
        mark(BusinessTool.GOALS_AND_LIMITS, row.goalsAndLimits)
        mark(BusinessTool.RECURRING_EXPENSES, row.recurringExpenses)
        mark(BusinessTool.PRICE_INDEX, row.priceIndex)
    }
}
