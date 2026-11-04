package io.github.yesidrobayo.shopledger.data

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.repository.RoomAppSettingsRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomCategoryRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomExpenseRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomLowStockAlertRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomPriceHistoryRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomProductRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomPurchaseRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSaleRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomStockLedgerRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSupplierRepository
import io.github.yesidrobayo.shopledger.data.transaction.BackupStateChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.CommitListener
import io.github.yesidrobayo.shopledger.data.transaction.DatabaseStorageSpace
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.data.transaction.RoomTransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Los puertos de `:domain` sobre una base ya abierta: lo único que la capa de datos entrega a los
 * casos de uso. Las clases de Room no salen de este módulo.
 *
 * Solo existe después de abrir la base, y solo lo crea [DataGraph.open]: un repositorio no puede
 * consultar la base antes de que el arranque decida que abrirla es seguro, porque Room crearía una
 * vacía si el archivo faltara.
 *
 * Todos comparten la misma base. Las escrituras que cuentan como cambio del negocio van dentro de
 * [transactions].
 */
class DataPorts internal constructor(
    database: AppDatabase,
    databaseFile: File,
    ioContext: CoroutineContext,
    clock: Clock,
) {
    /**
     * Abre cada escritura del negocio en una sola transacción, y cuenta el cambio para la copia.
     *
     * Dos partes esperan a las tareas que las construyen. Mientras no hay restauración, nadie toma
     * su cerrojo. Mientras no hay respaldo programado, una confirmación no avisa a nadie: el cambio
     * igual queda contado en la base, y el respaldo lo verá al llegar.
     */
    val transactions: TransactionRunner =
        RoomTransactionRunner(
            database = database,
            changeCounter = BackupStateChangeCounter(database.backupStateDao()),
            restoreLock = RestoreLock { false },
            storageSpace = DatabaseStorageSpace(databaseFile, ioContext),
            clock = clock,
            commitListener = CommitListener {},
        )

    val settings: AppSettingsRepository = RoomAppSettingsRepository(database)

    val categories: CategoryRepository = RoomCategoryRepository(database)

    val suppliers: SupplierRepository = RoomSupplierRepository(database)

    val products: ProductRepository = RoomProductRepository(database)

    val priceHistory: PriceHistoryRepository = RoomPriceHistoryRepository(database)

    val stockLedger: StockLedgerRepository = RoomStockLedgerRepository(database)

    val lowStockAlerts: LowStockAlertRepository = RoomLowStockAlertRepository(database)

    val purchases: PurchaseRepository = RoomPurchaseRepository(database)

    val sales: SaleRepository = RoomSaleRepository(database)

    val expenseCategories: ExpenseCategoryRepository = RoomExpenseCategoryRepository(database)

    val expenses: ExpenseRepository = RoomExpenseRepository(database)
}
