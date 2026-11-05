package io.github.yesidrobayo.shopledger.data.local.guard

import io.github.yesidrobayo.shopledger.data.local.AppDatabase

/**
 * El esquema esperado de cada versión: la lista permitida contra la que se compara una copia antes
 * de confiar en ella, porque `integrity_check` da `ok` ante una base bien formada y hostil.
 *
 * Una base de la versión `N` solo puede tener en `sqlite_master`:
 * - las tablas, los índices y las guardas de [forVersion] `N`, ni uno más ni uno menos;
 * - las tablas internas de [INTERNAL_TABLES];
 * - los índices automáticos de SQLite, `sqlite_autoindex_*`, solo de una tabla cuya llave primaria
 *   los crea, como dice [CatalogTable.hasPrimaryKeyIndex].
 *
 * Nada más: ninguna vista, ninguna tabla virtual ni sus tablas internas, y ningún trigger que no
 * sea una guarda del catálogo con su texto exacto.
 *
 * Tablas e índices se comparan por estructura, con `pragma_table_info`, `pragma_index_list`,
 * `pragma_index_xinfo` y `pragma_foreign_key_list`, y nunca por el texto de `sqlite_master`: una
 * tabla reconstruida por una migración queda guardada como `CREATE TABLE "x"`, con comillas
 * dobles, y una nueva con acentos graves, y una columna agregada con `ALTER TABLE` queda al final.
 * Las guardas, en cambio, se comparan por texto exacto: una guarda distinta cambia una regla.
 *
 * Cada versión sale de dos fuentes: las tablas, del JSON que Room exporta en `data/schemas/`, y las
 * guardas, de [SchemaGuards]. La tarea `generateSchemaCatalog` de `:data` escribe el archivo de la
 * versión actual, y una prueba en la JVM falla si no coincide con esas dos fuentes. El archivo de
 * una versión anterior no se vuelve a generar: sus guardas ya no viven en [SchemaGuards], así que
 * queda congelado, igual que su JSON.
 */
internal object SchemaCatalog {
    /**
     * Tablas que SQLite, Room y Android crean por su cuenta, y que no son del esquema. Su contenido
     * no es del negocio: `room_master_table` guarda la huella del esquema, y `sqlite_sequence` el
     * último `id` de cada tabla con `AUTOINCREMENT`.
     */
    val INTERNAL_TABLES: Set<String> =
        setOf("sqlite_sequence", "sqlite_stat1", "android_metadata", "room_master_table")

    /** El prefijo de los índices que SQLite crea solo para una llave primaria o un `UNIQUE`. */
    const val AUTOINDEX_PREFIX = "sqlite_autoindex_"

    /** Una entrada por versión del esquema, de la primera a la actual, sin huecos. */
    val versions: List<SchemaVersionCatalog> by lazy {
        val all = generatedSchemaCatalogs()
        all.forEachIndexed { index, catalog ->
            require(catalog.version == index + 1) {
                "El catálogo del esquema salta de versión en ${catalog.version}"
            }
        }
        all
    }

    /**
     * El catálogo de [version], o `null` si esta app no la conoce: una copia de una versión mayor
     * viene de una app más nueva, y una menor que 1 no es una base de la app.
     */
    fun forVersion(version: Int): SchemaVersionCatalog? = versions.getOrNull(version - 1)

    /** El catálogo de la versión que compila esta app. */
    val current: SchemaVersionCatalog
        get() = requireNotNull(forVersion(AppDatabase.SCHEMA_VERSION)) {
            "Falta el catálogo de la versión actual del esquema"
        }
}

/**
 * El esquema de una versión.
 *
 * @property identityHash la huella que Room guarda en `room_master_table` para esta versión.
 * @property tables las tablas del esquema, por nombre.
 * @property triggers las guardas, por nombre, con el texto que guarda SQLite.
 */
internal data class SchemaVersionCatalog(
    val version: Int,
    val identityHash: String,
    val tables: List<CatalogTable>,
    val triggers: List<CatalogTrigger>,
) {
    /** La tabla [name], o `null` si no es del esquema. */
    fun table(name: String): CatalogTable? = tables.find { it.name == name }

    /** La guarda [name], o `null` si no es del esquema. */
    fun trigger(name: String): CatalogTrigger? = triggers.find { it.name == name }
}

/**
 * Una tabla, tal como la describen los `pragma` de SQLite.
 *
 * @property autoIncrement si su llave primaria lleva `AUTOINCREMENT`: SQLite lleva entonces su
 *   último `id` en `sqlite_sequence`, y nunca reusa uno borrado.
 * @property columns sus columnas, por nombre. El orden no es parte de la estructura: una columna
 *   agregada con `ALTER TABLE` queda al final.
 * @property indices los índices que Room crea con `CREATE INDEX`, por nombre. Ninguno es parcial
 *   ni de expresión, y todos usan la intercalación `BINARY`.
 * @property foreignKeys sus llaves foráneas, por columna. Ninguna difiere su revisión ni usa
 *   `MATCH`.
 */
internal data class CatalogTable(
    val name: String,
    val autoIncrement: Boolean,
    val columns: List<CatalogColumn>,
    val indices: List<CatalogIndex>,
    val foreignKeys: List<CatalogForeignKey>,
) {
    /**
     * Si SQLite crea un `sqlite_autoindex_*` para su llave primaria: lo hace con toda llave que no
     * sea una sola columna `INTEGER`, que es el `rowid` mismo.
     */
    val hasPrimaryKeyIndex: Boolean
        get() {
            val key = columns.filter { it.primaryKeyPosition > 0 }
            return key.size != 1 || !key.single().type.equals("INTEGER", ignoreCase = true)
        }
}

/**
 * Una columna, con los campos de `pragma_table_info`.
 *
 * @property type el tipo declarado, como `INTEGER` o `TEXT`.
 * @property defaultValue el `DEFAULT` declarado, tal como lo devuelve `dflt_value`. Room lo deja
 *   siempre en `null`, porque los valores iniciales los pone el código.
 * @property primaryKeyPosition su lugar en la llave primaria, desde 1, o 0 si no es parte de ella.
 *   Ninguna columna es generada ni oculta.
 */
internal data class CatalogColumn(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKeyPosition: Int,
)

/**
 * Un índice, con los campos de `pragma_index_list` y `pragma_index_xinfo`.
 *
 * @property columns sus columnas clave, en el orden del índice.
 */
internal data class CatalogIndex(
    val name: String,
    val unique: Boolean,
    val columns: List<CatalogIndexColumn>,
)

/** Una columna clave de un índice. [descending] es la columna `desc` de `pragma_index_xinfo`. */
internal data class CatalogIndexColumn(val name: String, val descending: Boolean)

/**
 * Una llave foránea, con los campos de `pragma_foreign_key_list`.
 *
 * @property columns las columnas de la tabla, en orden.
 * @property referencedColumns las de [referencedTable], en el mismo orden.
 * @property onUpdate y [onDelete] son la acción como la escribe SQLite, como `NO ACTION`,
 *   `RESTRICT`, `CASCADE` o `SET NULL`.
 */
internal data class CatalogForeignKey(
    val columns: List<String>,
    val referencedTable: String,
    val referencedColumns: List<String>,
    val onUpdate: String,
    val onDelete: String,
)

/**
 * Una guarda.
 *
 * @property table la tabla que protege, que es la `tbl_name` de `sqlite_master`.
 * @property sql su texto exacto, tal como lo guarda SQLite en `sqlite_master`: sin el punto y coma
 *   final.
 */
internal data class CatalogTrigger(val name: String, val table: String, val sql: String)
