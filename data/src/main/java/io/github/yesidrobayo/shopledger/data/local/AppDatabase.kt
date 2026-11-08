package io.github.yesidrobayo.shopledger.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventDao
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventEntity
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.local.cash.CashDenominationDao
import io.github.yesidrobayo.shopledger.data.local.cash.CashDenominationEntity
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionCountDao
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionCountEntity
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionDao
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.CategoryDao
import io.github.yesidrobayo.shopledger.data.local.catalog.CategoryEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.CustomFieldDao
import io.github.yesidrobayo.shopledger.data.local.catalog.CustomFieldDefinitionEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.MediaAssetDao
import io.github.yesidrobayo.shopledger.data.local.catalog.MediaAssetEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.PriceHistoryDao
import io.github.yesidrobayo.shopledger.data.local.catalog.PriceHistoryEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductComponentDao
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductComponentEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductCustomValueEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductDao
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductModifierDao
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductModifierEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductTagEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductUnitDao
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductUnitEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierDao
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.TagDao
import io.github.yesidrobayo.shopledger.data.local.catalog.TagEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.UnitDao
import io.github.yesidrobayo.shopledger.data.local.catalog.UnitEntity
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerDao
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerEntity
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerLedgerEntryDao
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerLedgerEntryEntity
import io.github.yesidrobayo.shopledger.data.local.customer.LoyaltyLedgerEntryDao
import io.github.yesidrobayo.shopledger.data.local.customer.LoyaltyLedgerEntryEntity
import io.github.yesidrobayo.shopledger.data.local.customer.MessageTemplateDao
import io.github.yesidrobayo.shopledger.data.local.customer.MessageTemplateEntity
import io.github.yesidrobayo.shopledger.data.local.document.DocumentAttachmentDao
import io.github.yesidrobayo.shopledger.data.local.document.DocumentAttachmentEntity
import io.github.yesidrobayo.shopledger.data.local.document.OrderDao
import io.github.yesidrobayo.shopledger.data.local.document.OrderEntity
import io.github.yesidrobayo.shopledger.data.local.document.OrderLineEntity
import io.github.yesidrobayo.shopledger.data.local.document.QuoteDao
import io.github.yesidrobayo.shopledger.data.local.document.QuoteEntity
import io.github.yesidrobayo.shopledger.data.local.document.QuoteLineEntity
import io.github.yesidrobayo.shopledger.data.local.document.SaleFiscalMarkDao
import io.github.yesidrobayo.shopledger.data.local.document.SaleFiscalMarkEntity
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseCategoryDao
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseCategoryEntity
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseDao
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseEntity
import io.github.yesidrobayo.shopledger.data.local.expense.RecurringExpenseDao
import io.github.yesidrobayo.shopledger.data.local.expense.RecurringExpenseEntity
import io.github.yesidrobayo.shopledger.data.local.experience.ModuleUsageDao
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuards
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuardsCallback
import io.github.yesidrobayo.shopledger.data.local.migration.AppMigrations
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountDao
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementDao
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementEntity
import io.github.yesidrobayo.shopledger.data.local.payable.SupplierLedgerEntryDao
import io.github.yesidrobayo.shopledger.data.local.payable.SupplierLedgerEntryEntity
import io.github.yesidrobayo.shopledger.data.local.payment.PaymentMethodDao
import io.github.yesidrobayo.shopledger.data.local.payment.PaymentMethodEntity
import io.github.yesidrobayo.shopledger.data.local.payment.SalePaymentDao
import io.github.yesidrobayo.shopledger.data.local.payment.SalePaymentEntity
import io.github.yesidrobayo.shopledger.data.local.pricing.FxRateDao
import io.github.yesidrobayo.shopledger.data.local.pricing.FxRateEntity
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListDao
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity
import io.github.yesidrobayo.shopledger.data.local.pricing.ProductPriceDao
import io.github.yesidrobayo.shopledger.data.local.pricing.ProductPriceEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseDao
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineDao
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineEntity
import io.github.yesidrobayo.shopledger.data.local.report.BusinessGoalDao
import io.github.yesidrobayo.shopledger.data.local.report.BusinessGoalEntity
import io.github.yesidrobayo.shopledger.data.local.report.PriceIndexEntryDao
import io.github.yesidrobayo.shopledger.data.local.report.PriceIndexEntryEntity
import io.github.yesidrobayo.shopledger.data.local.returns.PurchaseReturnDao
import io.github.yesidrobayo.shopledger.data.local.returns.PurchaseReturnEntity
import io.github.yesidrobayo.shopledger.data.local.returns.PurchaseReturnLineDao
import io.github.yesidrobayo.shopledger.data.local.returns.PurchaseReturnLineEntity
import io.github.yesidrobayo.shopledger.data.local.returns.SaleReturnDao
import io.github.yesidrobayo.shopledger.data.local.returns.SaleReturnEntity
import io.github.yesidrobayo.shopledger.data.local.returns.SaleReturnLineDao
import io.github.yesidrobayo.shopledger.data.local.returns.SaleReturnLineEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDao
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDraftDao
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDraftEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDraftLineEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineComponentDao
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineComponentEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineDao
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineEntity
import io.github.yesidrobayo.shopledger.data.local.settings.AppSettingsDao
import io.github.yesidrobayo.shopledger.data.local.settings.AppSettingsEntity
import io.github.yesidrobayo.shopledger.data.local.stock.CountSessionDao
import io.github.yesidrobayo.shopledger.data.local.stock.CountSessionEntity
import io.github.yesidrobayo.shopledger.data.local.stock.CountSessionLineEntity
import io.github.yesidrobayo.shopledger.data.local.stock.LocationDao
import io.github.yesidrobayo.shopledger.data.local.stock.LocationEntity
import io.github.yesidrobayo.shopledger.data.local.stock.LowStockAlertDao
import io.github.yesidrobayo.shopledger.data.local.stock.LowStockAlertEntity
import io.github.yesidrobayo.shopledger.data.local.stock.ProductionBatchDao
import io.github.yesidrobayo.shopledger.data.local.stock.ProductionBatchEntity
import io.github.yesidrobayo.shopledger.data.local.stock.ProductionBatchLineEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockBalanceEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockLotDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockLotEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockTransferDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockTransferEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockTransferLineEntity
import io.github.yesidrobayo.shopledger.data.local.tax.TaxRateDao
import io.github.yesidrobayo.shopledger.data.local.tax.TaxRateEntity
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * La base de la app: el esquema 1, que nace completo y sin migraciones.
 *
 * Por ahora tiene el catálogo (categorías, proveedores, unidades, productos con sus conversiones y
 * su historial de precios), `price_lists`, las compras y las ventas con sus líneas, el libro de
 * stock y sus alertas, los gastos con sus categorías, las tasas de impuesto, las filas únicas de
 * los ajustes y del estado del respaldo, que nacen con la base, los clientes con sus libros de
 * cuenta y de puntos y sus plantillas de mensaje, las cuentas de dinero con su libro y sus sesiones
 * de caja, el libro de los proveedores, el registro de auditoría, y las metas, los gastos
 * recurrentes y el índice de precios de los reportes, y las cotizaciones, los pedidos, las marcas
 * de las ventas y las fotos de comprobantes. Del catálogo también tiene los combos, los campos
 * propios, las etiquetas, las imágenes y los extras de cada producto; de las ventas, los
 * componentes que consumió cada combo vendido, sus devoluciones, las ventas en espera, los métodos
 * de pago y los pagos; de las compras, las devoluciones a proveedor, y de los precios, los precios
 * por lista y las cotizaciones. Room activa las llaves foráneas solo si alguna entidad declara una:
 * sin ellas, una fila huérfana entraría sin error. Las reglas que Room no sabe declarar son
 * guardas, en [SchemaGuards]. Las demás tablas llegan con las tareas que las crean, y la versión no
 * cambia mientras el esquema 1 no se publique.
 */
@Database(
    // Cada tabla va antes que las tablas a las que apunta con RESTRICT: Room 3.0.3 ordena las
    // entidades para borrarlas con un comparador que no es transitivo, y desde 32 entidades el
    // ordenamiento falla al compilar si dos vecinas quedan al revés. Con la lista ya en ese orden,
    // el ordenamiento la recorre una vez y no compara nada más.
    entities = [
        CategoryEntity::class,
        PriceHistoryEntity::class,
        CustomFieldDefinitionEntity::class,
        ProductComponentEntity::class,
        ProductModifierEntity::class,
        ProductCustomValueEntity::class,
        ProductTagEntity::class,
        TagEntity::class,
        ProductUnitEntity::class,
        SaleDraftEntity::class,
        SaleDraftLineEntity::class,
        StockMovementEntity::class,
        PurchaseReturnLineEntity::class,
        PurchaseLineEntity::class,
        SaleLineComponentEntity::class,
        SaleReturnLineEntity::class,
        SaleLineEntity::class,
        StockBalanceEntity::class,
        StockTransferLineEntity::class,
        StockTransferEntity::class,
        ProductionBatchLineEntity::class,
        ProductionBatchEntity::class,
        CountSessionEntity::class,
        CountSessionLineEntity::class,
        StockLotEntity::class,
        LowStockAlertEntity::class,
        AppSettingsEntity::class,
        BackupStateEntity::class,
        TaxRateEntity::class,
        LoyaltyLedgerEntryEntity::class,
        MessageTemplateEntity::class,
        MoneyMovementEntity::class,
        SalePaymentEntity::class,
        PaymentMethodEntity::class,
        ProductPriceEntity::class,
        FxRateEntity::class,
        CustomerLedgerEntryEntity::class,
        SaleReturnEntity::class,
        SupplierLedgerEntryEntity::class,
        PurchaseReturnEntity::class,
        CashSessionCountEntity::class,
        CashDenominationEntity::class,
        AuditEventEntity::class,
        BusinessGoalEntity::class,
        DocumentAttachmentEntity::class,
        PurchaseEntity::class,
        SupplierEntity::class,
        MediaAssetEntity::class,
        ExpenseEntity::class,
        OrderLineEntity::class,
        PriceIndexEntryEntity::class,
        QuoteLineEntity::class,
        ProductEntity::class,
        UnitEntity::class,
        RecurringExpenseEntity::class,
        ExpenseCategoryEntity::class,
        SaleFiscalMarkEntity::class,
        SaleEntity::class,
        LocationEntity::class,
        CashSessionEntity::class,
        MoneyAccountEntity::class,
        PriceListEntity::class,
        OrderEntity::class,
        QuoteEntity::class,
        CustomerEntity::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao

    abstract fun supplierDao(): SupplierDao

    abstract fun productDao(): ProductDao

    abstract fun priceHistoryDao(): PriceHistoryDao

    abstract fun customFieldDao(): CustomFieldDao

    abstract fun mediaAssetDao(): MediaAssetDao

    abstract fun productComponentDao(): ProductComponentDao

    abstract fun productModifierDao(): ProductModifierDao

    abstract fun tagDao(): TagDao

    abstract fun productUnitDao(): ProductUnitDao

    abstract fun unitDao(): UnitDao

    abstract fun purchaseDao(): PurchaseDao

    abstract fun purchaseLineDao(): PurchaseLineDao

    abstract fun purchaseReturnDao(): PurchaseReturnDao

    abstract fun purchaseReturnLineDao(): PurchaseReturnLineDao

    abstract fun saleDao(): SaleDao

    abstract fun saleLineDao(): SaleLineDao

    abstract fun saleLineComponentDao(): SaleLineComponentDao

    abstract fun saleDraftDao(): SaleDraftDao

    abstract fun saleReturnDao(): SaleReturnDao

    abstract fun saleReturnLineDao(): SaleReturnLineDao

    abstract fun stockMovementDao(): StockMovementDao

    abstract fun stockBalanceDao(): StockBalanceDao

    abstract fun stockLotDao(): StockLotDao

    abstract fun locationDao(): LocationDao

    abstract fun stockTransferDao(): StockTransferDao

    abstract fun productionBatchDao(): ProductionBatchDao

    abstract fun countSessionDao(): CountSessionDao

    abstract fun lowStockAlertDao(): LowStockAlertDao

    abstract fun expenseCategoryDao(): ExpenseCategoryDao

    abstract fun expenseDao(): ExpenseDao

    abstract fun appSettingsDao(): AppSettingsDao

    abstract fun backupStateDao(): BackupStateDao

    abstract fun taxRateDao(): TaxRateDao

    abstract fun paymentMethodDao(): PaymentMethodDao

    abstract fun salePaymentDao(): SalePaymentDao

    abstract fun priceListDao(): PriceListDao

    abstract fun productPriceDao(): ProductPriceDao

    abstract fun fxRateDao(): FxRateDao

    abstract fun customerDao(): CustomerDao

    abstract fun customerLedgerEntryDao(): CustomerLedgerEntryDao

    abstract fun loyaltyLedgerEntryDao(): LoyaltyLedgerEntryDao

    abstract fun messageTemplateDao(): MessageTemplateDao

    abstract fun moneyAccountDao(): MoneyAccountDao

    abstract fun moneyMovementDao(): MoneyMovementDao

    abstract fun supplierLedgerEntryDao(): SupplierLedgerEntryDao

    abstract fun cashSessionDao(): CashSessionDao

    abstract fun cashDenominationDao(): CashDenominationDao

    abstract fun cashSessionCountDao(): CashSessionCountDao

    abstract fun auditEventDao(): AuditEventDao

    abstract fun moduleUsageDao(): ModuleUsageDao

    abstract fun businessGoalDao(): BusinessGoalDao

    abstract fun documentAttachmentDao(): DocumentAttachmentDao

    abstract fun orderDao(): OrderDao

    abstract fun priceIndexEntryDao(): PriceIndexEntryDao

    abstract fun quoteDao(): QuoteDao

    abstract fun recurringExpenseDao(): RecurringExpenseDao

    abstract fun saleFiscalMarkDao(): SaleFiscalMarkDao

    companion object {
        /**
         * Versión del esquema que compila esta app. Una base con un `user_version` mayor viene de
         * una app más nueva: Room se niega a abrirla, y no la borra.
         */
        const val SCHEMA_VERSION = 1

        /**
         * Abre la base y la deja lista para escribir: fija `PRAGMA synchronous = FULL` en la
         * conexión de escritura antes de entregarla, así que ninguna escritura de la app corre sin
         * ese ajuste. Es el camino de la app hacia la base: [build] sola no lo fija.
         *
         * En WAL, Room pone `synchronous = NORMAL` en cada conexión que abre, y con `NORMAL` una
         * transacción ya confirmada puede perderse si el teléfono se apaga de golpe o el sistema
         * falla. Con `FULL`, cada confirmación sincroniza el `-wal` con el disco: lo que la
         * pantalla da por guardado ya está en disco.
         *
         * - Solo en la escritora: en WAL hay una sola, y Room abre las lectoras con
         *   `query_only`, así que no confirman nada. Se quedan con el `NORMAL` de Room.
         * - Con la conexión de escritura y fuera de una transacción, porque SQLite no deja
         *   cambiarlo dentro de una. `Callback.onOpen` no sirve: solo corre en la primera
         *   conexión, y las demás pasan por la configuración de Room, que vuelve a poner `NORMAL`.
         * - El ajuste vive en la conexión, no en el archivo: cada instancia nueva pasa por aquí.
         *   Room conserva la misma escritora mientras la instancia sigue abierta. Solo su cierre
         *   automático la reabriría, y con `NORMAL`: por eso [build] no lo activa.
         * - Antes solo escribe Room, al crear el esquema o migrarlo, con `NORMAL`. Un corte ahí
         *   solo deshace esa creación o esa migración, que Room repite al abrir otra vez, y la
         *   primera confirmación con `FULL` sincroniza también lo que el `-wal` ya tenía.
         *
         * Igual que [build], solo la llama el código que antes comprobó que abrirla es seguro. Si
         * Room se niega a abrir la base, por ejemplo porque viene de una app más nueva, lanza
         * `IllegalStateException`: la instancia se cierra antes de relanzarla, y el archivo queda
         * como estaba.
         *
         * @param databaseFile ruta absoluta del archivo de la base.
         * @param queryContext contexto de las consultas, fuera del hilo principal.
         * @param clock reloj de las filas que nacen con la base, si esta la crea.
         * @param initialRegion formato, zona y semana con que nacen los ajustes, si la base se
         *   crea. Solo se lee entonces, y por defecto sale del sistema del teléfono.
         */
        suspend fun open(
            context: Context,
            databaseFile: File,
            queryContext: CoroutineContext,
            clock: Clock = SystemClock,
            initialRegion: () -> InitialRegion = InitialRegion::ofSystem,
        ): AppDatabase {
            val database = build(context, databaseFile, queryContext, clock, initialRegion)
            try {
                database.useWriterConnection { it.executeSQL("PRAGMA synchronous = FULL") }
            } catch (failure: Throwable) {
                // Una base que no quedó lista no se entrega, ni deja conexiones abiertas.
                database.close()
                throw failure
            }
            return database
        }

        /**
         * Prepara la base guardada en [databaseFile] sin tocar el disco: Room la abre, o la crea si
         * no existe, en la primera consulta.
         *
         * Por eso solo la llama el código que antes comprobó que abrirla es seguro. Si la marca de
         * base inicializada existe y el archivo falta, Room crearía una base vacía encima del
         * negocio, y entonces la app debe ir al rescate sin llamar a esta función.
         *
         * La app no la usa sola: recibe la base por [open], que además deja la escritura en disco
         * en cada confirmación. Sola sirve a las pruebas que miran la base tal como la deja Room.
         *
         * - Siempre el SQLite empaquetado: la misma versión en todos los teléfonos, con
         *   `VACUUM INTO`. Abrir este archivo con el SQLite del sistema podría corromperlo.
         * - WAL explícito: con el modo automático, Room usa `TRUNCATE` en teléfonos con poca
         *   memoria, y la copia de respaldo necesita WAL para no frenar las escrituras.
         * - Sin migración destructiva: sin una ruta de migración, Room lanza una excepción y los
         *   datos quedan en el archivo.
         * - Los saltos de versión salen de [AppMigrations], la misma lista para la base activa y
         *   para la copia candidata de una restauración.
         * - Sin cierre automático por inactividad: Room reabriría la conexión de escritura con
         *   `synchronous = NORMAL`, sin pasar por [open].
         * - Al crearla y en cada apertura, [SchemaGuardsCallback] deja en la base las guardas del
         *   catálogo. Va primero: al crearla, las filas que escribe [InitialRowsCallback] ya
         *   pasan por sus guardas.
         * - Al crearla, [InitialRowsCallback] escribe las filas iniciales, como las únicas de los
         *   ajustes y del respaldo, en la misma transacción que las tablas.
         *
         * @param databaseFile ruta absoluta del archivo de la base.
         * @param queryContext contexto de las consultas, fuera del hilo principal.
         * @param clock reloj de las filas que nacen con la base, si esta la crea.
         * @param initialRegion formato, zona y semana con que nacen los ajustes, si la base se
         *   crea. Solo se lee entonces, y por defecto sale del sistema del teléfono.
         */
        fun build(
            context: Context,
            databaseFile: File,
            queryContext: CoroutineContext,
            clock: Clock = SystemClock,
            initialRegion: () -> InitialRegion = InitialRegion::ofSystem,
        ): AppDatabase {
            require(databaseFile.isAbsolute) { "La ruta de la base debe ser absoluta" }
            return Room
                .databaseBuilder(context, AppDatabase::class.java, databaseFile.path)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(queryContext)
                .addMigrations(*AppMigrations.all.toTypedArray())
                .addCallback(SchemaGuardsCallback)
                .addCallback(InitialRowsCallback(clock, initialRegion))
                .build()
        }
    }
}
