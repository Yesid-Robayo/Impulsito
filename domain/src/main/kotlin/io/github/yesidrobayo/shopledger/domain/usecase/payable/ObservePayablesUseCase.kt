package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PayablesSource
import io.github.yesidrobayo.shopledger.domain.port.SupplierAccount
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierDebts
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Las cuentas por pagar, para su lista: cuánto se debe, qué vence y qué está vencido.
 *
 * Lo vencido se mide con la fecha de negocio de hoy, en la zona del negocio, y con el vencimiento
 * guardado en cada cargo: el reloj del teléfono solo dice qué día es hoy. Un cargo que vence hoy
 * todavía no está vencido. Lo que resta cubre los cargos más antiguos primero.
 *
 * El orden: primero lo vencido, desde lo más antiguo; después lo que vence, desde lo más próximo;
 * después lo que no tiene vencimiento, y al final los saldos a favor del negocio, por nombre.
 */
class ObservePayablesUseCase(
    private val source: PayablesSource,
    private val calendar: BusinessCalendarSource,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<PayablesLoad> =
        combine(source.observe(), calendar.observe()) { accounts, calendarResult ->
            when (calendarResult) {
                is BusinessCalendarResult.Ready ->
                    overviewOf(accounts, calendarResult.calendar.businessDateOf(clock.now()))

                is BusinessCalendarResult.UnknownZone ->
                    PayablesLoad.UnknownTimeZone(calendarResult.storedZoneId)
            }
        }

    private fun overviewOf(accounts: List<SupplierAccount>, today: BusinessDate): PayablesLoad {
        val weekEnd = today.plusDays(DUE_SOON_DAYS - 1)
        val rows = ArrayList<PayableRow>(accounts.size)
        var owed = Money.ZERO
        var dueSoon = Money.ZERO
        var overdueCount = 0
        try {
            for (account in accounts) {
                val debt = SupplierDebts.of(account.entries) ?: return PayablesLoad.LedgerDamaged
                val overdueSince = debt.overdueSince(today)
                val nextDue = debt.nextDue(today)
                val status = when {
                    debt.openCharges.isEmpty() -> PayableStatus.IN_FAVOR
                    overdueSince != null -> PayableStatus.OVERDUE
                    nextDue != null -> PayableStatus.UPCOMING
                    else -> PayableStatus.NO_DUE_DATE
                }
                if (status == PayableStatus.OVERDUE) overdueCount++
                owed += debt.owed
                dueSoon += debt.dueBetween(today, weekEnd)
                rows += PayableRow(
                    supplier = account.supplier,
                    status = status,
                    owed = debt.owed,
                    creditInFavor = debt.creditInFavor,
                    overdueSince = overdueSince,
                    overdueAmount = debt.overdueAmount(today),
                    nextDue = nextDue,
                )
            }
        } catch (overflow: ArithmeticException) {
            return PayablesLoad.LedgerDamaged
        }
        rows.sortWith(ROW_ORDER)
        return PayablesLoad.Ready(
            PayablesOverview(today, rows, owed, dueSoon, overdueCount),
        )
    }

    private companion object {
        /** `Vence en 7 días`: hoy y los seis días siguientes. */
        const val DUE_SOON_DAYS = 7

        val ROW_ORDER: Comparator<PayableRow> = compareBy<PayableRow> { it.status.ordinal }
            .thenBy { it.overdueSince ?: it.nextDue }
            .thenBy { it.supplier.details.normalizedName }
            .thenBy { it.supplier.id }
    }
}

/** Lo que muestra la lista de cuentas por pagar. */
sealed interface PayablesLoad {
    data class Ready(val overview: PayablesOverview) : PayablesLoad

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo saber qué es hoy. */
    data class UnknownTimeZone(val storedZoneId: String) : PayablesLoad

    /** Un libro tiene cifras que no caben: solo sale de una copia dañada. */
    data object LedgerDamaged : PayablesLoad
}

/**
 * Las cuentas por pagar a la fecha de negocio [today]: [owed] es lo que se debe en total,
 * [dueWithinWeek] lo que vence de hoy a seis días, y [overdueCount] cuántos proveedores tienen algo
 * vencido. [rows] va en el orden de la lista.
 */
data class PayablesOverview(
    val today: BusinessDate,
    val rows: List<PayableRow>,
    val owed: Money,
    val dueWithinWeek: Money,
    val overdueCount: Int,
)

/**
 * Un proveedor en la lista: lo que se le debe, o lo que tiene a favor el negocio, con desde cuándo
 * está vencido y el próximo vencimiento.
 */
data class PayableRow(
    val supplier: Supplier,
    val status: PayableStatus,
    val owed: Money,
    val creditInFavor: Money,
    val overdueSince: BusinessDate?,
    val overdueAmount: Money,
    val nextDue: BusinessDate?,
)

/** El grupo de un proveedor en la lista, en el orden en que se muestran. */
enum class PayableStatus {
    /** Algo está vencido. */
    OVERDUE,

    /** Nada vencido, y algo vence hoy o después. */
    UPCOMING,

    /** Se le debe, pero ningún cargo abierto tiene vencimiento. */
    NO_DUE_DATE,

    /** No se le debe nada, y el negocio tiene saldo a favor. */
    IN_FAVOR,
}
