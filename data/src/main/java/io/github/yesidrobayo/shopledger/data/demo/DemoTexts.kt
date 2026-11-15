package io.github.yesidrobayo.shopledger.data.demo

import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames

/**
 * Los nombres del negocio de ejemplo, en el idioma de la interfaz. Esta capa no tiene textos: los
 * pone la interfaz desde sus recursos traducibles, así el negocio de ejemplo sale en el idioma que
 * eligió la persona. Todos son inventados, sin marcas ni personas reales.
 *
 * @property businessName el nombre del negocio de ejemplo, que encabeza sus comprobantes.
 * @property products un nombre por producto, exactamente [PRODUCT_COUNT], distintos entre sí.
 * @property customer el nombre de la clienta o el cliente de ejemplo.
 * @property supplier el nombre del proveedor de ejemplo.
 * @property expense la descripción del gasto de ejemplo.
 * @property paymentMethodNames el nombre de los métodos de pago iniciales, que una venta congela.
 */
class DemoTexts(
    val businessName: String,
    val products: List<String>,
    val customer: String,
    val supplier: String,
    val expense: String,
    val paymentMethodNames: PaymentMethodNames,
) {
    init {
        require(products.size == PRODUCT_COUNT) { "El negocio de ejemplo lleva $PRODUCT_COUNT" }
        require(products.toSet().size == PRODUCT_COUNT) { "Los productos no se repiten" }
        require(products.all { it.isNotBlank() }) { "Ningún producto va sin nombre" }
        require(
            businessName.isNotBlank() && customer.isNotBlank() && supplier.isNotBlank() &&
                expense.isNotBlank(),
        ) {
            "Ningún nombre de ejemplo va vacío"
        }
    }

    companion object {
        /** Cuántos productos tiene el negocio de ejemplo. */
        const val PRODUCT_COUNT = 6
    }
}
