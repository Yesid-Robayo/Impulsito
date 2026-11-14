package io.github.yesidrobayo.shopledger.domain.model.imports

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó aplicar una importación, que va entera o no va. */
sealed interface ImportApplyResult {
    /**
     * Se escribió todo en una transacción: [created] filas nuevas, [updated] existentes cambiadas
     * y [skipped] omitidas porque ya existían.
     */
    data class Applied(val created: Int, val updated: Int, val skipped: Int) : ImportApplyResult

    /** Hay montos y la moneda del negocio sigue sin confirmar: nada cambió. */
    data object CurrencyUnconfirmed : ImportApplyResult

    /**
     * Entre revisar y aplicar cambió lo que ya existe, como un nombre o un código que otra
     * pantalla acaba de guardar: nada cambió, y hay que revisar las filas otra vez.
     */
    data object Outdated : ImportApplyResult

    /** La base no pudo escribir, por [failure]: nada cambió. */
    data class StorageFailed(val failure: StorageFailure) : ImportApplyResult {
        /** Si faltó espacio: la pantalla lo dice aparte, porque la persona puede liberarlo. */
        val outOfSpace: Boolean get() = failure == StorageFailure.DISK_FULL
    }
}
