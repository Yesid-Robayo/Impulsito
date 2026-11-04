package io.github.yesidrobayo.shopledger.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * La base de la app: el esquema 1, que nace completo y sin migraciones.
 *
 * Por ahora solo tiene `price_lists`, la tabla más pequeña del esquema con una llave foránea. Hace
 * falta una así desde el principio porque Room activa las llaves foráneas solo si alguna entidad
 * declara una: sin ella, una fila huérfana entraría sin error. Las demás tablas llegan con las
 * tareas que las crean, y la versión no cambia mientras el esquema 1 no se publique.
 */
@Database(
    entities = [PriceListEntity::class],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class AppDatabase : RoomDatabase() {
    companion object {
        /**
         * Versión del esquema que compila esta app. Una base con un `user_version` mayor viene de
         * una app más nueva: Room se niega a abrirla, y no la borra.
         */
        const val SCHEMA_VERSION = 1

        /**
         * Prepara la base guardada en [databaseFile] sin tocar el disco: Room la abre, o la crea si
         * no existe, en la primera consulta.
         *
         * Por eso solo la llama el código que antes comprobó que abrirla es seguro. Si la marca de
         * base inicializada existe y el archivo falta, Room crearía una base vacía encima del
         * negocio, y entonces la app debe ir al rescate sin llamar a esta función.
         *
         * - Siempre el SQLite empaquetado: la misma versión en todos los teléfonos, con
         *   `VACUUM INTO`. Abrir este archivo con el SQLite del sistema podría corromperlo.
         * - WAL explícito: con el modo automático, Room usa `TRUNCATE` en teléfonos con poca
         *   memoria, y la copia de respaldo necesita WAL para no frenar las escrituras.
         * - Sin migración destructiva: sin una ruta de migración, Room lanza una excepción y los
         *   datos quedan en el archivo.
         *
         * @param databaseFile ruta absoluta del archivo de la base.
         * @param queryContext contexto de las consultas, fuera del hilo principal.
         */
        fun build(
            context: Context,
            databaseFile: File,
            queryContext: CoroutineContext,
        ): AppDatabase {
            require(databaseFile.isAbsolute) { "La ruta de la base debe ser absoluta" }
            return Room
                .databaseBuilder(context, AppDatabase::class.java, databaseFile.path)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(queryContext)
                .build()
        }
    }
}
