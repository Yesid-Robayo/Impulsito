package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.CurrencyCorrectionRepository
import kotlin.time.Instant

/**
 * La corrección de la moneda del negocio sobre la base: lee lo que la haría incoherente y cambia
 * la etiqueta de `app_settings` y de las cuentas que la seguían, en la transacción del caso de
 * uso.
 *
 * Primero cambia la moneda del negocio y después las cuentas: la guarda de `money_accounts` solo
 * deja cambiar la moneda de una cuenta con movimientos hacia la del negocio, con los mismos
 * decimales.
 */
internal class RoomCurrencyCorrectionRepository(database: AppDatabase) :
    CurrencyCorrectionRepository {
    private val dao = database.currencyCorrectionDao()
    private val writes = RepositoryWrites(database)

    override suspend fun accountsIn(code: CurrencyCode): Int = dao.accountsIn(code.code).toInt()

    override suspend fun usedAsOtherCurrency(code: CurrencyCode): Boolean =
        dao.usedAsOtherCurrency(code.code)

    override suspend fun relabel(
        from: CurrencyCode,
        to: CurrencyCode,
        decimals: Int,
        updatedAt: Instant,
    ): Int = writes.inTransaction {
        val at = updatedAt.toStoredMillis()
        // Si la moneda ya no es la de origen, algo cambió dentro de la transacción: un error de
        // quien llama, que la deshace entera.
        requireOneRow(dao.relabelBusiness(from.code, to.code, decimals.toLong(), at))
        dao.relabelAccounts(from.code, to.code, decimals.toLong(), at)
    }
}
