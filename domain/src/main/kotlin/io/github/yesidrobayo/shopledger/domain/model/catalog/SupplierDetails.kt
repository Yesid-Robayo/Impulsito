package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Lo que la persona escribe de un proveedor.
 *
 * Solo el nombre es obligatorio. [normalizedName] es [name] pasado por `TextNormalizer`, y no se
 * repite entre proveedores sin archivar. [paymentTermsDays] es el plazo habitual de pago, en días.
 */
data class SupplierDetails(
    val name: String,
    val normalizedName: String,
    val phone: String?,
    val email: String?,
    val notes: String?,
    val paymentTermsDays: Int?,
)
