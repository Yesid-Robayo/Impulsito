package io.github.yesidrobayo.shopledger.domain.model.imports

/**
 * Lo que ya existe en el negocio y decide cómo se lee cada fila: qué nombres ya están, qué
 * códigos están en uso y qué unidades y listas se pueden nombrar. Se lee una vez, al revisar las
 * filas; aplicar la importación lo vuelve a comprobar dentro de su transacción.
 *
 * Todo nombre va normalizado, como se compara al guardar: sin mayúsculas ni tildes.
 */
data class ImportCatalog(
    /** Los nombres de lo que ya existe, vigente o archivado, con si está archivado. */
    val existing: Map<String, Boolean> = emptyMap(),
    /** Los códigos de barras de los productos vigentes, con el nombre normalizado de su dueño. */
    val barcodes: Map<String, String> = emptyMap(),
    /** Las unidades vigentes que se pueden elegir. */
    val units: List<ImportChoice> = emptyList(),
    /** Si la unidad de conteo, la de un producto sin unidad, admite decimales. */
    val defaultUnitAllowsFraction: Boolean = false,
    /** Las listas de precios vigentes que puede llevar un cliente. */
    val priceLists: List<ImportChoice> = emptyList(),
    /** Los nombres de las categorías que ya existen. */
    val categories: Set<String> = emptySet(),
    /** Los nombres de los proveedores vigentes. */
    val suppliers: Set<String> = emptySet(),
)

/**
 * Algo que una celda puede nombrar, como una unidad: su id, los nombres con que se reconoce, ya
 * normalizados, y si admite decimales.
 */
data class ImportChoice(val id: Long, val names: Set<String>, val allowsFraction: Boolean = true)
