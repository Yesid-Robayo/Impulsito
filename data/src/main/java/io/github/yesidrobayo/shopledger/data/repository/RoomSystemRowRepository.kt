package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository

/**
 * Las filas iniciales por su clave estable, cada una en su tabla. Las crea la base al nacer, y sus
 * guardas impiden archivarlas o borrarlas.
 */
internal class RoomSystemRowRepository(database: AppDatabase) : SystemRowRepository {
    private val units = database.unitDao()
    private val locations = database.locationDao()
    private val priceLists = database.priceListDao()

    override suspend fun findId(row: SystemRow): Long? = when (row) {
        SystemRow.DEFAULT_UNIT -> units.findBySystemKey(row.systemKey)?.id
        SystemRow.MAIN_LOCATION -> locations.findBySystemKey(row.systemKey)?.id
        SystemRow.DEFAULT_PRICE_LIST -> priceLists.findBySystemKey(row.systemKey)?.id
    }
}
