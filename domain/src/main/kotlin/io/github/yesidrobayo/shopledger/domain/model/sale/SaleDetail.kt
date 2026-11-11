package io.github.yesidrobayo.shopledger.domain.model.sale

/**
 * Una venta guardada con todo lo que muestra su detalle: la cabecera, sus líneas en el orden en que
 * se guardaron y sus pagos. [archivedProductIds] son los productos de sus líneas que hoy están
 * archivados: la línea conserva su nombre congelado, y el detalle lo marca.
 */
data class SaleDetail(
    val sale: Sale,
    val lines: List<SaleLine>,
    val payments: List<SalePayment>,
    val archivedProductIds: Set<Long>,
)
