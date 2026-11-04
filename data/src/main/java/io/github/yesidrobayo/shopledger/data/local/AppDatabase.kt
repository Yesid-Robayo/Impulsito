package io.github.yesidrobayo.shopledger.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.CategoryDao
import io.github.yesidrobayo.shopledger.data.local.catalog.CategoryEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.PriceHistoryDao
import io.github.yesidrobayo.shopledger.data.local.catalog.PriceHistoryEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductDao
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierDao
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierEntity
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseCategoryDao
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseCategoryEntity
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseDao
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseEntity
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuards
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuardsCallback
import io.github.yesidrobayo.shopledger.data.local.migration.AppMigrations
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseDao
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineDao
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDao
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineDao
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineEntity
import io.github.yesidrobayo.shopledger.data.local.settings.AppSettingsDao
import io.github.yesidrobayo.shopledger.data.local.settings.AppSettingsEntity
import io.github.yesidrobayo.shopledger.data.local.stock.LowStockAlertDao
import io.github.yesidrobayo.shopledger.data.local.stock.LowStockAlertEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementDao
import io.github.yesidrobayo.shopledger.data.local.stock.StockMovementEntity
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * La base de la app: el esquema 1, que nace completo y sin migraciones.
 *
 * Por ahora tiene el catálogo (categorías, proveedores, productos y su historial de precios),
 * `price_lists`, las compras y las ventas con sus líneas, el libro de stock y sus alertas, los
 * gastos con sus categorías, y las filas únicas de los ajustes y del estado del respaldo, que
 * nacen con la base. Room activa las llaves foráneas solo si alguna entidad declara una: sin
 * ellas, una fila huérfana entraría sin error. Las reglas que Room no sabe declarar son guardas,
 * en [SchemaGuards]. Las demás tablas llegan con las tareas que las crean, y la versión no cambia
 * mientras el esquema 1 no se publique.
 */
@Database(
    entities = [
        PriceListEntity::class,
        CategoryEntity::class,
        SupplierEntity::class,
        ProductEntity::class,
        PriceHistoryEntity::class,
        PurchaseEntity::class,
        PurchaseLineEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        StockMovementEntity::class,
        LowStockAlertEntity::class,
        ExpenseCategoryEntity::class,
        ExpenseEntity::class,
        AppSettingsEntity::class,
        BackupStateEntity::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao

    abstract fun supplierDao(): SupplierDao

    abstract fun productDao(): ProductDao

    abstract fun priceHistoryDao(): PriceHistoryDao

    abstract fun purchaseDao(): PurchaseDao

    abstract fun purchaseLineDao(): PurchaseLineDao

    abstract fun saleDao(): SaleDao

    abstract fun saleLineDao(): SaleLineDao

    abstract fun stockMovementDao(): StockMovementDao

    abstract fun lowStockAlertDao(): LowStockAlertDao

    abstract fun expenseCategoryDao(): ExpenseCategoryDao

    abstract fun expenseDao(): ExpenseDao

    abstract fun appSettingsDao(): AppSettingsDao

    abstract fun backupStateDao(): BackupStateDao

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
         */
        suspend fun open(
            context: Context,
            databaseFile: File,
            queryContext: CoroutineContext,
            clock: Clock = SystemClock,
        ): AppDatabase {
            val database = build(context, databaseFile, queryContext, clock)
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
         * - Al crearla, [InitialRowsCallback] escribe las filas únicas de los ajustes y del
         *   respaldo en la misma transacción que las tablas.
         *
         * @param databaseFile ruta absoluta del archivo de la base.
         * @param queryContext contexto de las consultas, fuera del hilo principal.
         * @param clock reloj de las filas que nacen con la base, si esta la crea.
         */
        fun build(
            context: Context,
            databaseFile: File,
            queryContext: CoroutineContext,
            clock: Clock = SystemClock,
        ): AppDatabase {
            require(databaseFile.isAbsolute) { "La ruta de la base debe ser absoluta" }
            return Room
                .databaseBuilder(context, AppDatabase::class.java, databaseFile.path)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(queryContext)
                .addMigrations(*AppMigrations.all.toTypedArray())
                .addCallback(SchemaGuardsCallback)
                .addCallback(InitialRowsCallback(clock))
                .build()
        }
    }
}
