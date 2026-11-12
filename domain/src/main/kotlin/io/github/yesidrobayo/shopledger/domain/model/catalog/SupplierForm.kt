package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Un proveedor tal como lo escribió la persona en su hoja, antes de validarlo.
 *
 * Solo el nombre es obligatorio: teléfono, correo y notas en blanco se guardan sin valor. El plazo
 * de pago no se escribe en la hoja mientras las compras a plazo no estén en uso; al editar, la
 * pantalla devuelve el que ya tenía para no perderlo.
 */
data class SupplierForm(
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val paymentTermsDays: Int? = null,
)
