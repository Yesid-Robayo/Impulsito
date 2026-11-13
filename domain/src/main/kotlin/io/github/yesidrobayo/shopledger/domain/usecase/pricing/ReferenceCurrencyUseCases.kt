package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.pricing.NewFxRate
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.FxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ReferenceRateRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Resultado de [SetReferenceCurrencyUseCase]. */
sealed interface SetReferenceCurrencyResult {
    /** Quedó así, o ya estaba así y no hacía falta escribir. */
    data object Saved : SetReferenceCurrencyResult

    /** No se puede elegir esa moneda, por [issue]. No cambió nada. */
    data class Rejected(val issue: ReferenceRateRules.ReferenceIssue) : SetReferenceCurrencyResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : SetReferenceCurrencyResult
}

/**
 * Elige la moneda de referencia, o la quita con `null`. Es un dato del negocio: va en su propia
 * transacción y cuenta como cambio sin respaldar. Cambiarla no toca ningún precio ni ninguna
 * cotización: las cotizaciones de cada moneda se conservan, y vuelven si se vuelve a elegir.
 */
class SetReferenceCurrencyUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(reference: ReferenceCurrency?): SetReferenceCurrencyResult = try {
        transactions.write {
            val regional = settings.get().regional
            val issue = reference?.let {
                ReferenceRateRules.referenceIssue(it, regional.currency.code)
            }
            when {
                issue != null -> TxResult.Rollback(SetReferenceCurrencyResult.Rejected(issue))

                regional.referenceCurrency == reference ->
                    TxResult.Rollback(SetReferenceCurrencyResult.Saved)

                else -> {
                    settings.updateReferenceCurrency(reference, clock.now())
                    TxResult.Commit(SetReferenceCurrencyResult.Saved)
                }
            }
        }
    } catch (failure: StorageFailureException) {
        SetReferenceCurrencyResult.Failed(failure.failure)
    }
}

/** Resultado de [RecordReferenceRateUseCase]. */
sealed interface RecordReferenceRateResult {
    /** Se agregó la cotización [rateId]. Ningún precio cambió. */
    data class Recorded(val rateId: Long) : RecordReferenceRateResult

    /** No hay moneda de referencia elegida: no hay de qué escribir la cotización. */
    data object NoReferenceCurrency : RecordReferenceRateResult

    /** La cotización es cero o pasa del tope. */
    data object RateOutOfRange : RecordReferenceRateResult

    /** La nota pasa de [ReferenceRateRules.NOTE_MAX] caracteres. */
    data object NoteTooLong : RecordReferenceRateResult

    /** La fecha elegida es posterior a hoy: una cotización no se registra por adelantado. */
    data object FutureDate : RecordReferenceRateResult

    /** El teléfono no conoce la zona del negocio: no se puede fechar la cotización. */
    data object UnknownTimeZone : RecordReferenceRateResult

    /** La base no pudo escribir, por [failure]. No se agregó nada. */
    data class Failed(val failure: StorageFailure) : RecordReferenceRateResult
}

/**
 * Agrega una cotización de la moneda de referencia, escrita por la persona, en su propia
 * transacción. Va con la fecha de hoy y la hora de ahora, o con una fecha pasada a las 12:00, como
 * una compra atrasada; nunca con una futura. Solo agrega: la anterior queda en el historial, y ningún precio se
 * recalcula. Repreciar desde la cotización es otra acción de la persona, con vista previa.
 */
class RecordReferenceRateUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val rates: FxRateRepository,
    private val clock: Clock,
) {
    private val calendar = BusinessCalendarSource(settings)

    suspend operator fun invoke(
        rateMicro: Long,
        note: String?,
        date: BusinessDate? = null,
    ): RecordReferenceRateResult = try {
        transactions.write { record(rateMicro, note, date) }
    } catch (failure: StorageFailureException) {
        RecordReferenceRateResult.Failed(failure.failure)
    }

    private suspend fun record(
        rateMicro: Long,
        note: String?,
        date: BusinessDate?,
    ): TxResult<RecordReferenceRateResult> {
        val reference = settings.get().regional.referenceCurrency
            ?: return TxResult.Rollback(RecordReferenceRateResult.NoReferenceCurrency)
        if (!ReferenceRateRules.isValidRate(rateMicro)) {
            return TxResult.Rollback(RecordReferenceRateResult.RateOutOfRange)
        }
        val trimmed = ReferenceRateRules.note(note)
        if ((trimmed?.length ?: 0) > ReferenceRateRules.NOTE_MAX) {
            return TxResult.Rollback(RecordReferenceRateResult.NoteTooLong)
        }
        val now = clock.now()
        val today = calendar.stamp(now)
            ?: return TxResult.Rollback(RecordReferenceRateResult.UnknownTimeZone)
        val time = when {
            date == null || date == today.businessDate -> today

            date > today.businessDate ->
                return TxResult.Rollback(RecordReferenceRateResult.FutureDate)

            else -> calendar.backdated(date)
        }
        val id = rates.append(
            NewFxRate(
                currency = reference.code,
                rateMicro = rateMicro,
                note = trimmed,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            ),
        )
        return TxResult.Commit(RecordReferenceRateResult.Recorded(id))
    }
}

/**
 * La moneda de referencia, su cotización vigente y su historial, de la más reciente a la más
 * vieja. [reference] es `null` si la persona no eligió ninguna; entonces no hay cotizaciones.
 */
data class ReferenceRateView(
    val reference: ReferenceCurrency?,
    val latest: FxRate?,
    val history: List<FxRate>,
)

/**
 * La moneda de referencia con su cotización vigente y su historial, de nuevo cada vez que cambia la
 * moneda o se escribe una cotización. Solo lee lo que escribió la persona.
 */
class ObserveReferenceRateUseCase(
    private val settings: AppSettingsRepository,
    private val rates: FxRateRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<ReferenceRateView> = settings.observe()
        .map { it.regional.referenceCurrency }
        .distinctUntilChanged()
        .flatMapLatest { reference ->
            if (reference == null) {
                flowOf(ReferenceRateView(null, null, emptyList()))
            } else {
                combine(
                    rates.observeLatest(reference.code),
                    rates.observeHistory(reference.code),
                ) { latest, history -> ReferenceRateView(reference, latest, history) }
            }
        }
        .distinctUntilChanged()
}

/**
 * Las 12:00 de [date] en la zona del negocio. Solo se llama después de [stamp], que ya confirmó que
 * la zona se conoce.
 */
private suspend fun BusinessCalendarSource.backdated(date: BusinessDate): RecordTime =
    when (val result = current()) {
        is BusinessCalendarResult.Ready -> result.calendar.stampBackdated(date)

        is BusinessCalendarResult.UnknownZone -> error(
            "La zona se confirmó en esta misma transacción",
        )
    }
