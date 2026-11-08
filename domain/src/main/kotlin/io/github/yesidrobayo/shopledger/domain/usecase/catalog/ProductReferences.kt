package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogReference
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository

/**
 * Comprueba, dentro de la transacción, que lo que eligió la persona en el formulario de un producto
 * todavía se puede elegir.
 *
 * Entre abrir el formulario y guardar pudo cambiar: una categoría eliminada, un proveedor
 * archivado. La base lo rechazaría con una llave rota, pero sin decir qué campo corregir. Lo que el
 * producto ya tenía se respeta aunque esté archivado: un proveedor habitual archivado sigue
 * mostrándose, y una unidad archivada sigue siendo la del libro.
 */
internal class ProductReferences(
    private val categories: CategoryRepository,
    private val suppliers: SupplierRepository,
    private val units: UnitRepository,
    private val systemRows: SystemRowRepository,
) {
    /**
     * La unidad base de [form], o `null` si ya no se puede elegir. Sin unidad elegida es la de
     * conteo. [current] es el producto antes de editarlo, o `null` al crearlo.
     */
    suspend fun unitOf(form: ProductForm, current: ProductDetails?): MeasureUnit? {
        val unitId = form.baseUnitId ?: systemRows.requireId(SystemRow.DEFAULT_UNIT)
        val unit = units.findById(unitId) ?: return null
        return unit.takeUnless { it.isArchived && it.id != current?.baseUnitId }
    }

    /** La categoría o el proveedor de [form] que ya no se pueden elegir, o `null` si ninguno. */
    suspend fun missingIn(form: ProductForm, current: ProductDetails?): CatalogReference? {
        val categoryId = form.categoryId
        if (categoryId != null && categories.findById(categoryId) == null) {
            return CatalogReference.CATEGORY
        }
        val supplierId = form.defaultSupplierId
        if (supplierId != null && supplierId != current?.defaultSupplierId) {
            val supplier = suppliers.findById(supplierId)
            if (supplier == null || supplier.isArchived) return CatalogReference.SUPPLIER
        }
        return null
    }
}
