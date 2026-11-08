package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogChoices
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForEdit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.FindProductNameClashUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.LoadProductForEditUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveCatalogChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
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
}

/** Las operaciones del formulario sobre los casos de uso de catálogo. */
class ProductEditUseCases(
    private val loadUseCase: LoadProductForEditUseCase,
    private val choicesUseCase: ObserveCatalogChoicesUseCase,
    private val nameClashUseCase: FindProductNameClashUseCase,
    private val createUseCase: CreateProductUseCase,
    private val editUseCase: EditProductUseCase,
    private val createCategoryUseCase: CreateCategoryUseCase,
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
}
