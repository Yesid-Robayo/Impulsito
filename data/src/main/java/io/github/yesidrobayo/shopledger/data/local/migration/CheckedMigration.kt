package io.github.yesidrobayo.shopledger.data.local.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * La forma de toda migración de la base: un salto de una versión a la siguiente, que al terminar
 * comprueba que ninguna fila quedó apuntando a otra que no existe.
 *
 * Room corre todos los saltos pendientes en una sola transacción `EXCLUSIVE`, y cambia
 * `user_version` dentro de ella. Si un salto lanza, Room revierte la cadena entera: la base queda
 * en la versión desde la que se abrió, con todas sus filas, y la excepción sale de la primera
 * consulta, que en la app es la de `AppDatabase.open`. La comprobación final aprovecha eso.
 *
 * Por qué hace falta: Room migra con las llaves foráneas apagadas, porque las enciende en la
 * conexión después de migrar, y no las revisa al terminar una migración manual. Una tabla
 * reconstruida que pierde el padre de una fila, o una columna nueva con una llave que no cumple,
 * pasaría sin error y dejaría la base rota hasta la próxima validación de una copia. Así, un salto
 * que deja una llave rota falla y no llega al disco.
 *
 * Reglas de cada salto:
 * - SQL literal, escrito en la propia migración: es historia, y no cambia cuando cambia la app.
 * - Un salto por versión, de [startVersion] a `startVersion + 1`, y nunca se borra: una copia
 *   vieja que se restaura recorre todos.
 * - En el libro de stock solo se agregan columnas. Si hay que reconstruir otra tabla: crear la
 *   nueva con su nombre final más un sufijo, copiar todas las filas con sus `id`, igualar su fila
 *   de `sqlite_sequence` a la de la vieja, borrar los disparadores que la nombran, borrar la vieja
 *   y renombrar la nueva. Nunca al revés: renombrar primero la vieja haría que las llaves de las
 *   tablas hijas la siguieran a su nombre nuevo.
 * - Las guardas no se recrean aquí: se alinean al abrir la base.
 * - Sin reglas de negocio: un valor que deba recalcularse lo recalcula el dominio después de abrir.
 *
 * @param startVersion versión desde la que salta. Llega a la siguiente.
 */
internal abstract class CheckedMigration(startVersion: Int) :
    Migration(startVersion, startVersion + 1) {
    /** Los cambios de este salto, con SQL literal. Corre dentro de la transacción de Room. */
    protected abstract suspend fun migrateSchema(connection: SQLiteConnection)

    final override suspend fun migrate(connection: SQLiteConnection) {
        migrateSchema(connection)
        val brokenTables = brokenForeignKeyTables(connection)
        if (brokenTables.isNotEmpty()) {
            // Solo nombres de tabla y conteos: ningún valor de una fila sale en el mensaje.
            throw IllegalStateException(
                "La migración de $startVersion a $endVersion dejó llaves foráneas rotas en: " +
                    brokenTables.joinToString(", "),
            )
        }
    }

    /** Cada tabla con filas cuyo padre no existe, como `tabla (filas)`, por nombre. */
    private fun brokenForeignKeyTables(connection: SQLiteConnection): List<String> {
        val rowsByTable = sortedMapOf<String, Int>()
        connection.prepare("PRAGMA foreign_key_check").use { statement ->
            while (statement.step()) {
                val table = statement.getText(0)
                rowsByTable[table] = (rowsByTable[table] ?: 0) + 1
            }
        }
        return rowsByTable.map { (table, rows) -> "$table ($rows)" }
    }
}
