package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Qué mide una unidad. Es un valor guardado en la base: su nombre no cambia nunca.
 *
 * Solo agrupa las unidades al elegirlas: no convierte entre ellas. La conversión de un producto la
 * da su factor, que escribe la persona, porque una caja o un paquete no tienen tamaño fijo y no se
 * asume el sistema de medidas de ningún país.
 */
enum class UnitKind {
    /** Piezas que se cuentan, como unidad, caja, paquete o docena. */
    COUNT,

    /** Peso, como kilogramo, gramo, libra u onza. */
    MASS,

    /** Volumen, como litro, mililitro o galón. */
    VOLUME,

    /** Longitud, como metro. */
    LENGTH,

    /** Cualquier otra medida que cree la persona. */
    OTHER,
}
