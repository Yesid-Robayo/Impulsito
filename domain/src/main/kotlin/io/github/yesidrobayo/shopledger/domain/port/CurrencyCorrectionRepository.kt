package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import kotlin.time.Instant

/**
 * Lo que la corrección de la moneda del negocio lee y escribe en la base, además de los ajustes.
 *
 * Corregir la moneda solo cambia su etiqueta: ningún monto guardado cambia. Lo que lee sirve para
 * no dejar nada incoherente, como una cuenta propia en otra moneda que de pronto sería la del
 * negocio.
 */
interface CurrencyCorrectionRepository {
    /** Cuántas cuentas de dinero, vigentes o archivadas, llevan [code] como su moneda. */
    suspend fun accountsIn(code: CurrencyCode): Int

    /**
     * Si algún registro guardó [code] como otra moneda, distinta de la del negocio: un cobro en
     * otra moneda, una compra en otra moneda o una cotización manual.
     */
    suspend fun usedAsOtherCurrency(code: CurrencyCode): Boolean

    /**
     * Cambia la moneda del negocio de [from] a [to], con los mismos [decimals], y lleva a [to] las
     * cuentas de dinero que estaban en [from] con esos decimales. No toca ningún monto, ni el
     * instante del bloqueo, ni el símbolo propio. Solo dentro de [TransactionRunner.write]: es un
     * dato del negocio y cuenta como cambio.
     *
     * @return cuántas cuentas cambiaron de etiqueta.
     */
    suspend fun relabel(
        from: CurrencyCode,
        to: CurrencyCode,
        decimals: Int,
        updatedAt: Instant,
    ): Int
}
