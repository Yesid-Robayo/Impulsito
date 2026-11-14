package io.github.yesidrobayo.shopledger.domain.usecase.imports

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportApplyResult
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceIndexRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductFormRules
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ReplacementCostRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceRule
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.applyWithoutNotice
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.defaultLot
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.lock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.openingMovement
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.requireId
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock

/**
 * Los repositorios que escribe una importación. Van juntos porque cada tipo usa los suyos, y el
 * caso de uso los necesita todos en la misma transacción.
 */
class ImportWrites(
    val products: ProductRepository,
    val categories: CategoryRepository,
    val suppliers: SupplierRepository,
    val customers: CustomerRepository,
    val units: UnitRepository,
    val priceIndex: PriceIndexRepository,
    val systemRows: SystemRowRepository,
    val priceHistory: PriceHistoryRepository,
    val ledger: StockLedgerRepository,
    val alerts: LowStockAlertRepository,
)

/**
 * Aplica una importación revisada: todo en una sola transacción, o nada.
 *
 * - **Productos nuevos** entran como al crearlos a mano: su primera fila de precios, con origen
 *   importación, y su stock inicial como inventario inicial a su costo, sin tocar las ganancias.
 *   La categoría y el proveedor que no existen se crean.
 * - **Productos que ya existen**, solo si la persona eligió actualizar: cambia su categoría, su
 *   proveedor, su código, su mínimo y su precio, con su fila de historial. Nunca su stock, su costo,
 *   su unidad ni su modo de stock: eso tiene libro y se cambia en su pantalla.
 * - **Clientes, proveedores y meses del índice** se crean o, con actualizar, cambian lo que el
 *   archivo trae; lo que no trae queda como estaba.
 * - **Se vuelve a comprobar** dentro de la transacción lo que la vista previa vio: si otra pantalla
 *   guardó entretanto un nombre o un código, nada se escribe y hay que revisar otra vez.
 * - **La moneda** se bloquea con el primer monto, en la misma transacción. Sin confirmar, nada
 *   cambia.
 */
class ApplyImportUseCase(
    private val transactions: TransactionRunner,
    private val writes: ImportWrites,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        preview: ImportPreview,
        policy: ImportExistingPolicy,
    ): ImportApplyResult = storageSafe(ImportApplyResult::StorageFailed) {
        transactions.write { apply(preview, policy) }
    }

    private suspend fun apply(
        preview: ImportPreview,
        policy: ImportExistingPolicy,
    ): TxResult<ImportApplyResult> {
        val time = calendar.stamp(clock.now()) ?: return outdated()
        val updates = if (policy == ImportExistingPolicy.UPDATE) preview.existing else emptyList()
        val skipped = preview.existingRows - updates.size
        if (preview.ready.isEmpty() && updates.isEmpty()) {
            // Nada que escribir: no se abre un cambio sin respaldar.
            return TxResult.Rollback(ImportApplyResult.Applied(0, 0, skipped))
        }
        val writer = Writer(time)
        val written = when (preview.target) {
            ImportTarget.PRODUCTS -> writer.products(preview.ready, updates)
            ImportTarget.CUSTOMERS -> writer.customers(preview.ready, updates)
            ImportTarget.SUPPLIERS -> writer.suppliers(preview.ready, updates)
            ImportTarget.PRICE_INDEX -> writer.priceIndex(preview.ready, updates)
        }
        return when (written) {
            null -> outdated()

            ImportApplyResult.CurrencyUnconfirmed -> TxResult.Rollback(written)

            else -> TxResult.Commit(
                ImportApplyResult.Applied(preview.ready.size, updates.size, skipped),
            )
        }
    }

    private fun outdated(): TxResult<ImportApplyResult> =
        TxResult.Rollback(ImportApplyResult.Outdated)

    /**
     * Escribe las filas dentro de la transacción. Cada método devuelve `null` si lo que vio la
     * vista previa ya no es cierto, la moneda sin confirmar si hace falta, o [DONE] si escribió.
     */
    private inner class Writer(private val time: RecordTime) {
        private val categoryIds = HashMap<String, Long>()
        private val supplierIds = HashMap<String, Long>()

        suspend fun products(
            ready: List<ImportDraft>,
            updates: List<ImportDraft>,
        ): ImportApplyResult? {
            val news = ready.filterIsInstance<ImportDraft.Product>()
            val changes = updates.filterIsInstance<ImportDraft.Product>()
            val hasMoney = (news + changes).any {
                (it.salePrice?.minor ?: 0L) != 0L || (it.cost?.minor ?: 0L) != 0L
            }
            if (hasMoney && !currencyLock.lock(time.occurredAt)) {
                return ImportApplyResult.CurrencyUnconfirmed
            }
            for (draft in news) if (!create(draft)) return null
            for (draft in changes) if (!update(draft)) return null
            return DONE
        }

        private suspend fun create(draft: ImportDraft.Product): Boolean {
            val w = writes
            if (w.products.findActiveByNormalizedName(draft.normalizedName) != null) return false
            if (draft.barcode?.let { w.products.findActiveByBarcode(it) } != null) return false
            val unitId = draft.unitId ?: w.systemRows.requireId(SystemRow.DEFAULT_UNIT)
            val unit = w.units.findById(unitId)?.takeUnless { it.isArchived } ?: return false
            val mode = draft.stockMode ?: ProductStockMode.TRACKED
            val cost = draft.cost ?: Money.ZERO
            val stock = draft.onHand ?: QuantityMilli.ZERO
            val form = ProductForm(
                name = draft.name,
                baseUnitId = unit.id,
                stockMode = mode,
                salePrice = draft.salePrice ?: Money.ZERO,
                cost = cost,
                minStock = draft.minStock ?: QuantityMilli.ZERO,
                barcode = draft.barcode,
            )
            val valid = ProductFormRules.check(form, stock, unit.allowsFraction)
                as? ProductFormCheck.Valid ?: return false
            val tracked = mode == ProductStockMode.TRACKED
            val details = ProductDetails(
                name = valid.name,
                normalizedName = valid.normalizedName,
                categoryId = draft.category?.let { categoryId(it) },
                defaultSupplierId = draft.supplier?.let { supplierId(it) },
                baseUnitId = unit.id,
                stockMode = mode,
                tracksLots = false,
                fixedUnitCost = if (tracked) Money.ZERO else cost,
                minStock = form.minStock,
                barcode = valid.barcode,
                parentProductId = null,
                variantAxis1 = null,
                variantAxis2 = null,
                variantValue1 = null,
                variantValue2 = null,
                photoMediaId = null,
                taxRateId = null,
                targetMargin = null,
                referenceCost = null,
                referencePrice = null,
                expiryNoticeDays = null,
                usage = draft.usage ?: ProductUsage.SALE,
            )
            val initialCost = if (tracked) cost else Money.ZERO
            val productId = w.products.insert(
                NewProduct(
                    details = details,
                    salePrice = form.salePrice,
                    initialCost = initialCost,
                    replacementCost = Money.ZERO,
                    replacementCostAt = ReplacementCostRules.manualStamp(
                        Money.ZERO,
                        time.occurredAt,
                    ),
                    createdAt = time.occurredAt,
                ),
            )
            appendPrice(productId, oldPrice = null, newPrice = form.salePrice)
            val onHand = if (tracked && stock != QuantityMilli.ZERO) {
                opening(productId, stock, initialCost) ?: return false
            } else {
                QuantityMilli.ZERO
            }
            val transition = LowStockEvaluator.evaluateWithoutMovement(
                stockMode = mode,
                isArchived = false,
                onHand = onHand,
                minStock = form.minStock,
                hasActiveAlert = false,
            )
            w.alerts.applyWithoutNotice(productId, transition, activeAlertId = null, time = time)
            return true
        }

        /** El inventario inicial de un producto nuevo: deja su stock y su costo, o `null`. */
        private suspend fun opening(
            productId: Long,
            quantity: QuantityMilli,
            unitCost: Money,
        ): QuantityMilli? {
            val entry = OpeningBalanceRule.entry(CostState.initial(unitCost), quantity, unitCost)
            val value = (entry as? Checked.Value)?.value ?: return null
            val location = writes.systemRows.requireId(SystemRow.MAIN_LOCATION)
            val lot = writes.ledger.defaultLot(productId, tracksLots = false, at = time.occurredAt)
            writes.ledger.append(
                openingMovement(productId, location, lot, quantity, unitCost, value, time),
            )
            return value.after.onHand
        }

        private suspend fun update(draft: ImportDraft.Product): Boolean {
            val w = writes
            val product =
                w.products.findActiveByNormalizedName(draft.normalizedName) ?: return false
            draft.barcode?.let { code ->
                val owner = w.products.findActiveByBarcode(code)
                if (owner != null && owner.id != product.id) return false
            }
            val current = product.details
            val requested = current.copy(
                categoryId = draft.category?.let { categoryId(it) } ?: current.categoryId,
                defaultSupplierId = draft.supplier?.let { supplierId(it) }
                    ?: current.defaultSupplierId,
                barcode = draft.barcode ?: current.barcode,
                minStock = draft.minStock ?: current.minStock,
                usage = draft.usage ?: current.usage,
            )
            val price = draft.salePrice
            val priceChanged = price != null && price != product.salePrice
            if (requested != current) {
                w.products.updateDetails(product.id, requested, time.occurredAt)
            }
            if (priceChanged) {
                w.products.updateSalePrice(product.id, price, time.occurredAt)
                if (product.priceReviewSince != null) {
                    w.products.setPriceReviewSince(product.id, since = null, time.occurredAt)
                }
                appendPrice(product.id, oldPrice = product.salePrice, newPrice = price)
            }
            if (requested.minStock != current.minStock) watch(product, requested.minStock)
            return true
        }

        /** Revisa la alerta de stock bajo de [product] con su mínimo nuevo, sin avisar. */
        private suspend fun watch(product: Product, minStock: QuantityMilli) {
            val w = writes
            val active = w.alerts.findActiveByProducts(listOf(product.id)).firstOrNull()
            // Un producto con variantes no se vigila: su stock es la suma de sus variantes.
            val parent = CatalogLink.VARIANT_PARENT in w.products.catalogLinks(product.id)
            val transition = LowStockEvaluator.evaluateWithoutMovement(
                stockMode = product.details.stockMode,
                isArchived = parent,
                onHand = product.onHand,
                minStock = minStock,
                hasActiveAlert = active != null,
            )
            w.alerts.applyWithoutNotice(product.id, transition, active?.id, time)
        }

        private suspend fun appendPrice(productId: Long, oldPrice: Money?, newPrice: Money) {
            writes.priceHistory.append(
                NewPriceChange(
                    productId = productId,
                    priceListId = writes.systemRows.requireId(SystemRow.DEFAULT_PRICE_LIST),
                    oldPrice = oldPrice,
                    newPrice = newPrice,
                    source = PriceChangeSource.IMPORT,
                    batchId = null,
                    bulkRule = null,
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                ),
            )
        }

        /** La categoría de [name]: la que ya existe con ese nombre, o una nueva. */
        private suspend fun categoryId(name: String): Long {
            val normalized = TextNormalizer.normalize(name)
            return categoryIds.getOrPut(normalized) {
                writes.categories.findByNormalizedName(normalized)?.id
                    ?: writes.categories.insert(
                        CategoryDetails(name.trim(), normalized, targetMargin = null),
                        time.occurredAt,
                    )
            }
        }

        /** El proveedor vigente de [name], o uno nuevo con solo su nombre. */
        private suspend fun supplierId(name: String): Long {
            val normalized = TextNormalizer.normalize(name)
            return supplierIds.getOrPut(normalized) {
                writes.suppliers.findActiveByNormalizedName(normalized)?.id
                    ?: writes.suppliers.insert(
                        SupplierDetails(name.trim(), normalized, null, null, null, null),
                        time.occurredAt,
                    )
            }
        }

        suspend fun customers(
            ready: List<ImportDraft>,
            updates: List<ImportDraft>,
        ): ImportApplyResult? {
            val w = writes
            val defaultList = w.systemRows.findId(SystemRow.DEFAULT_PRICE_LIST)
            for (draft in ready.filterIsInstance<ImportDraft.Contact>()) {
                // La lista inicial es la de quien no tiene lista propia.
                val listId = draft.priceListId?.takeUnless { it == defaultList }
                w.customers.insert(CustomerDetails(contact(draft), listId), time.occurredAt)
            }
            for (draft in updates.filterIsInstance<ImportDraft.Contact>()) {
                val matches = w.customers.findActiveByNormalizedName(draft.normalizedName)
                val customer = matches.singleOrNull() ?: return null
                val current = customer.details ?: return null
                val listId = when (val chosen = draft.priceListId) {
                    null -> current.priceListId
                    defaultList -> null
                    else -> chosen
                }
                val merged = CustomerDetails(merge(current.contact, draft), listId)
                if (merged != current) w.customers.update(customer.id, merged, time.occurredAt)
            }
            return DONE
        }

        suspend fun suppliers(
            ready: List<ImportDraft>,
            updates: List<ImportDraft>,
        ): ImportApplyResult? {
            val w = writes
            for (draft in ready.filterIsInstance<ImportDraft.Contact>()) {
                val clash = w.suppliers.findActiveByNormalizedName(draft.normalizedName)
                if (clash != null) return null
                w.suppliers.insert(
                    SupplierDetails(
                        draft.name,
                        draft.normalizedName,
                        draft.phone,
                        draft.email,
                        draft.notes,
                        draft.paymentTermsDays,
                    ),
                    time.occurredAt,
                )
            }
            for (draft in updates.filterIsInstance<ImportDraft.Contact>()) {
                val supplier = w.suppliers.findActiveByNormalizedName(draft.normalizedName)
                    ?: return null
                val current = supplier.details
                val merged = current.copy(
                    phone = draft.phone ?: current.phone,
                    email = draft.email ?: current.email,
                    notes = draft.notes ?: current.notes,
                    paymentTermsDays = draft.paymentTermsDays ?: current.paymentTermsDays,
                )
                if (merged != current) w.suppliers.update(supplier.id, merged, time.occurredAt)
            }
            return DONE
        }

        suspend fun priceIndex(
            ready: List<ImportDraft>,
            updates: List<ImportDraft>,
        ): ImportApplyResult? {
            val months = writes.priceIndex.months()
            for (draft in ready.filterIsInstance<ImportDraft.PriceIndex>()) {
                if (draft.month in months) return null
                writes.priceIndex.insert(draft.month, draft.change, time.occurredAt)
            }
            for (draft in updates.filterIsInstance<ImportDraft.PriceIndex>()) {
                if (draft.month !in months) return null
                writes.priceIndex.update(draft.month, draft.change, time.occurredAt)
            }
            return DONE
        }

        private fun contact(draft: ImportDraft.Contact) = CustomerContact(
            name = draft.name,
            normalizedName = draft.normalizedName,
            phone = draft.phone,
            email = draft.email,
            notes = draft.notes,
        )

        private fun merge(current: CustomerContact, draft: ImportDraft.Contact) = current.copy(
            phone = draft.phone ?: current.phone,
            email = draft.email ?: current.email,
            notes = draft.notes ?: current.notes,
        )
    }

    private companion object {
        /** Lo que devuelve cada escritura que terminó: el conteo se arma después. */
        val DONE: ImportApplyResult = ImportApplyResult.Applied(0, 0, 0)
    }
}
