package io.github.yesidrobayo.shopledger.domain.model.customer

/**
 * Un cliente tal como lo escribió la persona en su hoja, antes de validarlo.
 *
 * Solo el nombre es obligatorio: teléfono, correo y notas en blanco se guardan sin valor. La lista
 * de precios no se escribe en la hoja mientras no haya más de una; al editar, la pantalla devuelve
 * la que ya tenía para no perderla.
 */
data class CustomerForm(
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val priceListId: Long? = null,
)
