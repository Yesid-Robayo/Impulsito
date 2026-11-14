package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantIssue
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductVariantRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.catalog.PlannedVariant
import io.github.yesidrobayo.shopledger.domain.rule.catalog.VariantPlanCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.VariantRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceEntry
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceRule
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/** Cómo termina crear variantes. Salvo [Created], no se escribió nada. */
sealed interface CreateVariantsResult {
    /** Quedaron creadas, en el orden en que llegaron, en una sola transacción. */
    data class Created(val productIds: List<Long>) : CreateVariantsResult

    /**
     * Lo escrito no se puede crear, por todos estos motivos a la vez: de los ejes, y por el índice
     * de cada variante, de su combinación y de sus montos y cantidades.
     */
    data class Invalid(
        val axisIssues: Set<VariantIssue>,
        val variantIssues: Map<Int, Set<VariantIssue>>,
        val formIssues: Map<Int, Set<ProductFormIssue>>,
    ) : CreateVariantsResult

    /** El producto principal ya no existe. */
    data object ParentNotFound : CreateVariantsResult

    /** El producto principal está archivado: hay que reactivarlo antes. */
    data object ParentArchived : CreateVariantsResult

    /** El producto elegido ya es una variante: las variantes tienen un solo nivel. */
    data object ParentIsVariant : CreateVariantsResult

    /** Un producto hecho de componentes no tiene variantes: su stock es el de sus componentes. */
    data object ParentMadeOfComponents : CreateVariantsResult

    /**
     * El producto principal tiene stock propio. El principal no tiene stock: su stock es la suma
     * de sus variantes. Hay que llevar su stock a cero con un ajuste antes, para que esas unidades
     * no queden fuera de toda variante.
     */
    data object ParentHasStock : CreateVariantsResult

    /**
     * Otro producto sin archivar ya tiene el nombre de la variante [index], comparado sin
     * mayúsculas ni tildes. [existingName] es su nombre tal como se guardó.
     */
    data class DuplicateName(val index: Int, val existingName: String) : CreateVariantsResult

    /** Otro producto sin archivar ya tiene el código de barras de la variante [index]. */
    data class DuplicateBarcode(val index: Int, val existingName: String) : CreateVariantsResult

    /** Alguna variante lleva precio o costo, y la moneda del negocio sigue sin confirmar. */
    data object CurrencyUnconfirmed : CreateVariantsResult

    /** La zona del negocio no se reconoce, y sin ella no hay fecha de negocio que guardar. */
    data object UnknownTimeZone : CreateVariantsResult

    /** El stock inicial de una variante deja su stock o su valor por encima de [limit]. */
    data class AboveLimit(val limit: Limit) : CreateVariantsResult

    /** La base no pudo escribir. No quedó nada a medias. */
    data class StorageFailed(val failure: StorageFailure) : CreateVariantsResult
}

/**
 * Crea variantes de un producto, todas en una sola transacción: o quedan todas o ninguna.
 *
 * Cada variante es un producto con su libro, ligado a su producto principal: hereda su categoría,
 * su proveedor, su unidad, su modo de stock, su impuesto y su margen objetivo, y lleva su propio
 * precio, costo, stock inicial, mínimo y código. Por cada una escribe lo mismo que crear un
 * producto suelto: el producto, la primera fila de su historial de precios y, con stock inicial,
 * la primera fila de su libro.
 *
 * El principal no tiene stock propio ni se vende directo, así que deja de vigilarse: su alerta
 * activa, si tenía una, queda resuelta en la misma transacción.
 */
class CreateVariantsUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val variants: ProductVariantRepository,
    private val units: UnitRepository,
    private val systemRows: SystemRowRepository,
    private val priceHistory: PriceHistoryRepository,
    private val ledger: StockLedgerRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
) {
    /** Crea [drafts] como variantes de [parentId], con los ejes [axes]. */
    suspend operator fun invoke(
        parentId: Long,
        axes: VariantAxes,
        drafts: List<VariantDraft>,
    ): CreateVariantsResult = storageSafe(CreateVariantsResult::StorageFailed) {
        transactions.write { create(parentId, axes, drafts) }
    }

    private suspend fun create(
        parentId: Long,
        axes: VariantAxes,
        drafts: List<VariantDraft>,
    ): TxResult<CreateVariantsResult> {
        val parent = products.findById(parentId)
            ?: return reject(CreateVariantsResult.ParentNotFound)
        parentProblem(parent)?.let { return reject(it) }
        val unit = checkNotNull(units.findById(parent.details.baseUnitId)) {
            "La unidad base de un producto no se borra"
        }
        val existing = variants.findVariants(parentId)
        val plan = when (
            val check = VariantRules.check(parent, axes, drafts, existing, unit.allowsFraction)
        ) {
            is VariantPlanCheck.Invalid -> return reject(
                CreateVariantsResult.Invalid(
                    check.axisIssues,
                    check.variantIssues,
                    check.formIssues,
                ),
            )

            is VariantPlanCheck.Valid -> check
        }
        plan.variants.forEachIndexed { index, variant ->
            products.findActiveByNormalizedName(variant.normalizedName)?.let {
                return reject(CreateVariantsResult.DuplicateName(index, it.details.name))
            }
            variant.barcode?.let { products.findActiveByBarcode(it) }?.let {
                return reject(CreateVariantsResult.DuplicateBarcode(index, it.details.name))
            }
        }
        val time = calendar.stamp(clock.now())
            ?: return reject(CreateVariantsResult.UnknownTimeZone)
        val hasMoney = drafts.any { it.salePrice != Money.ZERO || it.cost != Money.ZERO }
        if (hasMoney && !currencyLock.lock(time.occurredAt)) {
            return reject(CreateVariantsResult.CurrencyUnconfirmed)
        }
        val tracked = parent.details.stockMode == ProductStockMode.TRACKED
        val openings = plan.variants.map { variant ->
            when (val opening = openingOf(variant.draft, tracked)) {
                is Checked.Value -> opening.value

                // Con los topes del formulario no se desborda; si pasara, pasa de todo tope.
                Checked.Overflow -> return reject(
                    CreateVariantsResult.AboveLimit(Limit.DOCUMENT_TOTAL),
                )

                is Checked.AboveLimit -> return reject(
                    CreateVariantsResult.AboveLimit(opening.limit),
                )
            }
        }
        // El principal deja de vigilarse antes de crear las variantes, que sí se vigilan.
        for (alert in alerts.findActiveByProducts(listOf(parentId))) {
            alerts.resolve(alert.id, time.occurredAt)
        }
        val ids = plan.variants.mapIndexed { index, variant ->
            insert(parent, plan.axes, variant, openings[index], time)
        }
        return TxResult.Commit(CreateVariantsResult.Created(ids))
    }

    /** Lo que impide que [parent] tenga variantes, o `null` si puede tenerlas. */
    private fun parentProblem(parent: Product): CreateVariantsResult? = when {
        parent.isArchived -> CreateVariantsResult.ParentArchived

        parent.details.parentProductId != null -> CreateVariantsResult.ParentIsVariant

        parent.details.stockMode == ProductStockMode.COMPONENTS ->
            CreateVariantsResult.ParentMadeOfComponents

        parent.onHand != QuantityMilli.ZERO || parent.stockValue != Money.ZERO ->
            CreateVariantsResult.ParentHasStock

        else -> null
    }

    /** La primera fila del libro de [draft], o `null` si no lleva stock inicial. */
    private fun openingOf(draft: VariantDraft, tracked: Boolean): Checked<OpeningBalanceEntry?> {
        if (!tracked || draft.initialStock == QuantityMilli.ZERO) return Checked.Value(null)
        return OpeningBalanceRule.entry(
            CostState.initial(draft.cost),
            draft.initialStock,
            draft.cost,
        )
    }

    private suspend fun insert(
        parent: Product,
        axes: VariantAxes,
        variant: PlannedVariant,
        opening: OpeningBalanceEntry?,
        time: RecordTime,
    ): Long {
        val draft = variant.draft
        val mode = parent.details.stockMode
        val tracked = mode == ProductStockMode.TRACKED
        val initialCost = if (tracked) draft.cost else Money.ZERO
        val details = parent.details.copy(
            name = variant.name,
            normalizedName = variant.normalizedName,
            tracksLots = false,
            fixedUnitCost = if (mode == ProductStockMode.UNTRACKED) draft.cost else Money.ZERO,
            minStock = draft.minStock,
            barcode = variant.barcode,
            parentProductId = parent.id,
            variantAxis1 = axes.first,
            variantAxis2 = axes.second,
            variantValue1 = variant.values.first,
            variantValue2 = variant.values.second,
            photoMediaId = null,
            referenceCost = null,
            referencePrice = null,
            expiryNoticeDays = null,
        )
        val productId = products.insert(
            NewProduct(
                details = details,
                salePrice = draft.salePrice,
                initialCost = initialCost,
                replacementCost = Money.ZERO,
                replacementCostAt = null,
                createdAt = time.occurredAt,
            ),
        )
        priceHistory.append(
            NewPriceChange(
                productId = productId,
                priceListId = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST),
                oldPrice = null,
                newPrice = draft.salePrice,
                source = PriceChangeSource.INITIAL,
                batchId = null,
                bulkRule = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
            ),
        )
        val onHand = if (opening == null) {
            QuantityMilli.ZERO
        } else {
            val location = systemRows.requireId(SystemRow.MAIN_LOCATION)
            val quantity = draft.initialStock
            ledger.append(
                openingMovement(productId, location, quantity, initialCost, opening, time),
            )
            opening.after.onHand
        }
        val transition = LowStockEvaluator.evaluateWithoutMovement(
            stockMode = mode,
            isArchived = false,
            onHand = onHand,
            minStock = draft.minStock,
            hasActiveAlert = false,
        )
        alerts.applyWithoutNotice(productId, transition, activeAlertId = null, time = time)
        return productId
    }

    private fun reject(result: CreateVariantsResult): TxResult<CreateVariantsResult> =
        TxResult.Rollback(result)
}
