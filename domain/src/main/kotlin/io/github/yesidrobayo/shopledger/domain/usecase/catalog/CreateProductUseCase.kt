package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogReference
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
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
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ReplacementCostRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceEntry
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceRule
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/**
 * Crea un producto, con su stock inicial si la persona escribió uno, en una sola transacción.
 *
 * Escribe el producto, la primera fila de su historial de precios y, con stock inicial, la primera
 * fila de su libro, que no es compra ni gasto: no toca las ganancias. Si el producto nace bajo de
 * stock, crea su alerta callada, porque la persona lo está viendo, y así la primera venta no avisa
 * por un cruce que no hubo.
 *
 * Un nombre repetido entre los productos sin archivar se rechaza nombrando al que ya lo tiene,
 * comparado sin mayúsculas ni tildes. Con precio o costo, la moneda del negocio debe estar
 * confirmada, y si es el primer monto que se guarda, queda bloqueada en la misma transacción. Con
 * foto, anota su imagen en la misma transacción; si ya estaba anotada, la reusa.
 */
class CreateProductUseCase(
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
    private val media: MediaAssetRepository,
) {
    private val references = ProductReferences(categories, suppliers, units, systemRows)

    /** Crea el producto de [form] con [initialStock] milésimas de stock inicial, cero sin él. */
    suspend operator fun invoke(
        form: ProductForm,
        initialStock: QuantityMilli = QuantityMilli.ZERO,
    ): SaveProductResult = storageSafe(SaveProductResult::StorageFailed) {
        transactions.write { create(form, initialStock) }
    }

    private suspend fun create(
        form: ProductForm,
        initialStock: QuantityMilli,
    ): TxResult<SaveProductResult> {
        val unit = references.unitOf(form, current = null)
            ?: return reject(SaveProductResult.MissingReference(CatalogReference.UNIT))
        val valid = when (
            val check = ProductFormRules.check(
                form,
                initialStock,
                unit.allowsFraction,
            )
        ) {
            is ProductFormCheck.Invalid -> return reject(SaveProductResult.Invalid(check.issues))
            is ProductFormCheck.Valid -> check
        }
        references.missingIn(form, current = null)?.let {
            return reject(SaveProductResult.MissingReference(it))
        }
        products.findActiveByNormalizedName(valid.normalizedName)?.let {
            return reject(SaveProductResult.DuplicateName(it.details.name))
        }
        valid.barcode?.let { products.findActiveByBarcode(it) }?.let {
            return reject(SaveProductResult.DuplicateBarcode(it.details.name))
        }
        val time = calendar.stamp(clock.now()) ?: return reject(SaveProductResult.UnknownTimeZone)
        val tracked = form.stockMode == ProductStockMode.TRACKED
        val initialCost = if (tracked) form.cost else Money.ZERO
        // Un producto con precio y costo en cero no lleva dinero: no fija ninguna moneda.
        val replacement = form.replacementCost ?: Money.ZERO
        val hasMoney =
            form.salePrice != Money.ZERO || form.cost != Money.ZERO || replacement != Money.ZERO
        if (hasMoney && !currencyLock.lock(time.occurredAt)) {
            return reject(SaveProductResult.CurrencyUnconfirmed)
        }
        val opening = if (initialStock == QuantityMilli.ZERO) {
            null
        } else {
            when (
                val entry = OpeningBalanceRule.entry(
                    CostState.initial(initialCost),
                    initialStock,
                    initialCost,
                )
            ) {
                is Checked.Value -> entry.value

                // Con los topes del formulario no se desborda; si pasara, es por encima de todo tope.
                Checked.Overflow -> return reject(
                    SaveProductResult.AboveLimit(Limit.DOCUMENT_TOTAL),
                )

                is Checked.AboveLimit -> return reject(SaveProductResult.AboveLimit(entry.limit))
            }
        }
        val details = ProductDetails(
            name = valid.name,
            normalizedName = valid.normalizedName,
            categoryId = form.categoryId,
            defaultSupplierId = form.defaultSupplierId,
            baseUnitId = unit.id,
            stockMode = form.stockMode,
            tracksLots = form.tracksLots == true && tracked,
            fixedUnitCost = if (form.stockMode ==
                ProductStockMode.UNTRACKED
            ) {
                form.cost
            } else {
                Money.ZERO
            },
            minStock = form.minStock,
            barcode = valid.barcode,
            parentProductId = null,
            variantAxis1 = null,
            variantAxis2 = null,
            variantValue1 = null,
            variantValue2 = null,
            photoMediaId = photoIdAfter(media, form.photo, current = null, time.occurredAt),
            taxRateId = null,
            targetMargin = null,
            referenceCost = null,
            referencePrice = form.referencePrice?.takeIf { it > Money.ZERO },
            expiryNoticeDays = null,
        )
        val productId = products.insert(
            NewProduct(
                details = details,
                salePrice = form.salePrice,
                initialCost = initialCost,
                replacementCost = replacement,
                replacementCostAt = ReplacementCostRules.manualStamp(replacement, time.occurredAt),
                createdAt = time.occurredAt,
            ),
        )
        priceHistory.append(
            NewPriceChange(
                productId = productId,
                priceListId = systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST),
                oldPrice = null,
                newPrice = form.salePrice,
                source = PriceChangeSource.INITIAL,
                batchId = null,
                bulkRule = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
            ),
        )
        val onHand = opening?.let {
            appendOpening(productId, details.tracksLots, initialStock, initialCost, it, time)
        } ?: QuantityMilli.ZERO
        val transition = LowStockEvaluator.evaluateWithoutMovement(
            stockMode = form.stockMode,
            isArchived = false,
            onHand = onHand,
            minStock = form.minStock,
            hasActiveAlert = false,
        )
        alerts.applyWithoutNotice(productId, transition, activeAlertId = null, time = time)
        return TxResult.Commit(SaveProductResult.Saved(productId))
    }

    private suspend fun appendOpening(
        productId: Long,
        tracksLots: Boolean,
        quantity: QuantityMilli,
        unitCost: Money,
        entry: OpeningBalanceEntry,
        time: RecordTime,
    ): QuantityMilli {
        val location = systemRows.requireId(SystemRow.MAIN_LOCATION)
        // Sin lote elegido, el inventario inicial de un producto con lotes va al lote sin asignar.
        val lot = ledger.defaultLot(productId, tracksLots, time.occurredAt)
        ledger.append(openingMovement(productId, location, lot, quantity, unitCost, entry, time))
        return entry.after.onHand
    }

    private fun reject(result: SaveProductResult): TxResult<SaveProductResult> =
        TxResult.Rollback(result)
}
