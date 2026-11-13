package io.github.yesidrobayo.shopledger.ui.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.TransferRequest
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyOverview
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.ArchiveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.FindMoneyDocumentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAccountLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObserveArchivedMoneyAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObserveMoneyAccountsInUseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObserveMoneyAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObserveMoneyMovementsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObservePendingSettlementUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.ReactivateMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.RecordManualMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.RecordManualMoneyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.TransferMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.TransferMoneyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.ui.region.RegionalOption
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Lo que la pantalla de cuentas le pide al dominio. Es una interfaz para que las pruebas del
 * ViewModel den respuestas fijas sin una base.
 */
interface MoneyAccountsOperations {
    /** Las cuentas vigentes con su dinero, separado por moneda. */
    fun overview(): Flow<MoneyOverview>

    fun archived(): Flow<List<MoneyAccount>>

    /** Si la persona ya lleva cuentas: más de una vigente, o algún movimiento. */
    fun inUse(): Flow<Boolean>

    fun calendar(): Flow<BusinessCalendarResult>

    /** Lo que falta por llegar a cada cuenta a la fecha [today]. */
    fun pending(today: BusinessDate): Flow<Map<Long, Money>>

    /** El libro de una cuenta, en su orden. */
    fun movements(accountId: Long): Flow<List<MoneyMovement>>

    suspend fun record(request: ManualMoneyRequest): RecordManualMoneyResult

    suspend fun transfer(request: TransferRequest): TransferMoneyResult

    suspend fun void(movementId: Long): VoidMoneyMovementResult

    suspend fun save(editingId: Long?, draft: MoneyAccountDraft): SaveMoneyAccountResult

    suspend fun archive(accountId: Long): MoneyAccountLifecycleResult

    suspend fun reactivate(accountId: Long): MoneyAccountLifecycleResult

    suspend fun document(movementId: Long): MoneyDocument?

    fun now(): Instant
}

/** Las monedas que se ofrecen al crear una cuenta, con sus decimales de la norma. */
interface CurrencyChoices {
    /** Las monedas en uso hoy, por nombre en el idioma de la interfaz. */
    fun options(): List<RegionalOption>

    /** Los decimales de [code], o `null` si el teléfono no la conoce. */
    fun decimals(code: CurrencyCode): Int?
}

/**
 * Los símbolos con que se leen las cifras que escribe la persona: [money], los de un monto, que no
 * cambian con la moneda de la cuenta, y [rate], los de un número cualquiera, para la cotización.
 */
data class MoneyInputFormat(val money: NumberSymbols, val rate: NumberSymbols)

/** Las operaciones sobre los casos de uso de cuentas de dinero. */
class MoneyAccountsUseCases(
    private val observeAccounts: ObserveMoneyAccountsUseCase,
    private val observeArchived: ObserveArchivedMoneyAccountsUseCase,
    private val observeInUse: ObserveMoneyAccountsInUseUseCase,
    private val calendarSource: BusinessCalendarSource,
    private val observePending: ObservePendingSettlementUseCase,
    private val observeMovements: ObserveMoneyMovementsUseCase,
    private val recordManual: RecordManualMoneyUseCase,
    private val transferMoney: TransferMoneyUseCase,
    private val voidMovement: VoidMoneyMovementUseCase,
    private val saveAccount: SaveMoneyAccountUseCase,
    private val archiveAccount: ArchiveMoneyAccountUseCase,
    private val reactivateAccount: ReactivateMoneyAccountUseCase,
    private val findDocument: FindMoneyDocumentUseCase,
    private val clockNow: () -> Instant,
) : MoneyAccountsOperations {
    override fun overview(): Flow<MoneyOverview> = observeAccounts()

    override fun archived(): Flow<List<MoneyAccount>> = observeArchived()

    override fun inUse(): Flow<Boolean> = observeInUse()

    override fun calendar(): Flow<BusinessCalendarResult> = calendarSource.observe()

    override fun pending(today: BusinessDate): Flow<Map<Long, Money>> = observePending(today)

    override fun movements(accountId: Long): Flow<List<MoneyMovement>> = observeMovements(accountId)

    override suspend fun record(request: ManualMoneyRequest): RecordManualMoneyResult =
        recordManual(request)

    override suspend fun transfer(request: TransferRequest): TransferMoneyResult =
        transferMoney(request)

    override suspend fun void(movementId: Long): VoidMoneyMovementResult = voidMovement(movementId)

    override suspend fun save(editingId: Long?, draft: MoneyAccountDraft): SaveMoneyAccountResult =
        saveAccount(editingId, draft)

    override suspend fun archive(accountId: Long): MoneyAccountLifecycleResult =
        archiveAccount(accountId)

    override suspend fun reactivate(accountId: Long): MoneyAccountLifecycleResult =
        reactivateAccount(accountId)

    override suspend fun document(movementId: Long): MoneyDocument? = findDocument(movementId)

    override fun now(): Instant = clockNow()

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): MoneyAccountsUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            val writer = MoneyLedgerWriter(ports.moneyAccounts, ports.moneyLedger)
            return MoneyAccountsUseCases(
                observeAccounts = ObserveMoneyAccountsUseCase(ports.settings, ports.moneyAccounts),
                observeArchived = ObserveArchivedMoneyAccountsUseCase(ports.moneyAccounts),
                observeInUse = ObserveMoneyAccountsInUseUseCase(ports.moduleUsage),
                calendarSource = BusinessCalendarSource(ports.settings),
                observePending = ObservePendingSettlementUseCase(ports.moneyLedger),
                observeMovements = ObserveMoneyMovementsUseCase(ports.moneyLedger),
                recordManual = RecordManualMoneyUseCase(
                    tx,
                    ports.settings,
                    ports.moneyAccounts,
                    writer,
                    clock,
                ),
                transferMoney = TransferMoneyUseCase(
                    tx,
                    ports.settings,
                    ports.moneyAccounts,
                    writer,
                    clock,
                ),
                voidMovement = VoidMoneyMovementUseCase(
                    tx,
                    ports.settings,
                    ports.moneyLedger,
                    writer,
                    clock,
                ),
                saveAccount = SaveMoneyAccountUseCase(
                    tx,
                    ports.settings,
                    ports.moneyAccounts,
                    writer,
                    clock,
                ),
                archiveAccount = ArchiveMoneyAccountUseCase(tx, ports.moneyAccounts, clock),
                reactivateAccount = ReactivateMoneyAccountUseCase(tx, ports.moneyAccounts, clock),
                findDocument = FindMoneyDocumentUseCase(ports.moneyLedger),
                clockNow = clock::now,
            )
        }
    }
}
