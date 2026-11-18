package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductComponentRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductionBatchRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LeafStockPlan
import io.github.yesidrobayo.shopledger.domain.rule.catalog.LotExit
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionPlan
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotCodeCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.lock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.requireId
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockChangeRecorder
import kotlin.time.Instant

/**
 * Registra una producción en una sola transacción: la cabecera, un renglón por insumo, una salida
 * del libro por cada insumo con stock y la entrada de lo producido. Si algo falla o una regla
 * rechaza, no queda nada: una producción a medias dejaría insumos fuera sin su producto, o al
 * revés.
 *
 * - **Cada insumo con stock sale con `costoDeSalida`**, como la hoja de un combo vendido, y deja
 *   su costo promedio como estaba. Uno sin stock suma su costo fijo y no mueve nada.
 * - **Lo producido entra con `entrada` por la suma de los insumos**, así que su costo promedio
 * sale   de lo que de verdad se consumió.
 * - **Faltar insumos no bloquea:** el stock de un insumo puede quedar negativo, como al vender, y
 *   la pantalla solo lo avisa.
 * - **La fecha es la de hoy:** producir no se registra atrasado.
 * - Un insumo archivado se consume igual: archivar no cambia la receta. Lo producido, en cambio,
 *   debe estar vigente.
 *
 * Todo sale y entra en la ubicación principal.
 *
 * - **Un insumo con lotes sale por vencimiento:** primero el lote elegido, si lo hay, luego el que
 *   vence antes, con una fila por lote valorada en orden, como la hoja de un combo vendido. Lo que
 *   falte sale del lote sin asignar, que puede quedar negativo: producir nunca se bloquea.
 * - **Lo producido con lotes entra a un lote:** el del código que escribió la persona, que se crea
 *   con su vencimiento si el producto no lo tiene, o el lote sin asignar si no trae código.
 *
 * Con [lots] lee los lotes; sin él, un producto con lotes se rechaza con su motivo, como insumo o
 * como producto. En la misma transacción evalúa la alerta de stock bajo de cada producto que
 * mueve.
 */
class RegisterProductionUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val recipes: ProductComponentRepository,
    private val batches: ProductionBatchRepository,
    private val systemRows: SystemRowRepository,
    ledger: StockLedgerRepository,
    private val alerts: LowStockAlertRepository,
    private val calendar: BusinessCalendarSource,
    private val currencyLock: CurrencyLock,
    private val clock: Clock,
    private val lots: StockLotRepository? = null,
) {
    private val ledger = StockChangeRecorder(ledger)

    suspend operator fun invoke(request: ProductionRequest): RegisterProductionResult =
        storageSafe(RegisterProductionResult::StorageFailed) {
            transactions.write { register(request) }
        }

    /**
     * Registra dentro de una transacción ya abierta, para quien junta varias producciones o una
     * receta con la producción en una sola escritura.
     */
    internal suspend fun register(request: ProductionRequest): TxResult<RegisterProductionResult> {
        ledger.start()
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if ((note?.length ?: 0) > MAX_NOTE_LENGTH) {
            return reject(RegisterProductionResult.NoteTooLong)
        }
        val product = products.findById(request.productId)
            ?: return reject(RegisterProductionResult.NotFound)
        productRejection(product, request)?.let { return reject(it) }
        val location = systemRows.requireId(SystemRow.MAIN_LOCATION)
        val read = when (val result = readInputs(request, location)) {
            is InputsRead.Read -> result
            is InputsRead.Rejected -> return reject(result.reason)
        }
        val before = CostState(product.onHand, product.averageCost, product.stockValue)
        val plan = when (
            val result = ProductionRules.plan(
                before,
                request.quantity,
                request.inputs,
                read.products,
                read.lots,
            )
        ) {
            is Checked.Value -> result.value

            // Con los topes de cada insumo y del total ya revisados, no cabe solo por encima de
            // todo tope.
            Checked.Overflow ->
                return reject(RegisterProductionResult.AboveLimit(Limit.STOCK_VALUE))

            is Checked.AboveLimit ->
                return reject(RegisterProductionResult.AboveLimit(result.limit))
        }
        val now = clock.now()
        val time = calendar.stamp(now) ?: return reject(RegisterProductionResult.UnknownTimeZone)
        // Una producción sin costo no es dinero: no fija ninguna moneda.
        if (plan.totalCost != Money.ZERO && !currencyLock.lock(now)) {
            return reject(RegisterProductionResult.CurrencyUnconfirmed)
        }
        val batchId = batches.insert(
            NewProductionBatch(
                productId = product.id,
                locationId = location,
                quantity = request.quantity,
                totalCost = plan.totalCost,
                note = note,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        writeInputs(batchId, plan, location, time)
        writeOutput(batchId, product, request, plan, location, time)
        ledger.applyAlerts(alerts, products, time)
        return TxResult.Commit(RegisterProductionResult.Registered(batchId, plan.totalCost))
    }

    /** Por qué no se puede producir [product] como lo pide [request], o `null` si se puede. */
    private suspend fun productRejection(
        product: Product,
        request: ProductionRequest,
    ): RegisterProductionResult? {
        if (product.isArchived) return RegisterProductionResult.ProductArchived
        if (product.details.stockMode != ProductStockMode.TRACKED) {
            return RegisterProductionResult.NotStocked
        }
        if (product.details.tracksLots && lots == null) {
            return RegisterProductionResult.LotsNotSupported
        }
        lotRejection(product, request.lot)?.let { return it }
        // La unidad base de un producto siempre existe: su llave no deja borrarla.
        val unit = checkNotNull(units.findById(product.details.baseUnitId)) {
            "Falta la unidad base"
        }
        val check = UnitQuantityRule.check(request.quantity, unit.allowsFraction)
        if (check != UnitQuantityCheck.Valid) {
            return RegisterProductionResult.InvalidQuantity(check)
        }
        if (!Limit.LINE_QUANTITY_MILLI.allows(request.quantity.milli)) {
            return RegisterProductionResult.AboveLimit(Limit.LINE_QUANTITY_MILLI)
        }
        if (recipes.findByParent(product.id).isEmpty()) return RegisterProductionResult.NoRecipe
        return when {
            request.inputs.isEmpty() -> RegisterProductionResult.NoInputs

            !Limit.DOCUMENT_LINES.allows(request.inputs.size.toLong()) ->
                RegisterProductionResult.TooManyInputs

            else -> null
        }
    }

    /**
     * Por qué no sirve el lote [lot] de lo producido, o `null` si sirve: solo un producto con
     * lotes lo lleva, y su código tiene un largo máximo. Un código en blanco deja lo producido en
     * el lote sin asignar.
     */
    private fun lotRejection(product: Product, lot: ProductionLot?): RegisterProductionResult? {
        if (lot == null) return null
        if (!product.details.tracksLots) return RegisterProductionResult.LotNotTracked
        return when (val check = LotRules.checkCode(lot.code)) {
            is LotCodeCheck.TooLong -> RegisterProductionResult.LotCodeTooLong(check.max)
            is LotCodeCheck.Valid, LotCodeCheck.Blank -> null
        }
    }

    /**
     * Lee cada insumo dentro de la transacción y revisa que se pueda consumir. La cantidad admite
     * milésimas en cualquier unidad, como la receta: una tanda puede llevar media unidad. De un
     * insumo con lotes lee lo que tiene cada lote en [location], de donde sale.
     */
    private suspend fun readInputs(request: ProductionRequest, location: Long): InputsRead {
        val found = products.findByIds(request.inputs.map { it.productId }.toSet())
            .associateBy { it.id }
        val seen = HashSet<Long>()
        val stocks = HashMap<Long, List<LotStock>>()
        request.inputs.forEachIndexed { index, input ->
            val component = found[input.productId]
            val quantity = input.quantity.milli
            val reason = when {
                component == null -> ProductionInputRejection.NOT_FOUND

                input.productId == request.productId -> ProductionInputRejection.SAME_PRODUCT

                !seen.add(input.productId) -> ProductionInputRejection.REPEATED

                component.details.stockMode == ProductStockMode.COMPONENTS ->
                    ProductionInputRejection.IS_COMBO

                component.details.tracksLots && lots == null ->
                    ProductionInputRejection.LOTS_NOT_SUPPORTED

                quantity <= 0L || !Limit.LINE_QUANTITY_MILLI.allows(quantity) ->
                    ProductionInputRejection.INVALID_QUANTITY

                else -> chosenLotRejection(component, input.lotId, location, stocks)
            }
            if (reason != null) {
                return InputsRead.Rejected(RegisterProductionResult.InputRejected(index, reason))
            }
        }
        return InputsRead.Read(found, stocks)
    }

    /**
     * Lee en [stocks] lo que tiene cada lote de [component] en [location], si se controla por
     * lotes, y revisa el lote elegido [lotId]: tiene que ser de este insumo. `null` si sirve.
     */
    private suspend fun chosenLotRejection(
        component: Product,
        lotId: Long?,
        location: Long,
        stocks: MutableMap<Long, List<LotStock>>,
    ): ProductionInputRejection? {
        val read = if (component.details.tracksLots) {
            val repository = requireNotNull(lots) { "Producir con lotes pide los lotes" }
            repository.findStock(component.id, location).also { stocks[component.id] = it }
        } else {
            null
        }
        return if (lotId != null && read.orEmpty().none { it.lotId == lotId }) {
            ProductionInputRejection.LOT_NOT_FOUND
        } else {
            null
        }
    }

    /**
     * Guarda cada insumo y, si lleva stock, su salida del libro ligada a la producción: una fila,
     * o una por lote en un insumo con lotes. La parte sin lote va al lote sin asignar.
     */
    private suspend fun writeInputs(
        batchId: Long,
        plan: ProductionPlan,
        location: Long,
        time: RecordTime,
    ) {
        for (input in plan.inputs) {
            batches.insertLine(
                batchId,
                NewProductionBatchLine(
                    componentProductId = input.productId,
                    quantity = input.quantity,
                    unitCost = input.unitCost,
                    lineCost = input.lineCost,
                ),
            )
            when (input) {
                is LeafStockPlan.Tracked -> appendOutflow(batchId, input, location, time)
                is LeafStockPlan.Untracked -> Unit
            }
        }
    }

    /** Las filas de salida de [input]: una sola sin lotes, o una por lote, en orden. */
    private suspend fun appendOutflow(
        batchId: Long,
        input: LeafStockPlan.Tracked,
        location: Long,
        time: RecordTime,
    ) {
        val exit = input.exit
        val parts = exit.lots.ifEmpty {
            listOf(LotExit(lotId = null, exit.quantity, exit.cost, exit.stateAfter))
        }
        for (part in parts) {
            val lotId = if (exit.lots.isEmpty()) {
                null
            } else {
                part.lotId ?: ledger.unassignedLot(input.productId, time.occurredAt)
            }
            ledger.append(
                NewStockMovement(
                    movement = StockMovementDraft(
                        key = StockKey(input.productId, location, lotId),
                        type = StockMovementType.PRODUCTION_OUT,
                        quantity = -part.quantity,
                        reversesMovementId = null,
                    ),
                    reason = null,
                    unitCost = exit.unitCost,
                    totalCost = -part.cost,
                    balanceAfter = part.stateAfter.onHand,
                    averageCostAfter = part.stateAfter.averageCost,
                    stockValueAfter = part.stateAfter.stockValue,
                    source = StockMovementSource(productionBatchId = batchId),
                    note = null,
                    occurredAt = time.occurredAt,
                    businessDate = time.businessDate,
                    createdAt = time.occurredAt,
                ),
            )
        }
    }

    /** La entrada de lo producido al libro, por la suma de los insumos, en su lote si lo lleva. */
    private suspend fun writeOutput(
        batchId: Long,
        product: Product,
        request: ProductionRequest,
        plan: ProductionPlan,
        location: Long,
        time: RecordTime,
    ) {
        val lotId = outputLotOf(product, request.lot, time.occurredAt)
        ledger.append(
            NewStockMovement(
                movement = StockMovementDraft(
                    key = StockKey(product.id, location, lotId),
                    type = StockMovementType.PRODUCTION_IN,
                    quantity = request.quantity,
                    reversesMovementId = null,
                ),
                reason = null,
                unitCost = plan.unitCost,
                totalCost = plan.totalCost,
                balanceAfter = plan.outputAfter.onHand,
                averageCostAfter = plan.outputAfter.averageCost,
                stockValueAfter = plan.outputAfter.stockValue,
                source = StockMovementSource(productionBatchId = batchId),
                note = null,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = time.occurredAt,
            ),
        )
    }

    /**
     * El lote al que entra lo producido: ninguno sin lotes; el del código escrito, que se crea
     * aquí con su vencimiento si el producto no lo tiene; o el lote sin asignar sin código.
     */
    private suspend fun outputLotOf(product: Product, lot: ProductionLot?, at: Instant): Long? {
        if (!product.details.tracksLots) return null
        val code = (lot?.let { LotRules.checkCode(it.code) } as? LotCodeCheck.Valid)?.code
            ?: return ledger.unassignedLot(product.id, at)
        val repository = requireNotNull(lots) { "Producir con lotes pide los lotes" }
        return repository.findByCode(product.id, code)?.id
            ?: repository.insert(
                NewStockLot(
                    productId = product.id,
                    code = code,
                    expiryDate = lot?.expiry,
                    purchaseLineId = null,
                    createdAt = at,
                ),
            )
    }

    private fun reject(result: RegisterProductionResult): TxResult<RegisterProductionResult> =
        TxResult.Rollback(result)

    /**
     * Los insumos leídos, por id, con lo que tiene cada lote de los que llevan lotes, o el primer
     * rechazo.
     */
    private sealed interface InputsRead {
        class Read(val products: Map<Long, Product>, val lots: Map<Long, List<LotStock>>) :
            InputsRead

        class Rejected(val reason: RegisterProductionResult) : InputsRead
    }

    companion object {
        /** Largo máximo de la nota de una producción, el mismo que admite la base. */
        const val MAX_NOTE_LENGTH: Int = 200
    }
}
