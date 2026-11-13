package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListInputError
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó crear o editar una lista de precios. Salvo [Saved], no se escribió nada. */
sealed interface SavePriceListResult {
    /** Quedó guardada, o ya estaba exactamente así. [id] es el de la lista. */
    data class Saved(val id: Long) : SavePriceListResult

    /** Cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<PriceListInputError>) : SavePriceListResult

    /** Otra lista vigente ya se llama así, comparado sin mayúsculas ni tildes. */
    data object DuplicateName : SavePriceListResult

    /** La lista ya no existe, o se archivó mientras se editaba. */
    data object NotFound : SavePriceListResult

    /** Es la lista inicial, la del precio de venta: no se renombra ni cambia de tipo. */
    data object Protected : SavePriceListResult

    /** La lista base no existe o está archivada: no se ofrece para una relativa nueva. */
    data object BaseUnavailable : SavePriceListResult

    /**
     * La base elegida parte, directa o indirectamente, de esta misma lista: sus precios se
     * calcularían desde sí mismos.
     */
    data object CreatesCycle : SavePriceListResult

    /**
     * La lista es fija y tiene precios propios: pasarla a relativa los dejaría sin uso. Hay que
     * quitarlos antes, o crear otra lista.
     */
    data object HasOwnPrices : SavePriceListResult

    /** La base no pudo escribir. No quedó nada a medias, y lo escrito se conserva. */
    data class StorageFailed(val failure: StorageFailure) : SavePriceListResult
}

/** Cómo terminó archivar, reactivar o eliminar una lista de precios. */
sealed interface PriceListLifecycleResult {
    /** Se hizo lo pedido. */
    data object Done : PriceListLifecycleResult

    /** La lista ya no existe. */
    data object NotFound : PriceListLifecycleResult

    /** Ya estaba como se pedía: archivada al archivar, o vigente al reactivar. Nada cambió. */
    data object Unchanged : PriceListLifecycleResult

    /** Es la lista inicial, la del precio de venta: no se archiva ni se elimina. */
    data object Protected : PriceListLifecycleResult

    /** Tiene uso: no se elimina, solo se archiva. */
    data object InUse : PriceListLifecycleResult

    /** Una lista vigente ya usa su nombre: hay que cambiar uno de los dos antes de reactivarla. */
    data object NameTaken : PriceListLifecycleResult

    data class StorageFailed(val failure: StorageFailure) : PriceListLifecycleResult
}

/** Cómo terminó cambiar el precio de un producto en una lista. Salvo [Changed], nada cambió. */
sealed interface SetListPriceResult {
    /**
     * El precio quedó guardado. [historyId] es la fila de su historial, o `null` si el precio que
     * se ve no cambió: quitar un precio propio igual al de venta no es un cambio de precio.
     */
    data class Changed(val historyId: Long?) : SetListPriceResult

    /** El precio pedido ya es el de ahora: no se escribe nada. */
    data object Unchanged : SetListPriceResult

    /** El precio es negativo o pasa del tope de dinero por unidad. */
    data object PriceOutOfRange : SetListPriceResult

    /** La lista inicial siempre tiene precio: el de venta. No se quita, se cambia. */
    data object DefaultPriceRequired : SetListPriceResult

    /**
     * Un precio propio en cero no es un precio: para que el producto vuelva al precio de venta, se
     * quita el propio. En la inicial, cero sí vale: es un producto sin precio.
     */
    data object ZeroOwnPrice : SetListPriceResult

    data object ListNotFound : SetListPriceResult

    /** Archivada: hay que reactivarla antes de cambiar sus precios. */
    data object ListArchived : SetListPriceResult

    /** La lista es relativa: sus precios se calculan desde otra y no se escriben. */
    data object ListIsCalculated : SetListPriceResult

    data object ProductNotFound : SetListPriceResult

    /** Archivado: hay que reactivarlo antes de cambiar sus precios. */
    data object ProductArchived : SetListPriceResult

    /** La zona del negocio no se reconoce: la fila del historial no tendría fecha. */
    data object UnknownTimeZone : SetListPriceResult

    /** La moneda del negocio no está confirmada, y un precio es dinero. */
    data object CurrencyUnconfirmed : SetListPriceResult

    data class StorageFailed(val failure: StorageFailure) : SetListPriceResult
}
