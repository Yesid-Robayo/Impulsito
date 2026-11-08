package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow

/**
 * Encuentra las filas iniciales por su clave estable.
 *
 * Un caso de uso no supone el id de una fila inicial: una base restaurada de otro teléfono la tiene
 * igual, pero el id es un detalle de la base y la clave es lo que no cambia nunca.
 */
interface SystemRowRepository {
    /** El id de [row], o `null` si la base no la tiene, algo que solo pasa con una base dañada. */
    suspend fun findId(row: SystemRow): Long?
}
