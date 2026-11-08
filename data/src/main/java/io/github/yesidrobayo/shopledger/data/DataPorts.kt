package io.github.yesidrobayo.shopledger.data

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import io.github.yesidrobayo.shopledger.data.backup.BackupStatusEnvironment
import io.github.yesidrobayo.shopledger.data.backup.BackupStatusReader
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.repository.RoomAppSettingsRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomCategoryRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomExpenseRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomLowStockAlertRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomPaymentMethodRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomPriceHistoryRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomProductRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomPurchaseRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSalePaymentRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSaleRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomStockLedgerRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSupplierRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomSystemRowRepository
import io.github.yesidrobayo.shopledger.data.repository.RoomUnitRepository
import io.github.yesidrobayo.shopledger.data.transaction.BackupStateChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.CommitListener
import io.github.yesidrobayo.shopledger.data.transaction.DatabaseStorageSpace
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.data.transaction.RoomTransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.BackupStatusSource
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpenseCategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.SalePaymentRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
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
 *
 * @param backupStatusEnvironment lo que el estado del respaldo lee fuera de la base. Los puertos
 *   que se arman a mano, sin la app, no tienen nada fuera de ella.
 * @param commitListener lo que corre tras confirmar un cambio del negocio: programar la copia. Los
 *   puertos que se arman a mano no programan nada.
 */
class DataPorts internal constructor(
    private val database: AppDatabase,
    /** El archivo de la base abierta: el respaldo la copia desde aquí. */
    internal val databaseFile: File,
    ioContext: CoroutineContext,
    clock: Clock,
    /**
     * El cerrojo de la restauración: mientras está tomado, ni las escrituras de los casos de uso ni
     * las que van solas entran en la base.
     */
    restoreLock: RestoreLock,
    backupStatusEnvironment: BackupStatusEnvironment = BackupStatusEnvironment.Detached,
    commitListener: CommitListener = CommitListener {},
) {
    /**
     * Abre cada escritura del negocio en una sola transacción, y cuenta el cambio para la copia.
     *
     * Con una restauración en curso no entra ninguna. Tras confirmar, [commitListener] programa
     * la copia; si eso falla, el cambio igual queda contado en la base, y el respaldo lo ve en el
     * siguiente evento.
     */
    val transactions: TransactionRunner =
        RoomTransactionRunner(
            database = database,
            changeCounter = BackupStateChangeCounter(database.backupStateDao()),
            restoreLock = restoreLock,
            storageSpace = DatabaseStorageSpace(databaseFile, ioContext),
            clock = clock,
            commitListener = commitListener,
        )

    val settings: AppSettingsRepository = RoomAppSettingsRepository(database, restoreLock)

    val categories: CategoryRepository = RoomCategoryRepository(database)

    val suppliers: SupplierRepository = RoomSupplierRepository(database)

    val products: ProductRepository = RoomProductRepository(database)

    val priceHistory: PriceHistoryRepository = RoomPriceHistoryRepository(database)

    val units: UnitRepository = RoomUnitRepository(database)

    val systemRows: SystemRowRepository = RoomSystemRowRepository(database)

    val stockLedger: StockLedgerRepository = RoomStockLedgerRepository(database)

    val lowStockAlerts: LowStockAlertRepository =
        RoomLowStockAlertRepository(database, restoreLock)

    val purchases: PurchaseRepository = RoomPurchaseRepository(database)

    val sales: SaleRepository = RoomSaleRepository(database)

    /** Los pagos de las ventas, y lo que una venta copia del método con que cobra. */
    val salePayments: SalePaymentRepository = RoomSalePaymentRepository(database)

    val expenseCategories: ExpenseCategoryRepository = RoomExpenseCategoryRepository(database)

    val expenses: ExpenseRepository = RoomExpenseRepository(database)

    val paymentMethods: PaymentMethodRepository = RoomPaymentMethodRepository(database)

    /**
     * Lo que el estado del respaldo lee del teléfono, para la pantalla de inicio y la del respaldo.
     * Solo lee: no escribe nada ni usa la red.
     */
    val backupStatus: BackupStatusSource =
        BackupStatusReader(
            state = database.backupStateDao(),
            meteredPolicy = { settings.get().preferences.meteredBackupPolicy },
            environment = backupStatusEnvironment,
            ioContext = ioContext,
        )

    /** El estado del respaldo, para el motor de respaldo: no es un puerto de los casos de uso. */
    internal val backupState: BackupStateDao = database.backupStateDao()

    /** El registro de auditoría, para lo que la capa de datos anota por su cuenta. */
    internal val auditEvents: AuditEventDao = database.auditEventDao()

    /**
     * Cierra la base para que el arranque siguiente la reemplace: pasa el `-wal` al archivo y
     * cierra todas sus conexiones. Después, nada de estos puertos sirve, y el proceso se reinicia.
     */
    internal suspend fun closeForRestore() {
        try {
            database.useWriterConnection { it.executeSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        } finally {
            database.close()
        }
    }
}
