package io.github.yesidrobayo.shopledger.domain.model.export

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate

/**
 * Las fechas de negocio que entran en una exportación, con [from] y [to] incluidas. Filtra los
 * documentos y los movimientos por su `business_date`: ventas, compras, gastos, movimientos de stock,
 * cambios de precio y las líneas de las ventas y compras que entran. Los catálogos, como productos y
 * proveedores, van completos.
 */
@ConsistentCopyVisibility
data class CsvDateRange private constructor(val from: BusinessDate, val to: BusinessDate) {
    companion object {
        /** El rango de [from] a [to], o `null` si termina antes de empezar. */
        fun of(from: BusinessDate, to: BusinessDate): CsvDateRange? =
            if (from <= to) CsvDateRange(from, to) else null
    }
}
