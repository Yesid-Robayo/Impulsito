package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineComponentEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLineComponent
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineComponent
import io.github.yesidrobayo.shopledger.domain.port.SaleLineComponentRepository

/**
 * Las hojas de las líneas de combo sobre `sale_line_components`: agregar y leer. La guarda de la
 * tabla rechaza editarlas o borrarlas, y una hoja que sea otro combo o cuyo `tracked` no coincida
 * con el modo de su producto.
 */
internal class RoomSaleLineComponentRepository(database: AppDatabase) :
    SaleLineComponentRepository {
    private val components = database.saleLineComponentDao()
    private val writes = RepositoryWrites(database)

    override suspend fun findBySaleLine(saleLineId: Long): List<SaleLineComponent> =
        components.findBySaleLine(saleLineId).map(::toComponent)

    override suspend fun findBySale(saleId: Long): List<SaleLineComponent> =
        components.findBySale(saleId).map(::toComponent)

    override suspend fun insert(saleLineId: Long, component: NewSaleLineComponent): Long =
        writes.inTransaction {
            components.insert(
                SaleLineComponentEntity(
                    saleLineId = saleLineId,
                    componentProductId = component.componentProductId,
                    quantityMilli = component.quantity.milli,
                    tracked = component.tracked.toStoredFlag(),
                    unitCost = component.unitCost.minor,
                    lineCost = component.lineCost.minor,
                ),
            )
        }

    private fun toComponent(row: SaleLineComponentEntity): SaleLineComponent = SaleLineComponent(
        id = row.id,
        saleLineId = row.saleLineId,
        content = NewSaleLineComponent(
            componentProductId = row.componentProductId,
            quantity = QuantityMilli(row.quantityMilli),
            tracked = row.tracked.toFlag(),
            unitCost = Money(row.unitCost),
            lineCost = Money(row.lineCost),
        ),
    )
}
