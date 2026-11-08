package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import kotlinx.coroutines.flow.Flow

/**
 * Las unidades en que se cuenta el stock y se registran las líneas.
 *
 * Aquí solo se leen: crearlas y archivarlas es de la pantalla de unidades. Las lecturas que devuelven
 * [Flow] vuelven a emitir cada vez que se confirma una transacción que cambia lo leído.
 */
interface UnitRepository {
    /** Las vigentes, en el orden en que se crearon: las iniciales primero. */
    fun observeActive(): Flow<List<MeasureUnit>>

    suspend fun findById(id: Long): MeasureUnit?
}
