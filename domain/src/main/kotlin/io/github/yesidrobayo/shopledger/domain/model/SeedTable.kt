package io.github.yesidrobayo.shopledger.domain.model

/**
 * Las tablas que nacen con datos iniciales y las claves estables de esas filas, en el orden en que
 * se crean.
 *
 * Una fila inicial lleva su clave en inglés y ningún nombre: el texto visible sale del recurso
 * traducible de la clave, en el idioma de la interfaz, hasta que la persona lo edite. Así cambiar
 * de idioma cambia también lo que nadie renombró, y la clave sigue identificando la fila aunque se
 * renombre. Ninguna fila trae una marca, una tasa de impuesto ni nada de un país, y ninguna cuenta
 * para decidir si la base tiene datos del negocio.
 *
 * Las claves son valores guardados en la base: no cambian nunca. Una nueva exige su recurso en
 * cada idioma, que una prueba comprueba.
 */
enum class SeedTable(
    /** El nombre de la tabla, que también arma el nombre del recurso de cada fila. */
    val tableName: String,
    /** Las claves de sus filas iniciales, en el orden en que se crean y se ofrecen. */
    val systemKeys: List<String>,
) {
    /** Las categorías de gasto. La última, la de otros gastos, nunca se archiva ni se borra. */
    EXPENSE_CATEGORIES(
        "expense_categories",
        listOf(
            "RENT",
            "UTILITIES",
            "INTERNET_PHONE",
            "TRANSPORT",
            "PACKAGING_SUPPLIES",
            "ADVERTISING",
            "TAXES_FEES",
            "WAGES",
            OTHER_EXPENSE_CATEGORY,
        ),
    ),

    /** Las unidades. La primera es la unidad base por defecto de todo producto. */
    UNITS(
        "units",
        listOf(
            "UNIT",
            "KILOGRAM",
            "GRAM",
            "LITER",
            "MILLILITER",
            "POUND",
            "OUNCE",
            "GALLON",
            "METER",
            "BOX",
            "PACK",
            "DOZEN",
        ),
    ),

    /** Un método de pago genérico por clase, sin marcas: la clave es el nombre de su clase. */
    PAYMENT_METHODS(
        "payment_methods",
        listOf("CASH", "CARD", "TRANSFER", "WALLET", "ON_ACCOUNT", "OTHER"),
    ),

    /** La lista de precios inicial, cuyo precio es el de venta de cada producto. */
    PRICE_LISTS("price_lists", listOf("DEFAULT")),

    /** La ubicación inicial del stock. */
    LOCATIONS("locations", listOf("MAIN")),

    /** La caja: la cuenta de dinero inicial. */
    MONEY_ACCOUNTS("money_accounts", listOf("CASH")),
}

/** La categoría de otros gastos: la única inicial que nunca se archiva ni se borra. */
const val OTHER_EXPENSE_CATEGORY: String = "OTHER"
