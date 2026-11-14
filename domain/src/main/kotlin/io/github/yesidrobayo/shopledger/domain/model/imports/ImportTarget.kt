package io.github.yesidrobayo.shopledger.domain.model.imports

/**
 * Lo que se puede importar, cada uno con sus datos de destino. La clave de cada dato es la clave
 * estable en inglés de su columna en la exportación: así un archivo exportado vuelve a entrar con
 * sus columnas reconocidas, en cualquier idioma.
 *
 * [ignoredColumns] son columnas que la exportación escribe y que la importación no usa, como un
 * saldo, que sale de su libro y nunca de un archivo. Se reconocen igual, para que no vayan a la
 * pantalla de relacionar columnas como si fueran desconocidas.
 */
enum class ImportTarget(
    /** Los datos que se pueden traer, en el orden en que se ofrecen al relacionar columnas. */
    val fields: List<ImportField>,
    /** Las columnas conocidas que no se importan. */
    val ignoredColumns: List<String>,
) {
    PRODUCTS(
        listOf(
            ImportField("name", ImportFieldKind.TEXT, required = true),
            ImportField("category", ImportFieldKind.TEXT),
            ImportField("default_supplier", ImportFieldKind.TEXT),
            ImportField("base_unit", ImportFieldKind.TEXT),
            ImportField("stock_mode", ImportFieldKind.TEXT),
            ImportField("barcode", ImportFieldKind.CODE),
            ImportField("sale_price", ImportFieldKind.AMOUNT),
            ImportField("average_cost", ImportFieldKind.AMOUNT),
            ImportField("initial_cost", ImportFieldKind.AMOUNT),
            ImportField("on_hand", ImportFieldKind.QUANTITY),
            ImportField("min_stock", ImportFieldKind.QUANTITY),
            ImportField("usage", ImportFieldKind.TEXT),
        ),
        listOf("stock_value", "archived"),
    ),
    CUSTOMERS(
        listOf(
            ImportField("name", ImportFieldKind.TEXT, required = true),
            ImportField("phone", ImportFieldKind.TEXT),
            ImportField("email", ImportFieldKind.TEXT),
            ImportField("notes", ImportFieldKind.TEXT),
            ImportField("price_list", ImportFieldKind.TEXT),
        ),
        listOf("balance", "points_balance", "archived", "created_at"),
    ),
    SUPPLIERS(
        listOf(
            ImportField("name", ImportFieldKind.TEXT, required = true),
            ImportField("phone", ImportFieldKind.TEXT),
            ImportField("email", ImportFieldKind.TEXT),
            ImportField("notes", ImportFieldKind.TEXT),
            ImportField("payment_terms_days", ImportFieldKind.COUNT),
        ),
        listOf("balance", "archived"),
    ),
    PRICE_INDEX(
        listOf(
            ImportField("month", ImportFieldKind.MONTH, required = true),
            ImportField("change_percent", ImportFieldKind.PERCENT, required = true),
        ),
        listOf("created_at"),
    ),
    ;

    /** El dato de clave [key], o `null` si este destino no lo trae. */
    fun field(key: String): ImportField? = fields.firstOrNull { it.key == key }
}

/** Un dato que se puede traer de una columna del archivo. */
data class ImportField(
    /** La clave estable en inglés, la misma de la columna en la exportación. */
    val key: String,
    /** Cómo se lee el texto de la celda. */
    val kind: ImportFieldKind,
    /** Si sin esta columna no se puede importar. */
    val required: Boolean = false,
)

/** Cómo se lee la celda de un dato. */
enum class ImportFieldKind {
    /** Texto tal cual, recortado. */
    TEXT,

    /** Un código, como el de barras: texto, sin el apóstrofo que lo protege en una hoja. */
    CODE,

    /** Un monto, en la moneda del negocio y con sus decimales. */
    AMOUNT,

    /** Una cantidad, con hasta tres decimales. */
    QUANTITY,

    /** Un porcentaje, con hasta dos decimales. */
    PERCENT,

    /** Un entero sin decimales, como unos días. */
    COUNT,

    /** Un mes, `AAAA-MM`. */
    MONTH,
}
