package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogReference
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductFormRules
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductHistoryLock
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ReplacementCostRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockTransition
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/**
 * Guarda la edición de un producto en una sola transacción: sus datos, su precio y su costo
 * inicial, con las restricciones que protegen su historia.
 *
 * - **Nombre.** Cambiarlo no toca las ventas: cada línea guarda su propio nombre y sus valores.
 * - **Precio.** Si cambia, deja una fila en su historial; si es el mismo, no escribe nada de precio.
 * - **Costo.** El costo inicial cambia, y con él el promedio, solo mientras el producto no tenga
 *   filas en su libro. Después lo lleva el libro, y se corrige con una corrección de costo.
 * - **Unidad, modo de stock y lotes.** Con historia ya no cambian: el libro no mezcla unidades.
 * - **Reposición.** Se corrige a mano sin tocar el costo promedio, y se fecha al guardarla. Un
 *   producto que deja de llevar stock propio la pierde, porque ya no se compra.
 * - **Mínimo.** Si el producto queda bajo, crea su alerta callada; si deja de estarlo, la resuelve.
 * - **Archivado.** Se puede renombrar, para reactivarlo si su nombre choca, pero no cambia de
 *   precio ni de costo hasta reactivarlo.
 *
 * Una edición que no cambia nada no escribe nada ni cuenta un cambio sin respaldar. Una que deja
 * algún monto exige la moneda confirmada, y la bloquea si es el primero.
 */
class EditProductUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    categories: CategoryRepository,
    suppliers: SupplierRepository,
    units: UnitRepository,
    private val systemRows: SystemRowRepository,
    private val priceHistory: PriceHistoryRepository,
    private val ledger: StockLedgerRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
) {
    private val references = ProductReferences(categories, suppliers, units, systemRows)

    suspend operator fun invoke(productId: Long, form: ProductForm): SaveProductResult =
        storageSafe(SaveProductResult::StorageFailed) {
            transactions.write { edit(productId, form) }
        }

    private suspend fun edit(productId: Long, form: ProductForm): TxResult<SaveProductResult> {
        val product = products.findById(productId) ?: return reject(SaveProductResult.NotFound)
        val current = product.details
        val unit = references.unitOf(form, current)
            ?: return reject(SaveProductResult.MissingReference(CatalogReference.UNIT))
        val valid = when (
            val check = ProductFormRules.check(form, QuantityMilli.ZERO, unit.allowsFraction)
        ) {
            is ProductFormCheck.Invalid -> return reject(SaveProductResult.Invalid(check.issues))
            is ProductFormCheck.Valid -> check
        }
        references.missingIn(form, current)?.let {
            return reject(SaveProductResult.MissingReference(it))
        }
        // Un archivado no reserva su nombre ni su código, así que tampoco choca con nadie.
        if (!product.isArchived) {
            products.findActiveByNormalizedName(valid.normalizedName)
                ?.takeIf { it.id != productId }
                ?.let { return reject(SaveProductResult.DuplicateName(it.details.name)) }
            valid.barcode?.let { products.findActiveByBarcode(it) }
                ?.takeIf { it.id != productId }
                ?.let { return reject(SaveProductResult.DuplicateBarcode(it.details.name)) }
        }
        val requested = current.copy(
            name = valid.name,
            normalizedName = valid.normalizedName,
            categoryId = form.categoryId,
            defaultSupplierId = form.defaultSupplierId,
            baseUnitId = unit.id,
            stockMode = form.stockMode,
            // Solo un producto con stock propio lleva lotes.
            tracksLots = current.tracksLots && form.stockMode == ProductStockMode.TRACKED,
            fixedUnitCost = if (form.stockMode ==
                ProductStockMode.UNTRACKED
            ) {
                form.cost
            } else {
                Money.ZERO
            },
            minStock = form.minStock,
            barcode = valid.barcode,
        )
        val locked = ProductHistoryLock.lockedChanges(
            current,
            requested,
            products.hasHistory(productId),
        )
        if (locked.isNotEmpty()) return reject(SaveProductResult.LockedByHistory(locked))
        return save(product, requested, form)
    }

    private suspend fun save(
        product: Product,
        requested: ProductDetails,
        form: ProductForm,
    ): TxResult<SaveProductResult> {
        val current = product.details
        val initialCost = if (form.stockMode == ProductStockMode.TRACKED) form.cost else Money.ZERO
        val priceChanged = form.salePrice != product.salePrice
        val initialCostChanged = initialCost != product.initialCost
        val replacement = when {
            form.stockMode != ProductStockMode.TRACKED -> Money.ZERO
            else -> form.replacementCost ?: product.replacementCost
        }
        val replacementChanged = replacement != product.replacementCost
        val costChanged = initialCostChanged || replacementChanged ||
            requested.fixedUnitCost != current.fixedUnitCost
        if (product.isArchived && (priceChanged || costChanged)) {
            return reject(SaveProductResult.ArchivedPriceLocked)
        }
        if (initialCostChanged && ledger.findByProduct(product.id).isNotEmpty()) {
            return reject(SaveProductResult.CostLockedByMovements)
        }
        if (requested == current && !priceChanged && !initialCostChanged && !replacementChanged) {
            // Nada cambió: no se escribe ni se cuenta un cambio sin respaldar.
            return TxResult.Rollback(SaveProductResult.Saved(product.id))
        }
        val watchChanged = requested.minStock != current.minStock ||
            requested.stockMode != current.stockMode
        val activeAlert = if (watchChanged) {
            alerts.findActiveByProducts(listOf(product.id)).firstOrNull()
        } else {
            null
        }
        val transition = if (watchChanged) {
            LowStockEvaluator.evaluateWithoutMovement(
                stockMode = requested.stockMode,
                isArchived = product.isArchived,
                onHand = product.onHand,
                minStock = requested.minStock,
                hasActiveAlert = activeAlert != null,
            )
        } else {
            LowStockTransition.Unchanged
        }
        val now = clock.now()
        // Con algún monto, la moneda debe estar confirmada; si es el primero, la bloquea ahora.
        val hasMoney = form.salePrice != Money.ZERO || initialCost != Money.ZERO ||
            requested.fixedUnitCost != Money.ZERO || replacement != Money.ZERO
        if (hasMoney &&
            !currencyLock.lock(now)
        ) {
            return reject(SaveProductResult.CurrencyUnconfirmed)
        }
        // La fecha de negocio solo hace falta para la fila de precio o para una alerta.
        val time = if (priceChanged || transition != LowStockTransition.Unchanged) {
            calendar.stamp(now) ?: return reject(SaveProductResult.UnknownTimeZone)
        } else {
            null
        }
        products.updateDetails(product.id, requested, now)
        if (initialCostChanged) products.updateInitialCost(product.id, initialCost, now)
        if (replacementChanged) {
            val at = ReplacementCostRules.manualStamp(replacement, now)
            products.updateReplacementCost(product.id, replacement, at, now)
        }
        if (priceChanged) {
            val at = checkNotNull(time)
            products.updateSalePrice(product.id, form.salePrice, now)
            priceHistory.append(
                NewPriceChange(
                    productId = product.id,
                    priceListId = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST),
                    oldPrice = product.salePrice,
                    newPrice = form.salePrice,
                    source = PriceChangeSource.MANUAL,
                    batchId = null,
                    bulkRule = null,
                    occurredAt = at.occurredAt,
                    businessDate = at.businessDate,
                ),
            )
        }
        if (time != null) alerts.applyWithoutNotice(product.id, transition, activeAlert?.id, time)
        return TxResult.Commit(SaveProductResult.Saved(product.id))
    }

    private fun reject(result: SaveProductResult): TxResult<SaveProductResult> =
        TxResult.Rollback(result)
}
