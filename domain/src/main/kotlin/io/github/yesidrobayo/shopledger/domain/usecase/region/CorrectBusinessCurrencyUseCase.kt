package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.SafetyCopyFailure
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCorrection
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CurrencyCorrectionRepository
import io.github.yesidrobayo.shopledger.domain.port.SafetyCopyPort
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.CurrencyCorrectionProblem
import io.github.yesidrobayo.shopledger.domain.rule.region.CurrencyCorrectionRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina corregir la moneda del negocio. Salvo [Applied], la moneda no cambió. */
sealed interface CorrectCurrencyResult {
    /**
     * La moneda del negocio es ahora [currency], con los mismos decimales, el mismo bloqueo y el
     * mismo símbolo propio. Ningún monto cambió. [accounts] cuentas de dinero cambiaron de
     * etiqueta con ella.
     */
    data class Applied(val currency: BusinessCurrency, val accounts: Int) : CorrectCurrencyResult

    /** No se puede corregir a la moneda elegida, por [problem]. No se guardó copia ni nada. */
    data class Rejected(val problem: CurrencyCorrectionProblem) : CorrectCurrencyResult

    /** La copia local de seguridad no se guardó, por [failure], así que no se cambió nada. */
    data class SafetyCopyFailed(val failure: SafetyCopyFailure) : CorrectCurrencyResult

    /** La base no pudo leer o escribir, por [failure]. No quedó nada a medias. */
    data class StorageFailed(val failure: StorageFailure) : CorrectCurrencyResult
}

/**
 * Corrige la moneda del negocio cuando la persona la eligió mal y ya hay registros con dinero.
 *
 * - **Solo la etiqueta.** Cada monto se guarda en la unidad menor de la moneda: con los mismos
 *   decimales, el mismo número vale lo mismo en la moneda correcta. Por eso solo se aceptan
 *   monedas con los mismos decimales, y ningún monto se convierte ni se toca.
 * - **Nada incoherente.** Se rechaza la moneda de referencia, la de una cuenta propia y la que
 *   algún cobro, compra o cotización guardó como otra moneda (`CurrencyCorrectionProblem`).
 * - **Copia antes.** Revisa primero, para no gastar una copia en una corrección que no vale;
 *   después guarda la copia local de seguridad, y sin ella no cambia nada.
 * - **Una sola transacción.** Dentro de ella vuelve a revisar, porque algo pudo cambiar mientras
 *   se copiaba, y cambia la moneda y las cuentas que la seguían. Cuenta como cambio del negocio.
 * - **Auditoría.** Anota el resultado tras confirmarlo, sin montos ni monedas: el éxito, o la
 *   falla de la copia o de la base. Un rechazo por una regla no se anota, porque no se intentó
 *   nada.
 */
class CorrectBusinessCurrencyUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val corrections: CurrencyCorrectionRepository,
    private val safetyCopy: SafetyCopyPort,
    private val audit: AuditRecorder,
    private val clock: Clock,
) {
    /** Corrige la moneda del negocio a la de [request], después de que la persona lo confirmó. */
    suspend operator fun invoke(request: CurrencyCorrection): CorrectCurrencyResult {
        val result = attempt(request)
        when (result) {
            is CorrectCurrencyResult.Applied ->
                audit.record(AuditEventType.CURRENCY_CORRECTED, AuditResult.SUCCESS)

            is CorrectCurrencyResult.SafetyCopyFailed, is CorrectCurrencyResult.StorageFailed ->
                audit.record(AuditEventType.CURRENCY_CORRECTED, AuditResult.FAILURE)

            is CorrectCurrencyResult.Rejected -> Unit
        }
        return result
    }

    private suspend fun attempt(request: CurrencyCorrection): CorrectCurrencyResult {
        return storageSafe(CorrectCurrencyResult::StorageFailed) {
            val problem = transactions.read { problemOf(request) }
            if (problem != null) return CorrectCurrencyResult.Rejected(problem)
            val copyFailure = safetyCopy.saveBeforeChange()
            if (copyFailure != null) return CorrectCurrencyResult.SafetyCopyFailed(copyFailure)
            transactions.write { relabel(request) }
        }
    }

    private suspend fun relabel(request: CurrencyCorrection): TxResult<CorrectCurrencyResult> {
        // Lo revisado antes de la copia pudo cambiar mientras se copiaba: se revisa de nuevo con
        // lo que hay en esta transacción.
        problemOf(request)?.let { return TxResult.Rollback(CorrectCurrencyResult.Rejected(it)) }
        val current = settings.get().regional.currency
        val accounts =
            corrections.relabel(
                from = current.code,
                to = request.code,
                decimals = current.decimals,
                updatedAt = clock.now(),
            )
        return TxResult.Commit(
            CorrectCurrencyResult.Applied(current.copy(code = request.code), accounts),
        )
    }

    /** El primer problema de corregir a [request] con lo que hay en la base, o `null`. */
    private suspend fun problemOf(request: CurrencyCorrection): CurrencyCorrectionProblem? =
        CurrencyCorrectionRules.check(settings.get().regional, request)
            ?: when {
                corrections.accountsIn(request.code) > 0 ->
                    CurrencyCorrectionProblem.USED_BY_ACCOUNT

                corrections.usedAsOtherCurrency(request.code) ->
                    CurrencyCorrectionProblem.USED_AS_OTHER_CURRENCY

                else -> null
            }
}
