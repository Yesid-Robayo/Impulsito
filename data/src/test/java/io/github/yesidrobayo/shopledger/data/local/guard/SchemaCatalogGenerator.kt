package io.github.yesidrobayo.shopledger.data.local.guard

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import java.io.File

/**
 * Lo que Room exporta de una versión del esquema, ya como catálogo: su huella y sus tablas, sin
 * guardas, porque Room no exporta triggers.
 */
internal data class ExportedSchema(
    val version: Int,
    val identityHash: String,
    val tables: List<CatalogTable>,
)

/**
 * Lee el JSON que Room exporta en `data/schemas/` y lo describe como lo verían los `pragma` de
 * SQLite, con todo en orden canónico: tablas, columnas e índices por nombre, y llaves por columna.
 */
internal object RoomSchemaReader {
    fun read(json: String): ExportedSchema {
        val root = JsonText.parse(json).asObject("el JSON")
        require(root.long("formatVersion") == 1L) { "Formato de esquema de Room desconocido" }
        val database = root.objectAt("database")
        val tables =
            database.listAt("entities").map { table(it.asObject("una tabla")) }.sortedBy { it.name }
        require(tables.map { it.name }.toSet().size == tables.size) { "Hay dos tablas iguales" }
        // Room 3 exporta las vistas en su propia lista: el esquema de la app no tiene ninguna.
        require(database["views"].let { it == null || (it as List<*>).isEmpty() }) {
            "El esquema exportado tiene vistas"
        }
        return ExportedSchema(
            version = database.long("version").toInt(),
            identityHash = database.text("identityHash"),
            tables = tables,
        )
    }

    private fun table(entity: Map<String, Any?>): CatalogTable {
        val name = entity.text("tableName")
        val primaryKey = entity.objectAt("primaryKey")
        val keyColumns = primaryKey.listAt("columnNames").map { it as String }
        val autoIncrement = primaryKey["autoGenerate"] == true
        // Room escribe AUTOINCREMENT solo si genera la llave: el texto lo confirma.
        require(autoIncrement == entity.text("createSql").contains(" AUTOINCREMENT ")) {
            "$name: AUTOINCREMENT no coincide con su llave"
        }
        require(entity["withoutRowId"] != true) { "$name: el catálogo no admite WITHOUT ROWID" }
        val columns =
            entity.listAt("fields").map { field ->
                val column = field.asObject("una columna de $name")
                val columnName = column.text("columnName")
                CatalogColumn(
                    name = columnName,
                    type = column.text("affinity"),
                    notNull = column["notNull"] == true,
                    defaultValue = column["defaultValue"] as String?,
                    primaryKeyPosition = keyColumns.indexOf(columnName) + 1,
                )
            }
        require(keyColumns.all { key -> columns.any { it.name == key } }) {
            "$name: su llave nombra una columna que no existe"
        }
        val indices =
            entity.listOrEmpty("indices").map { item ->
                val index = item.asObject("un índice de $name")
                val keys = index.listAt("columnNames").map { it as String }
                val orders = index.listOrEmpty("orders").map { it as String }
                require(orders.isEmpty() || orders.size == keys.size) {
                    "${index.text("name")}: cada columna lleva su orden"
                }
                CatalogIndex(
                    name = index.text("name"),
                    unique = index["unique"] == true,
                    columns =
                        keys.mapIndexed { position, key ->
                            CatalogIndexColumn(key, orders.getOrNull(position) == "DESC")
                        },
                )
            }
        val foreignKeys =
            entity.listOrEmpty("foreignKeys").map { item ->
                val key = item.asObject("una llave de $name")
                CatalogForeignKey(
                    columns = key.listAt("columns").map { it as String },
                    referencedTable = key.text("table"),
                    referencedColumns = key.listAt("referencedColumns").map { it as String },
                    onUpdate = key.text("onUpdate"),
                    onDelete = key.text("onDelete"),
                )
            }
        return CatalogTable(
            name = name,
            autoIncrement = autoIncrement,
            columns = columns.sortedBy { it.name },
            indices = indices.sortedBy { it.name },
            foreignKeys =
                foreignKeys.sortedWith(
                    compareBy({ it.columns.joinToString(",") }, { it.referencedTable }),
                ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(what: String): Map<String, Any?> =
        requireNotNull(this as? Map<String, Any?>) { "Se esperaba un objeto en $what" }

    private fun Map<String, Any?>.objectAt(key: String): Map<String, Any?> = this[key].asObject(key)

    private fun Map<String, Any?>.listAt(key: String): List<Any?> =
        requireNotNull(this[key] as? List<*>) { "Falta la lista $key" }

    private fun Map<String, Any?>.listOrEmpty(key: String): List<Any?> =
        this[key]?.let { requireNotNull(it as? List<*>) { "$key no es una lista" } } ?: emptyList()

    private fun Map<String, Any?>.text(key: String): String =
        requireNotNull(this[key] as? String) { "Falta el texto $key" }

    private fun Map<String, Any?>.long(key: String): Long =
        requireNotNull(this[key] as? Long) { "Falta el número $key" }
}

/**
 * Escribe el código de `SchemaCatalog`: un archivo por versión, con sus tablas y sus guardas, y
 * otro con la lista de versiones. Todo sale en orden canónico, así que generar dos veces da el
 * mismo texto, letra por letra.
 */
internal object SchemaCatalogGenerator {
    const val INDEX_FILE = "SchemaCatalogVersions.kt"

    fun versionFile(version: Int): String = "SchemaCatalogVersion$version.kt"

    /** Las guardas como las guarda SQLite: sin el punto y coma final, por nombre. */
    fun triggersOf(guards: List<Guard>): List<CatalogTrigger> = guards
        .map { CatalogTrigger(it.name, it.table, it.sql.removeSuffix(";")) }
        .sortedBy { it.name }

    fun renderVersion(catalog: SchemaVersionCatalog): String {
        val out = KotlinWriter()
        out.header(
            "Generado por la tarea generateSchemaCatalog de :data, desde el JSON que Room exporta",
            "de esta versión y desde SchemaGuards. No se edita a mano. Cuando la versión del",
            "esquema sube, este archivo queda congelado, igual que su JSON: sus guardas ya no",
            "viven en SchemaGuards.",
        )
        out.line(0, "/**")
        out.line(
            0,
            " * El esquema de la versión ${catalog.version}: ${catalog.tables.size} tablas y " +
                "${catalog.triggers.size} guardas.",
        )
        out.line(0, " */")
        val level =
            out.open(
                "internal fun schemaCatalogVersion${catalog.version}(): SchemaVersionCatalog",
                "SchemaVersionCatalog",
            )
        out.line(level, "version = ${catalog.version},")
        out.line(level, "identityHash = ${quote(catalog.identityHash)},")
        out.line(level, "tables =")
        out.line(level + 1, "listOf(")
        for (table in catalog.tables) out.line(level + 2, "${functionName(table.name)}(),")
        out.line(level + 1, "),")
        out.line(level, "triggers = triggers(),")
        out.line(level - 1, ")")
        for (table in catalog.tables) table(out, table)
        triggers(out, catalog.triggers)
        helpers(out)
        return out.text()
    }

    fun renderIndex(versions: List<Int>): String {
        val out = KotlinWriter()
        out.header(
            "Generado por la tarea generateSchemaCatalog de :data, con una línea por cada JSON que",
            "Room exporta del esquema. No se edita a mano.",
        )
        out.line(0, "/** Una entrada por versión del esquema, de la primera a la actual. */")
        val level =
            out.open("internal fun generatedSchemaCatalogs(): List<SchemaVersionCatalog>", "listOf")
        for (version in versions) out.line(level, "schemaCatalogVersion$version(),")
        out.line(level - 1, ")")
        return out.text()
    }

    private fun table(out: KotlinWriter, table: CatalogTable) {
        out.blank()
        val level = out.open(
            "private fun ${functionName(table.name)}(): CatalogTable",
            "CatalogTable",
        )
        out.line(level, "name = ${quote(table.name)},")
        out.line(level, "autoIncrement = ${table.autoIncrement},")
        out.line(level, "columns =")
        out.line(level + 1, "listOf(")
        for (column in table.columns) {
            val arguments =
                buildList {
                    add(quote(column.name))
                    add(quote(column.type))
                    add("notNull = ${column.notNull}")
                    if (column.primaryKeyPosition != 0) {
                        add("primaryKeyPosition = ${column.primaryKeyPosition}")
                    }
                    column.defaultValue?.let { add("defaultValue = ${quote(it)}") }
                }
            out.call(level + 2, "column", arguments)
        }
        out.line(level + 1, "),")
        if (table.indices.isEmpty()) {
            out.line(level, "indices = emptyList(),")
        } else {
            out.line(level, "indices =")
            out.line(level + 1, "listOf(")
            for (index in table.indices) {
                val keys =
                    index.columns.map {
                        val order = if (it.descending) ", descending = true" else ""
                        "key(${quote(it.name)}$order)"
                    }
                val function = if (index.unique) "uniqueIndex" else "index"
                out.call(level + 2, function, listOf(quote(index.name)) + keys)
            }
            out.line(level + 1, "),")
        }
        if (table.foreignKeys.isEmpty()) {
            out.line(level, "foreignKeys = emptyList(),")
        } else {
            out.line(level, "foreignKeys =")
            out.line(level + 1, "listOf(")
            for (key in table.foreignKeys) {
                out.line(level + 2, "CatalogForeignKey(")
                out.line(level + 3, "columns = ${textList(key.columns)},")
                out.line(level + 3, "referencedTable = ${quote(key.referencedTable)},")
                out.line(level + 3, "referencedColumns = ${textList(key.referencedColumns)},")
                out.line(level + 3, "onUpdate = ${quote(key.onUpdate)},")
                out.line(level + 3, "onDelete = ${quote(key.onDelete)},")
                out.line(level + 2, "),")
            }
            out.line(level + 1, "),")
        }
        out.line(level - 1, ")")
    }

    private fun triggers(out: KotlinWriter, triggers: List<CatalogTrigger>) {
        out.blank()
        out.line(0, "/** Las guardas, con el texto exacto que guarda SQLite. */")
        val level = out.open("private fun triggers(): List<CatalogTrigger>", "listOf")
        for (trigger in triggers) {
            out.line(level, "CatalogTrigger(")
            out.line(level + 1, "name = ${quote(trigger.name)},")
            out.line(level + 1, "table = ${quote(trigger.table)},")
            out.line(level + 1, "sql =")
            out.line(level + 2, "\"\"\"")
            // Una sangría más que las comillas: trimIndent la quita entera.
            for (line in rawLines(trigger)) out.line(level + 3, line)
            out.line(level + 2, "\"\"\".trimIndent(),")
            out.line(level, "),")
        }
        out.line(level - 1, ")")
    }

    private fun helpers(out: KotlinWriter) {
        out.blank()
        out.line(0, "/** Una columna. Sin `DEFAULT`, como las crea Room, salvo que diga otro. */")
        out.line(0, "private fun column(")
        out.line(1, "name: String,")
        out.line(1, "type: String,")
        out.line(1, "notNull: Boolean,")
        out.line(1, "primaryKeyPosition: Int = 0,")
        out.line(1, "defaultValue: String? = null,")
        out.line(
            0,
            "): CatalogColumn = CatalogColumn(name, type, notNull, defaultValue, primaryKeyPosition)",
        )
        out.blank()
        out.line(
            0,
            "private fun index(name: String, vararg keys: CatalogIndexColumn): CatalogIndex =",
        )
        out.line(1, "CatalogIndex(name, unique = false, keys.toList())")
        out.blank()
        out.line(
            0,
            "private fun uniqueIndex(name: String, vararg keys: CatalogIndexColumn): " +
                "CatalogIndex =",
        )
        out.line(1, "CatalogIndex(name, unique = true, keys.toList())")
        out.blank()
        out.line(
            0,
            "private fun key(name: String, descending: Boolean = false): CatalogIndexColumn =",
        )
        out.line(1, "CatalogIndexColumn(name, descending)")
    }

    /**
     * Las líneas de una guarda dentro de un texto crudo de Kotlin, que después pasa por
     * `trimIndent`. Lo que `trimIndent` no conserva se rechaza aquí, en vez de llegar al catálogo
     * con otro texto.
     */
    private fun rawLines(trigger: CatalogTrigger): List<String> {
        val lines = trigger.sql.lines()
        require("\"\"\"" !in trigger.sql) {
            "${trigger.name}: un texto crudo no admite tres comillas"
        }
        require(lines.none { it.endsWith(" ") || '\t' in it || '\r' in it }) {
            "${trigger.name}: tiene tabuladores o espacios al final de una línea"
        }
        require(lines.first().isNotBlank() && lines.last().isNotBlank()) {
            "${trigger.name}: empieza o termina con una línea vacía"
        }
        require(lines.any { !it.startsWith(" ") }) { "${trigger.name}: toda línea tiene sangría" }
        // Un $ abriría una plantilla de Kotlin: se escribe como una expresión que lo devuelve.
        return lines.map { it.replace("$", "\${'$'}") }
    }

    private fun functionName(table: String): String =
        table.split('_').mapIndexed { position, word ->
            if (position == 0) word else word.replaceFirstChar(Char::uppercaseChar)
        }.joinToString("") + "Table"

    private fun textList(items: List<String>): String =
        items.joinToString(", ", "listOf(", ")") { quote(it) }

    /** Un texto como literal de Kotlin. */
    private fun quote(text: String): String {
        val escaped =
            text.map { char ->
                when (char) {
                    '\\' -> "\\\\"

                    '"' -> "\\\""

                    '$' -> "\\$"

                    '\n' -> "\\n"

                    else -> {
                        require(char >= ' ') { "Carácter de control en un nombre del esquema" }
                        char.toString()
                    }
                }
            }
        return escaped.joinToString("", "\"", "\"")
    }

    private const val PACKAGE = "io.github.yesidrobayo.shopledger.data.local.guard"

    /** Ancho máximo de una línea de código, el mismo que exige el formato del proyecto. */
    private const val MAX_LINE = 100

    /** Arma el texto con la sangría y el ancho del formato del proyecto. */
    private class KotlinWriter {
        private val text = StringBuilder()

        /** El comentario del archivo, pegado al paquete, y una línea vacía después. */
        fun header(vararg comment: String) {
            for (line in comment) line(0, "// $line")
            line(0, "package $PACKAGE")
            blank()
        }

        fun blank() {
            text.append('\n')
        }

        /** Una línea con [level] niveles de sangría. Una vacía va sin espacios. */
        fun line(level: Int, content: String) {
            if (content.isEmpty()) {
                blank()
                return
            }
            text.append("    ".repeat(level)).append(content).append('\n')
        }

        /**
         * Abre el cuerpo de una función de expresión que es una llamada a [call]: en la línea de
         * la firma si cabe, como lo pide el formato, y si no, en la siguiente. Devuelve el nivel
         * de sus argumentos; la llamada se cierra un nivel menos.
         */
        fun open(signature: String, call: String): Int {
            val sameLine = "$signature = $call("
            if (sameLine.length <= MAX_LINE) {
                line(0, sameLine)
                return 1
            }
            line(0, "$signature =")
            line(1, "$call(")
            return 2
        }

        /**
         * Una llamada en una sola línea si cabe, y si no, con un argumento por línea y coma
         * final, como la deja el formateador.
         */
        fun call(level: Int, function: String, arguments: List<String>) {
            val single = "    ".repeat(level) + "$function(${arguments.joinToString(", ")}),"
            if (single.length <= MAX_LINE) {
                text.append(single).append('\n')
            } else {
                line(level, "$function(")
                for (argument in arguments) line(level + 1, "$argument,")
                line(level, "),")
            }
        }

        fun text(): String = text.toString()
    }
}

/**
 * Los archivos del catálogo en el repositorio: el JSON que Room exporta de cada versión, y el
 * código que se genera desde él. La tarea de Gradle pasa las dos carpetas.
 */
internal class SchemaCatalogSources(private val schemaDir: File, private val sourceDir: File) {
    /** Cada JSON exportado de `AppDatabase`, de la versión 1 a la actual, sin huecos. */
    fun exportedSchemas(): List<ExportedSchema> {
        val files = schemaDir.listFiles().orEmpty().filter { it.isFile }
        val unexpected = files.filterNot { JSON_FILE.matches(it.name) }
        require(unexpected.isEmpty()) { "Archivos que no son un esquema: $unexpected" }
        val schemas =
            files.map { file ->
                val schema = RoomSchemaReader.read(file.readText())
                require("${schema.version}.json" == file.name) {
                    "${file.name} dice ser la versión ${schema.version}"
                }
                schema
            }.sortedBy { it.version }
        require(schemas.map { it.version } == (1..schemas.size).toList()) {
            "Las versiones exportadas tienen huecos: ${schemas.map { it.version }}"
        }
        require(schemas.last().version == AppDatabase.SCHEMA_VERSION) {
            "El último JSON exportado no es el de la versión que compila la app: compila :data"
        }
        return schemas
    }

    /** El catálogo de la versión actual: tablas del JSON y guardas de [SchemaGuards]. */
    fun currentCatalog(): SchemaVersionCatalog {
        val current = exportedSchemas().last()
        return SchemaVersionCatalog(
            version = current.version,
            identityHash = current.identityHash,
            tables = current.tables,
            triggers = SchemaCatalogGenerator.triggersOf(SchemaGuards.all),
        )
    }

    /**
     * Lo que el generador escribe, por archivo: el de la versión actual y la lista de versiones.
     * El de una versión anterior no se toca, pero debe existir: sus guardas ya no están en
     * [SchemaGuards], y no habría de dónde volver a sacarlas.
     */
    fun generatedFiles(): Map<File, String> {
        val versions = exportedSchemas().map { it.version }
        for (version in versions.dropLast(1)) {
            val frozen = sourceDir.resolve(SchemaCatalogGenerator.versionFile(version))
            require(frozen.isFile) {
                "Falta ${frozen.name}: el catálogo de una versión anterior no se puede regenerar"
            }
        }
        return mapOf(
            sourceDir.resolve(SchemaCatalogGenerator.versionFile(versions.last())) to
                SchemaCatalogGenerator.renderVersion(currentCatalog()),
            sourceDir.resolve(SchemaCatalogGenerator.INDEX_FILE) to
                SchemaCatalogGenerator.renderIndex(versions),
        )
    }

    /**
     * Escribe los archivos que cambiaron y devuelve cuáles. Uno igual no se toca, aunque Git lo
     * haya dejado con otro fin de línea: así generar dos veces no cambia nada.
     */
    fun write(): List<File> = generatedFiles().filter { (file, text) ->
        val changed = !file.isFile || normalized(file.readText()) != text
        if (changed) file.writeText(text)
        changed
    }.keys.toList()

    companion object {
        private val JSON_FILE = Regex("""[1-9][0-9]*\.json""")

        /** Propiedades que la tarea de pruebas de `:data` pasa a la JVM. */
        const val SCHEMA_DIR_PROPERTY = "schemaCatalog.schemaDir"
        const val SOURCE_DIR_PROPERTY = "schemaCatalog.sourceDir"

        fun fromSystemProperties(): SchemaCatalogSources = SchemaCatalogSources(
            schemaDir = File(property(SCHEMA_DIR_PROPERTY)),
            sourceDir = File(property(SOURCE_DIR_PROPERTY)),
        )

        /** El texto con el fin de línea de Linux, el que escribe el generador. */
        fun normalized(text: String): String = text.replace("\r\n", "\n")

        private fun property(name: String): String =
            requireNotNull(System.getProperty(name)) { "Falta $name: corre las pruebas con Gradle" }
    }
}
