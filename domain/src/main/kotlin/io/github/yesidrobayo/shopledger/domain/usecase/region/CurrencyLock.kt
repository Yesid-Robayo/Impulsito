package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import kotlin.time.Instant

/** Resultado de [CurrencyLock.lockForMoneyRecord]. */
sealed interface CurrencyLockResult {
    /**
     * La moneda está confirmada y bloqueada: [currency] es la del registro, con sus decimales y
     * el instante del bloqueo, que puede ser de este mismo registro o de uno anterior.
     */
    data class Locked(val currency: BusinessCurrency) : CurrencyLockResult

    /**
     * La moneda sigue sin confirmar: el registro no se guarda. Quien llama deshace la transacción
     * y la pantalla pide confirmar la moneda, sin perder lo escrito.
     */
    data object Unconfirmed : CurrencyLockResult
}

/**
 * Lo que hace todo caso de uso que guarda dinero antes de escribirlo: comprobar que la moneda del
 * negocio está confirmada y, si es el primer registro con dinero, bloquearla en la misma
 * transacción.
 *
 * El bloqueo existe porque cada monto se guarda en la unidad menor de la moneda: cambiar la moneda
 * o sus decimales después cambiaría el valor de todo lo guardado. Mientras la moneda es `XXX`, sin
 * confirmar, no se guarda nada con dinero.
 *
 * Se llama dentro de `TransactionRunner.write`, justo antes de escribir el registro, y nunca fuera:
 * así la moneda que se lee es la vigente en esa transacción, y si el registro falla o se deshace, el
 * bloqueo también se deshace. Un registro sin dinero, como un producto con precio y costo en cero,
 * no lo llama: no fija ninguna moneda.
 */
class CurrencyLock(private val settings: AppSettingsRepository) {
    /**
     * Comprueba la moneda y la bloquea en [at] si todavía no lo estaba. Con la moneda sin
     * confirmar no escribe nada y devuelve [CurrencyLockResult.Unconfirmed].
     */
    suspend fun lockForMoneyRecord(at: Instant): CurrencyLockResult {
        val currency = settings.get().regional.currency
        return when {
            !currency.code.isConfirmed -> CurrencyLockResult.Unconfirmed

            currency.isLocked -> CurrencyLockResult.Locked(currency)

            // Dentro de la transacción nadie más escribe, así que el bloqueo es de este registro.
            // Si aun así ya estaba puesto, vale el guardado, que no se mueve.
            settings.lockCurrency(at) -> CurrencyLockResult.Locked(currency.copy(lockedAt = at))

            else -> CurrencyLockResult.Locked(settings.get().regional.currency)
        }
    }
}
