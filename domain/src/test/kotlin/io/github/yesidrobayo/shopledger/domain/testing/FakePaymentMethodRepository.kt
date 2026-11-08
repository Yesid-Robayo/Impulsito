package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [PaymentMethodRepository] sobre [InMemoryDatabase], con las reglas de la base real: un nombre
 * no se repite entre los vigentes, y un método con ventas no se borra.
 *
 * Las ventas y las cuentas de dinero no las escriben estos casos de uso: cada prueba las fija en
 * [methodsWithSales] y [activeMoneyAccounts].
 */
internal class FakePaymentMethodRepository(private val database: InMemoryDatabase) :
    PaymentMethodRepository {
    val methodsWithSales: MutableSet<Long> = mutableSetOf()
    val activeMoneyAccounts: MutableSet<Long> = mutableSetOf()

    override fun observe(filter: ArchiveFilter): Flow<List<PaymentMethodRecord>> =
        database.tables.map { tables -> ordered(tables.paymentMethods.values, filter) }

    override suspend fun findActive(): List<PaymentMethodRecord> =
        ordered(database.read().paymentMethods.values, ArchiveFilter.ACTIVE)

    override suspend fun findById(id: Long): PaymentMethodRecord? =
        database.read().paymentMethods[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): PaymentMethodRecord? =
        findActive().singleOrNull { it.details.normalizedName == normalizedName }

    override suspend fun hasSalePayments(id: Long): Boolean = id in methodsWithSales

    override suspend fun isActiveMoneyAccount(id: Long): Boolean = id in activeMoneyAccounts

    override suspend fun maxSortOrder(): Long? =
        database.read().paymentMethods.values.maxOfOrNull { it.sortOrder }

    override suspend fun insert(
        details: PaymentMethodDetails,
        sortOrder: Long,
        createdAt: Instant,
    ): Long {
        checkNameIsFree(details, id = null, archived = false)
        val id = database.nextId(TABLE)
        val method = PaymentMethodRecord(id, null, details, sortOrder, null, createdAt, createdAt)
        database.write { it.copy(paymentMethods = it.paymentMethods + (id to method)) }
        return id
    }

    override suspend fun update(id: Long, details: PaymentMethodDetails, updatedAt: Instant) {
        val method = existing(id)
        checkNameIsFree(details, id, method.isArchived)
        put(method.copy(details = details, updatedAt = updatedAt))
    }

    override suspend fun setSortOrder(id: Long, sortOrder: Long, updatedAt: Instant) {
        put(existing(id).copy(sortOrder = sortOrder, updatedAt = updatedAt))
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        val method = existing(id)
        if (archivedAt == null) checkNameIsFree(method.details, id, archived = false)
        put(method.copy(archivedAt = archivedAt, updatedAt = updatedAt))
    }

    override suspend fun delete(id: Long) {
        existing(id)
        check(id !in methodsWithSales) { "Un método con ventas no se borra" }
        database.write { it.copy(paymentMethods = it.paymentMethods - id) }
    }

    /**
     * Crea los seis métodos iniciales como los crea la base al nacer: sin nombre, sin comisión y en
     * el orden de sus claves. No cuenta como cambio del negocio.
     */
    fun seedInitialMethods(at: Instant) {
        database.begin()
        SeedTable.PAYMENT_METHODS.systemKeys.forEachIndexed { index, key ->
            val id = database.nextId(TABLE)
            val method = PaymentMethodRecord(
                id = id,
                systemKey = key,
                details = initialDetails(PaymentMethodKind.valueOf(key)),
                sortOrder = index + 1L,
                archivedAt = null,
                createdAt = at,
                updatedAt = at,
            )
            database.write { it.copy(paymentMethods = it.paymentMethods + (id to method)) }
        }
        database.commit(counted = false)
    }

    private fun existing(id: Long): PaymentMethodRecord =
        checkNotNull(database.read().paymentMethods[id]) { "El método no existe" }

    private fun put(method: PaymentMethodRecord) {
        database.write { it.copy(paymentMethods = it.paymentMethods + (method.id to method)) }
    }

    private suspend fun checkNameIsFree(
        details: PaymentMethodDetails,
        id: Long?,
        archived: Boolean,
    ) {
        val name = details.normalizedName ?: return
        if (archived) return
        val holder = findActiveByNormalizedName(name)
        check(holder == null || holder.id == id) { "El nombre ya es de otro vigente" }
    }

    private fun ordered(
        methods: Collection<PaymentMethodRecord>,
        filter: ArchiveFilter,
    ): List<PaymentMethodRecord> = methods
        .filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
        .sortedWith(compareBy<PaymentMethodRecord> { it.sortOrder }.thenBy { it.id })

    companion object {
        private const val TABLE = "payment_methods"

        fun initialDetails(kind: PaymentMethodKind): PaymentMethodDetails = PaymentMethodDetails(
            name = null,
            normalizedName = null,
            kind = kind,
            feeRate = BasisPoints.ZERO,
            feeFixed = Money.ZERO,
            settlementDays = 0,
            priceAdjustment = BasisPoints.ZERO,
            moneyAccountId = null,
            instructionsText = null,
            instructionsMediaId = null,
        )
    }
}
