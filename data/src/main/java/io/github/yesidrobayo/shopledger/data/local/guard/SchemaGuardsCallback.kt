package io.github.yesidrobayo.shopledger.data.local.guard

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection

/**
 * Pone las guardas del esquema en la base: al crearla y en cada apertura.
 *
 * - Al crearla, [onCreate] corre dentro de la transacción en que Room crea las tablas, antes de las
 *   filas que nacen con la base: así esas filas ya pasan por sus guardas. Debe ir antes que
 *   `InitialRowsCallback` en la lista de Room, que llama en ese orden.
 * - Al abrir, [onOpen] corre antes de que Room entregue la base: una base restaurada, migrada o con
 *   las guardas cambiadas a mano queda con las del catálogo antes de la primera escritura. Si falla,
 *   la apertura falla, y la base queda con las guardas que tenía.
 *
 * Con la base ya alineada, abrir solo cuesta una lectura de `sqlite_master`.
 */
internal object SchemaGuardsCallback : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        SchemaGuards.sync(connection)
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        SchemaGuards.sync(connection)
    }
}
