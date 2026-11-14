package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.stock.DatedLotRow
import io.github.yesidrobayo.shopledger.data.local.stock.LotStockRow
import io.github.yesidrobayo.shopledger.data.local.stock.StockLotEntity
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.DatedLotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.LotHolding
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.port.StockLotRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los lotes sobre `stock_lots`, y su saldo por ubicación sobre `stock_balances`.
 *
 * El lote sin asignar no se crea aquí: lo crea el libro de stock cuando una fila lo necesita. Un
 * código repetido en el mismo producto lo rechaza el índice único, y sale como regla rota.
 *
 * La marca del aviso de vencimiento no es un dato del negocio: se escribe sola, fuera de la
 * transacción de un caso de uso, con el cerrojo de la restauración [restoreLock]. Las pruebas que
 * no marcan avisos pueden omitirlo.
 */
internal class RoomStockLotRepository(
    database: AppDatabase,
    restoreLock: RestoreLock = RestoreLock { false },
) : StockLotRepository {
    private val lots = database.stockLotDao()
    private val writes = RepositoryWrites(database)
    private val standalone = StandaloneWrites(restoreLock)

    // Room vuelve a emitir con cualquier cambio de la tabla: una lista igual no se repite.
    override fun observeByProduct(productId: Long): Flow<List<StockLot>> = lots
        .observeByProduct(productId)
        .map { rows -> rows.map(::toLot) }
        .distinctUntilChanged()

    override fun observeAll(): Flow<List<StockLot>> =
        lots.observeAll().map { rows -> rows.map(::toLot) }.distinctUntilChanged()

    override suspend fun findById(id: Long): StockLot? = lots.findById(id)?.let(::toLot)

    override suspend fun findByCode(productId: Long, code: String): StockLot? =
        lots.findByCode(productId, code)?.let(::toLot)

    override suspend fun findStock(productId: Long, locationId: Long): List<LotStock> =
        lots.findStock(productId, locationId).map(::toStock)

    override suspend fun insert(lot: NewStockLot): Long = writes.inTransaction {
        lots.insert(
            StockLotEntity(
                productId = lot.productId,
                lotCode = lot.code,
                isUnassigned = 0L,
                expiryDate = lot.expiryDate?.toStored(),
                purchaseLineId = lot.purchaseLineId,
                expiryNotifiedAt = null,
                createdAt = lot.createdAt.toStoredMillis(),
                closedAt = null,
            ),
        )
    }

    override suspend fun attachPurchaseLine(lotId: Long, purchaseLineId: Long) {
        writes.inTransaction { requireOneRow(lots.setPurchaseLine(lotId, purchaseLineId)) }
    }

    override fun observeDated(): Flow<List<DatedLotStock>> =
        lots.observeDated().map { rows -> rows.map(::toDated) }.distinctUntilChanged()

    override suspend fun markExpiryNotified(lotIds: List<Long>, notifiedAt: Instant) {
        if (lotIds.isEmpty()) return
        val stored = notifiedAt.toStoredMillis()
        // Una sentencia por lote: si una falla, las anteriores quedan y ese lote se avisa otra vez.
        for (id in lotIds) standalone.write { requireOneRow(lots.setExpiryNotifiedAt(id, stored)) }
    }

    override fun observeHoldings(): Flow<List<LotHolding>> = lots.observeHoldings()
        .map { rows ->
            rows.map { LotHolding(it.lotId, it.locationId, QuantityMilli(it.onHandMilli)) }
        }
        .distinctUntilChanged()

    override suspend fun correct(lotId: Long, code: String, expiryDate: BusinessDate?) {
        writes.inTransaction {
            val before = checkNotNull(lots.findById(lotId)) { "El lote no existe" }
            val stored = expiryDate?.toStored()
            requireOneRow(lots.updateCodeAndExpiry(lotId, code, stored))
            // Con otra fecha, el aviso vuelve a quedar pendiente: el anterior era por otro día.
            if (before.expiryDate != stored) requireOneRow(lots.setExpiryNotifiedAt(lotId, null))
        }
    }

    private fun toDated(row: DatedLotRow): DatedLotStock = DatedLotStock(
        lotId = row.id,
        productId = row.productId,
        productName = row.productName,
        code = row.lotCode,
        expiryDate = storedDate(row.expiryDate),
        onHand = QuantityMilli(row.onHandMilli),
        baseUnitId = row.baseUnitId,
        // La guarda deja de 0 a 36.500 días: cabe en un Int.
        noticeDays = row.expiryNoticeDays?.toInt(),
        expiryNotifiedAt = row.expiryNotifiedAt?.toInstant(),
    )

    private fun toLot(row: StockLotEntity): StockLot = StockLot(
        id = row.id,
        productId = row.productId,
        code = row.lotCode,
        isUnassigned = row.isUnassigned.toFlag(),
        expiryDate = row.expiryDate?.let(::storedDate),
        purchaseLineId = row.purchaseLineId,
        expiryNotifiedAt = row.expiryNotifiedAt?.toInstant(),
        createdAt = row.createdAt.toInstant(),
        closedAt = row.closedAt?.toInstant(),
    )

    private fun toStock(row: LotStockRow): LotStock = LotStock(
        lotId = row.id,
        code = row.lotCode,
        expiryDate = row.expiryDate?.let(::storedDate),
        isUnassigned = row.isUnassigned.toFlag(),
        onHand = QuantityMilli(row.onHandMilli),
    )
}
