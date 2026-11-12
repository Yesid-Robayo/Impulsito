package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogChoices
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForEdit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.FindProductNameClashUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.LoadProductForEditUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveCatalogChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductConversionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitUseCase
import kotlinx.coroutines.flow.Flow

/**
 * Lo que el formulario de un producto le pide al dominio. Es una interfaz para que las pruebas del
 * ViewModel den respuestas fijas sin una base.
 */
interface ProductEditOperations {
    suspend fun load(productId: Long): ProductForEdit?

    fun choices(): Flow<CatalogChoices>

    suspend fun nameClash(name: String, editingId: Long?): String?

    suspend fun create(form: ProductForm, initialStock: QuantityMilli): SaveProductResult

    suspend fun edit(productId: Long, form: ProductForm): SaveProductResult

    suspend fun createCategory(name: String): SaveCategoryResult

    /** Las conversiones de compra y venta del producto [productId]. */
    fun conversions(productId: Long): Flow<List<ProductConversion>>

    suspend fun saveConversion(editingId: Long?, draft: ConversionDraft): SaveConversionResult

    suspend fun deleteConversion(id: Long): DeleteConversionResult

    /** Crea una unidad sin salir del formulario. */
    suspend fun saveUnit(draft: UnitDraft): SaveUnitResult

    /** Lo que el negocio fija para medir márgenes y sugerir precios. */
    fun pricing(): Flow<PricingContext>
}

/** Las operaciones del formulario sobre los casos de uso de catálogo. */
class ProductEditUseCases(
    private val loadUseCase: LoadProductForEditUseCase,
    private val choicesUseCase: ObserveCatalogChoicesUseCase,
    private val nameClashUseCase: FindProductNameClashUseCase,
    private val createUseCase: CreateProductUseCase,
    private val editUseCase: EditProductUseCase,
    private val createCategoryUseCase: CreateCategoryUseCase,
    private val conversionsUseCase: ObserveProductConversionsUseCase,
    private val saveConversionUseCase: SaveConversionUseCase,
    private val deleteConversionUseCase: DeleteConversionUseCase,
    private val saveUnitUseCase: SaveUnitUseCase,
    private val pricingUseCase: ObservePricingContextUseCase,
) : ProductEditOperations {
    override suspend fun load(productId: Long): ProductForEdit? = loadUseCase(productId)

    override fun choices(): Flow<CatalogChoices> = choicesUseCase()

    override suspend fun nameClash(name: String, editingId: Long?): String? =
        nameClashUseCase(name, editingId)

    override suspend fun create(form: ProductForm, initialStock: QuantityMilli): SaveProductResult =
        createUseCase(form, initialStock)

    override suspend fun edit(productId: Long, form: ProductForm): SaveProductResult =
        editUseCase(productId, form)

    override suspend fun createCategory(name: String): SaveCategoryResult =
        createCategoryUseCase(name)

    override fun conversions(productId: Long): Flow<List<ProductConversion>> =
        conversionsUseCase(productId)

    override suspend fun saveConversion(
        editingId: Long?,
        draft: ConversionDraft,
    ): SaveConversionResult = saveConversionUseCase(editingId, draft)

    override suspend fun deleteConversion(id: Long): DeleteConversionResult =
        deleteConversionUseCase(id)

    override suspend fun saveUnit(draft: UnitDraft): SaveUnitResult = saveUnitUseCase(null, draft)

    override fun pricing(): Flow<PricingContext> = pricingUseCase()
}
