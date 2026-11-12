package io.github.yesidrobayo.shopledger.domain.model.report

/** El tamaño de los períodos que recorren las estadísticas. */
enum class PeriodKind {
    /** Un día. */
    DAY,

    /** Una semana, que empieza en el día que eligió el negocio. */
    WEEK,

    /** Un mes del calendario. */
    MONTH,
}
