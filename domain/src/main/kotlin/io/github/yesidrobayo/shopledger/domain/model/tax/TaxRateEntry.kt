package io.github.yesidrobayo.shopledger.domain.model.tax

/**
 * Una tasa tal como la muestra la pantalla de impuestos: con si es la tasa por defecto y cuántos
 * productos la usan.
 *
 * Una tasa en uso, porque la usa algún producto, archivado o no, o porque es la tasa por defecto,
 * solo se archiva: eliminarla dejaría esos productos, o el negocio, sin tasa sin que nadie lo pida.
 * Una sin uso se puede eliminar, porque las ventas y compras que la usaron guardan su propia copia
 * de la tasa y del nombre.
 */
data class TaxRateEntry(val rate: TaxRate, val isDefault: Boolean, val productCount: Int) {
    /** Si está en uso: entonces solo se archiva. */
    val isInUse: Boolean get() = isDefault || productCount > 0
}
