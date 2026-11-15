package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.LimitNoticeMark

/**
 * Lanza el aviso del límite de ingresos como notificación local, sin servidor. Lo implementa
 * `:app`. El aviso nunca lleva montos: a lo sumo, el porcentaje del límite que se usó.
 */
fun interface RevenueLimitNotifier {
    /**
     * Muestra el aviso de [level], con [used] si se conoce. Devuelve `false` si el sistema no deja
     * avisar: el permiso, las notificaciones de la app o su canal están apagados.
     */
    suspend fun show(level: LimitLevel, used: BasisPoints?): Boolean
}

/**
 * Dónde se guarda el último aviso del límite que salió. Es de este teléfono, no un dato del
 * negocio: no viaja en una copia. Si se pierde, el aviso puede salir otra vez, que es el lado
 * seguro.
 */
interface LimitNoticeStore {
    /** El último aviso guardado, o `null` si no hay o no se entiende. */
    suspend fun read(): LimitNoticeMark?

    suspend fun write(mark: LimitNoticeMark)
}
