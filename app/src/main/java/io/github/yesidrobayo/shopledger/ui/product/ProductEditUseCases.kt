package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogChoices
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductExtras
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForEdit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.FindProductNameClashUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ImportProductPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.LoadProductForEditUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveCatalogChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductConversionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductExtrasUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductExtrasResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductExtrasUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

    /** Trae la foto elegida, recodificada y sin EXIF. Se anota al guardar el producto. */
    suspend fun importPhoto(source: String): ImageImport

    /**
     * Los campos propios vigentes, las etiquetas y lo que ya tiene el producto [productId]; sin
     * id, un producto nuevo, sin valores ni etiquetas.
     */
    fun extras(productId: Long?): Flow<ProductExtras>

    /**
     * Guarda los campos propios y las etiquetas del producto [productId], ya guardado, en su
     * propia transacción. [values] va en forma neutra, por id de campo.
     */
    suspend fun saveExtras(
        productId: Long,
        values: Map<Long, String>,
        tagIds: Set<Long>,
    ): SaveProductExtrasResult

    /** Hoy y la semana del negocio, para elegir una fecha; `null` con una zona desconocida. */
    fun calendar(): Flow<FieldCalendar?>
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
    private val importPhotoUseCase: ImportProductPhotoUseCase,
    private val extrasUseCase: ObserveProductExtrasUseCase,
    private val saveExtrasUseCase: SaveProductExtrasUseCase,
    private val calendarSource: BusinessCalendarSource,
    private val now: () -> Instant,
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

    override suspend fun importPhoto(source: String): ImageImport = importPhotoUseCase(source)

    override fun extras(productId: Long?): Flow<ProductExtras> = extrasUseCase(productId)

    override suspend fun saveExtras(
        productId: Long,
        values: Map<Long, String>,
        tagIds: Set<Long>,
    ): SaveProductExtrasResult = saveExtrasUseCase(productId, values, tagIds)

    override fun calendar(): Flow<FieldCalendar?> = calendarSource.observe()
        .map { result ->
            when (result) {
                is BusinessCalendarResult.Ready ->
                    FieldCalendar(
                        result.calendar.businessDateOf(now()),
                        result.calendar.weekStartDay,
                    )

                is BusinessCalendarResult.UnknownZone -> null
            }
        }
        .distinctUntilChanged()
}
